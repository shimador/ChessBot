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
        SearchContext ctx = SearchContext.load(position);
        return perft(ctx.position, ctx.moves, depth, 0);
    }

    private static long perft(Position p, int[][] moves, int depth, int ply) {
        if (depth == 0) return 1;
        int[] buffer = moves[ply];
        int count = MoveGenerator.generate(p, buffer);
        long nodes = 0;
        for (int i = 0; i < count; i++) {
            p.make(buffer[i]);
            if (!MoveGenerator.isKingAttacked(p, !p.whiteToMove)) {
                nodes += perft(p, moves, depth - 1, ply + 1);
            }
            p.unmake(buffer[i]);
        }
        return nodes;
    }
}
