package com.chessbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MateFinderTest {

    private static String mateIn1(String fen) {
        return MateFinder.findMateInOne(Position.fromFen(fen)).map(Move::toString).orElse("none");
    }

    @Test
    void backRankMate() {
        assertEquals("a1a8", mateIn1("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1"));
    }

    @Test
    void scholarsMate() {
        assertEquals("f3f7", mateIn1("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 2 3"));
    }

    @Test
    void foolsMateWithBlackToMove() {
        assertEquals("d8h4", mateIn1("rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2"));
    }

    @Test
    void mateByPromotion() {
        assertTrue(mateIn1("k7/7P/1K6/8/8/8/8/8 w - - 0 1").startsWith("h7h8"));
    }

    @Test
    void noMateWhenCheckingPieceCanBeCaptured() {
        assertEquals("none", mateIn1("1r4k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1"));
    }

    @Test
    void noMateFromStartPosition() {
        assertEquals("none", mateIn1(Position.START_FEN));
    }

    private static String mateIn(String fen, int n) {
        return MateFinder.findMate(Position.fromFen(fen), n).map(Move::toString).orElse("none");
    }

    @Test
    void rookMateInTwo() {
        // 1. Kf7 Kh7 2. Rh1# (or 1. Kg6 Kg8 2. Ra8#)
        String fen = "7k/8/5K2/8/8/8/8/R7 w - - 0 1";
        assertEquals("none", mateIn(fen, 1));
        assertTrue(Set.of("f6f7", "f6g6").contains(mateIn(fen, 2)));
    }

    @Test
    void smotheredMateInThree() {
        // 1. Nh6+ Kh8 2. Qg8+ Rxg8 3. Nf7#
        String fen = "5rk1/5Npp/8/3Q4/8/8/8/6K1 w - - 0 1";
        assertEquals("none", mateIn(fen, 2));
        assertEquals("f7h6", mateIn(fen, 3));
    }

    @Test
    void stalemateIsNotMate() {
        // Qg6 leaves black with no move but not in check: stalemate. A lone queen cannot mate.
        String fen = "7k/8/8/8/8/8/8/K5Q1 w - - 0 1";
        assertEquals("none", mateIn(fen, 1));
        assertEquals("none", mateIn(fen, 2));
    }
}
