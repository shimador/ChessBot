package com.chessbot;

import java.util.HashSet;
import java.util.Set;

/**
 * Justification measurement for the transposition table: replays the mate search (same order,
 * same pruning) and counts how many attacker nodes (position + remaining moves) were already
 * searched before. Every such node is work a memo table would skip.
 * <pre>
 *   java -cp target-bench/classes;target-bench/test-classes com.chessbot.TranspositionDiagnostic 4
 * </pre>
 */
public final class TranspositionDiagnostic {
    private static long visits;
    private static final Set<Long> seen = new HashSet<>();
    private static long repeats;

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        Position p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        attacker(p, n, 0);
        System.out.printf("Mate in %d on Kiwipete: %d attacker nodes, %d distinct, %d already searched (%.1f %%)%n",
                n, visits, seen.size(), repeats, 100.0 * repeats / visits);
    }

    static boolean attacker(Position p, int n, int ply) {
        if (ply > 0) {
            visits++;
            if (!seen.add(p.hash * 31 + n)) repeats++;
        }
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.generate(p, moves);
        for (int i = 0; i < count; i++) {
            p.make(moves[i]);
            boolean mates = !MoveGenerator.isKingAttacked(p, !p.whiteToMove) && defender(p, n, ply + 1);
            p.unmake(moves[i]);
            if (mates) return true;
        }
        return false;
    }

    static boolean defender(Position p, int n, int ply) {
        if (n == 1 && !p.isInCheck()) return false;
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.generate(p, moves);
        boolean hasLegalMove = false;
        for (int i = 0; i < count; i++) {
            p.make(moves[i]);
            boolean legal = !MoveGenerator.isKingAttacked(p, !p.whiteToMove);
            boolean escapes = legal && (n == 1 || !attacker(p, n - 1, ply + 1));
            p.unmake(moves[i]);
            if (escapes) return false;
            hasLegalMove |= legal;
        }
        return hasLegalMove || p.isInCheck();
    }
}
