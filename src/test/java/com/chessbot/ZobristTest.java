package com.chessbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ZobristTest {

    /** Walks the move tree and checks that the incremental hash always equals a full recomputation. */
    private static void walk(Position p, int depth) {
        assertEquals(p.computeHash(), p.hash);
        if (depth == 0) return;
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.generate(p, moves);
        for (int i = 0; i < count; i++) {
            long before = p.hash;
            p.make(moves[i]);
            if (!MoveGenerator.isKingAttacked(p, !p.whiteToMove)) walk(p, depth - 1);
            p.unmake(moves[i]);
            assertEquals(before, p.hash);
        }
    }

    @Test
    void incrementalHashMatchesFullHashOnTrickyPositions() {
        walk(Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"), 3);
        walk(Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"), 4);
        walk(Position.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1"), 3);
    }

    @Test
    void sameBoardDifferentSideHasDifferentHash() {
        assertNotEquals(Position.fromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1").hash,
                Position.fromFen("4k3/8/8/8/8/8/8/4K3 b - - 0 1").hash);
    }
}
