package com.chessbot;

/**
 * Counts the leaf nodes of the legal move tree to a given depth. Compare against known values
 * (https://www.chessprogramming.org/Perft_Results) to prove the move generator is correct,
 * and re-run after every optimisation to make sure it still is.
 */
public final class Perft {
    private Perft() {
    }

    public static long perft(Position position, int depth) {
        if (depth == 0) return 1;
        long nodes = 0;
        for (Move move : MoveGenerator.legalMoves(position)) {
            nodes += perft(position.makeMove(move), depth - 1);
        }
        return nodes;
    }
}
