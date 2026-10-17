package com.chessbot;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/** Brute-force mate search. Intentionally unoptimised: it is the baseline to improve on. */
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
        for (Move move : MoveGenerator.legalMoves(position)) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            Position next = position.makeMove(move);

            if (n == 1) {
                if (next.isInCheck() && MoveGenerator.legalMoves(next).isEmpty()) return Optional.of(move);
                continue;
            }

            List<Move> replies = MoveGenerator.legalMoves(next);
            if (replies.isEmpty()) {
                if (next.isInCheck()) return Optional.of(move); // mates even faster
                continue; // stalemate is a draw, not a win
            }

            boolean everyReplyLoses = true;
            for (Move reply : replies) {
                if (findMate(next.makeMove(reply), n - 1).isEmpty()) {
                    everyReplyLoses = false; // this reply escapes, so the move doesn't force mate
                    break;
                }
            }
            if (everyReplyLoses) return Optional.of(move);
        }
        return Optional.empty();
    }
}
