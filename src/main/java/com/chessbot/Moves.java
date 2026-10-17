package com.chessbot;

/**
 * Moves packed in an int, so they can be stored in plain int arrays instead of objects.
 * <pre>
 *   bits 0-5   from square (0-63)
 *   bits 6-11  to square (0-63)
 *   bits 12-14 promotion: 0 none, 1 queen, 2 rook, 3 bishop, 4 knight
 * </pre>
 */
final class Moves {
    private static final String PROMOTIONS = " QRBN";

    private Moves() {
    }

    static int encode(int from, int to, int promotion) {
        return from | (to << 6) | (promotion << 12);
    }

    static int from(int move) {
        return move & 63;
    }

    static int to(int move) {
        return (move >>> 6) & 63;
    }

    static int promotion(int move) {
        return move >>> 12;
    }

    static char promotionPiece(int promotion, boolean white) {
        char piece = PROMOTIONS.charAt(promotion);
        return white ? piece : Character.toLowerCase(piece);
    }

    /** Converts to the public {@link Move} record (cold path: UI, results). */
    static Move toMove(int move, boolean white) {
        int from = from(move), to = to(move), promo = promotion(move);
        return new Move(from >> 3, from & 7, to >> 3, to & 7, promo == 0 ? 0 : promotionPiece(promo, white));
    }
}
