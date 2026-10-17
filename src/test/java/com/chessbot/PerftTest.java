package com.chessbot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Reference values from https://www.chessprogramming.org/Perft_Results */
class PerftTest {

    @Test
    void startPosition() {
        assertEquals(8_902, Perft.perft(Position.fromFen(Position.START_FEN), 3));
    }

    @Test
    void kiwipeteCastlingAndPins() {
        Position p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        assertEquals(97_862, Perft.perft(p, 3));
    }

    @Test
    void enPassantEndgame() {
        Position p = Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1");
        assertEquals(43_238, Perft.perft(p, 4));
    }

    @Test
    void promotions() {
        Position p = Position.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1");
        assertEquals(9_467, Perft.perft(p, 3));
    }
}
