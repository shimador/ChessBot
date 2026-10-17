package com.chessbot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exhaustive mate search: tries every move, on one board, with preallocated move buffers.
 * Results of positions already searched are reused through a {@link MateTable}.
 */
public final class MateFinder {
    private MateFinder() {
    }

    /**
     * Tries every legal move. A move is mate if, afterwards, the opponent is in check
     * and has no legal reply.
     */
    public static Optional<Move> findMateInOne(Position position) {
        return findMate(position, 1);
    }

    /** Default number of worker threads: one per logical core. */
    public static final int DEFAULT_THREADS = Runtime.getRuntime().availableProcessors();

    /**
     * Finds a move that forces mate in at most {@code n} moves: there is a move such that
     * every opponent reply still allows a mate in {@code n - 1}. Uses {@link #DEFAULT_THREADS} threads.
     * Throws {@link CancellationException} if the calling thread is interrupted.
     */
    public static Optional<Move> findMate(Position position, int n) {
        return findMate(position, n, DEFAULT_THREADS);
    }

    /** Same as {@link #findMate(Position, int)} with an explicit number of threads (1 = sequential). */
    public static Optional<Move> findMate(Position position, int n, int threads) {
        return threads <= 1 ? findMateSequential(position, n) : findMateParallel(position, n, threads);
    }

    private static Optional<Move> findMateSequential(Position position, int n) {
        SearchContext ctx = SearchContext.load(position);
        MateTable table = ctx.mateTable();
        table.newSearch();
        int move = attackerMates(ctx.position, ctx.moves, table, n, 0);
        return move == NONE ? Optional.empty() : Optional.of(Moves.toMove(move, position.whiteToMove));
    }

    // ---- Parallel search: the root moves are shared out to a bounded pool of worker threads ----

    private static final Map<Integer, ExecutorService> POOLS = new ConcurrentHashMap<>();
    private static final AtomicLong SEARCH_IDS = new AtomicLong();

    /** One fixed-size pool per thread count, created once and reused (daemon threads). */
    private static ExecutorService pool(int threads) {
        return POOLS.computeIfAbsent(threads, t -> Executors.newFixedThreadPool(t, task -> {
            Thread worker = new Thread(task, "mate-worker");
            worker.setDaemon(true);
            return worker;
        }));
    }

    /**
     * Each legal root move becomes a task. Every worker has its own buffers and its own table
     * (thread-local), so workers share nothing and need no lock. The answer is the first mating
     * root move in generation order, as in the sequential search; once it is known, the stop flag
     * makes the remaining workers give up.
     */
    private static Optional<Move> findMateParallel(Position position, int n, int threads) {
        Position root = position.copy();
        int[] buffer = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.generate(root, buffer);
        long searchId = SEARCH_IDS.incrementAndGet();
        AtomicBoolean stop = new AtomicBoolean();

        List<Integer> rootMoves = new ArrayList<>();
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int move = buffer[i];
            root.make(move);
            boolean legal = !MoveGenerator.isKingAttacked(root, !root.whiteToMove);
            root.unmake(move);
            if (!legal) continue;
            rootMoves.add(move);
            results.add(pool(threads).submit(() -> rootMoveMates(position, move, n, searchId, stop)));
        }

        try {
            for (int k = 0; k < results.size(); k++) {
                if (results.get(k).get()) return Optional.of(Moves.toMove(rootMoves.get(k), position.whiteToMove));
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        } finally {
            stop.set(true); // answer known (or search abandoned): stop the other workers
            for (Future<Boolean> f : results) f.cancel(false);
        }
    }

    /** Worker task: does this root move force mate in n? */
    private static boolean rootMoveMates(Position start, int move, int n, long searchId, AtomicBoolean stop) {
        SearchContext ctx = SearchContext.load(start);
        MateTable table = ctx.mateTable();
        table.joinSearch(searchId, stop);
        ctx.position.make(move);
        return defenderLoses(ctx.position, ctx.moves, table, n, 1);
    }

    private static final int NONE = -1;
    private static final int FOUND = Integer.MAX_VALUE; // mate known from the table (no move needed below the root)

    /** Side to move attacks: returns a move forcing mate in at most n, or NONE. */
    private static int attackerMates(Position p, int[][] moves, MateTable table, int n, int ply) {
        if (ply > 0) {
            int known = table.probe(p.hash, n);
            if (known != MateTable.UNKNOWN) return known == MateTable.MATE ? FOUND : NONE;
        }
        int result = NONE;
        int[] buffer = moves[ply];
        int count = MoveGenerator.generate(p, buffer);
        for (int i = 0; i < count; i++) {
            if (Thread.currentThread().isInterrupted() || table.stopRequested()) throw new CancellationException();
            int move = buffer[i];
            p.make(move);
            boolean mates = !MoveGenerator.isKingAttacked(p, !p.whiteToMove) // legal
                    && defenderLoses(p, moves, table, n, ply + 1);
            p.unmake(move);
            if (mates) {
                result = move;
                break;
            }
        }
        if (ply > 0) table.store(p.hash, n, result != NONE);
        return result;
    }

    /**
     * Side to move defends: true if it is mated now, or if every legal reply still allows
     * the attacker a mate in n - 1. Stalemate is a draw, so it returns false.
     */
    private static boolean defenderLoses(Position p, int[][] moves, MateTable table, int n, int ply) {
        // Last move: only a check can be mate, so skip generating replies otherwise
        if (n == 1 && !p.isInCheck()) return false;
        int[] buffer = moves[ply];
        int count = MoveGenerator.generate(p, buffer);
        boolean hasLegalMove = false;
        for (int i = 0; i < count; i++) {
            int reply = buffer[i];
            p.make(reply);
            boolean legal = !MoveGenerator.isKingAttacked(p, !p.whiteToMove);
            // With no move left (n == 1), any legal reply means it is not mate
            boolean escapes = legal && (n == 1 || attackerMates(p, moves, table, n - 1, ply + 1) == NONE);
            p.unmake(reply);
            if (escapes) return false;
            hasLegalMove |= legal;
        }
        return hasLegalMove || p.isInCheck();
    }
}
