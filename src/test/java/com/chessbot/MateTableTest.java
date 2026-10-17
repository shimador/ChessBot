package com.chessbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The transposition table must never change a verdict: compare against the naive search, which has none. */
class MateTableTest {
    private static final String[] FENS = {
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "5rk1/5Npp/8/3Q4/8/8/8/6K1 w - - 0 1",
            "7k/8/5K2/8/8/8/8/R7 w - - 0 1",
            "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 2 3",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "7k/8/8/8/8/8/8/K5Q1 w - - 0 1",
    };

    @Test
    void sameVerdictAsSearchWithoutTable() {
        for (String fen : FENS) {
            for (int n = 1; n <= 3; n++) {
                boolean expected = TranspositionDiagnostic.attacker(Position.fromFen(fen), n, 0);
                Optional<Move> found = MateFinder.findMate(Position.fromFen(fen), n);
                assertEquals(expected, found.isPresent(), fen + " mate in " + n);
                found.ifPresent(m -> assertTrue(isWinning(fen, m), fen + " " + m));
            }
        }
    }

    /** The returned move must really force mate: after it, the defender loses (checked without table). */
    private static boolean isWinning(String fen, Move move) {
        Position p = Position.fromFen(fen);
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.generate(p, moves);
        for (int i = 0; i < count; i++) {
            if (!Moves.toMove(moves[i], p.whiteToMove).equals(move)) continue;
            p.make(moves[i]);
            for (int n = 1; n <= 3; n++) if (TranspositionDiagnostic.defender(p, n, 1)) return true;
            return false;
        }
        return false;
    }
}
