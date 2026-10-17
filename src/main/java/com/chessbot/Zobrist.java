package com.chessbot;

import java.util.SplittableRandom;

/**
 * Zobrist hashing: one random 64-bit key per (piece, square), side to move, castling rights and
 * en passant file. A position's hash is the XOR of the keys of what it contains, so a move updates
 * it with a few XORs instead of rehashing the board. Fixed seed: hashes are reproducible.
 */
final class Zobrist {
    private static final long[] PIECES = new long[128 * 64]; // indexed by piece char * 64 + square
    private static final long[] CASTLING = new long[16];
    private static final long[] EN_PASSANT = new long[8];
    static final long SIDE;

    static {
        SplittableRandom random = new SplittableRandom(42);
        for (char piece : "PNBRQKpnbrqk".toCharArray()) {
            for (int s = 0; s < 64; s++) PIECES[piece * 64 + s] = random.nextLong();
        }
        for (int i = 0; i < 16; i++) CASTLING[i] = random.nextLong();
        for (int i = 0; i < 8; i++) EN_PASSANT[i] = random.nextLong();
        SIDE = random.nextLong();
    }

    private Zobrist() {
    }

    static long piece(char piece, int square) {
        return PIECES[piece * 64 + square];
    }

    static long castling(int rights) {
        return CASTLING[rights];
    }

    static long enPassant(int square) {
        return square < 0 ? 0 : EN_PASSANT[square & 7];
    }
}
