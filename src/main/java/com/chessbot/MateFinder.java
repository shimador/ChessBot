package com.chessbot;

import java.util.Optional;
import java.util.concurrent.CancellationException;

/** Exhaustive mate search: tries every move, on one board, with preallocated move buffers. */
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

    /**
     * Finds a move that forces mate in at most {@code n} moves: there is a move such that
     * every opponent reply still allows a mate in {@code n - 1}.
     * Throws {@link CancellationException} if the calling thread is interrupted.
     */
    public static Optional<Move> findMate(Position position, int n) {
        SearchContext ctx = SearchContext.load(position);
        int move = attackerMates(ctx.position, ctx.moves, n, 0);
        return move == NONE ? Optional.empty() : Optional.of(Moves.toMove(move, position.whiteToMove));
    }

    private static final int NONE = -1;

    /** Side to move attacks: returns a move forcing mate in at most n, or NONE. */
    private static int attackerMates(Position p, int[][] moves, int n, int ply) {
        int[] buffer = moves[ply];
        int count = MoveGenerator.generate(p, buffer);
        for (int i = 0; i < count; i++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            int move = buffer[i];
            p.make(move);
            boolean mates = !MoveGenerator.isKingAttacked(p, !p.whiteToMove) // legal
                    && defenderLoses(p, moves, n, ply + 1);
            p.unmake(move);
            if (mates) return move;
        }
        return NONE;
    }

    /**
     * Side to move defends: true if it is mated now, or if every legal reply still allows
     * the attacker a mate in n - 1. Stalemate is a draw, so it returns false.
     */
    private static boolean defenderLoses(Position p, int[][] moves, int n, int ply) {
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
            boolean escapes = legal && (n == 1 || attackerMates(p, moves, n - 1, ply + 1) == NONE);
            p.unmake(reply);
            if (escapes) return false;
            hasLegalMove |= legal;
        }
        return hasLegalMove || p.isInCheck();
    }
}
