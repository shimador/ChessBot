package com.chessbot;

/**
 * A move from one square to another. Rows/cols follow {@link Position}: row 0 = rank 8, col 0 = file a.
 * {@code promotion} is the piece the pawn becomes (case matches the mover's colour), or 0 if none.
 */
public record Move(int fromRow, int fromCol, int toRow, int toCol, char promotion) {

    public Move(int fromRow, int fromCol, int toRow, int toCol) {
        this(fromRow, fromCol, toRow, toCol, (char) 0);
    }

    /** UCI notation, e.g. "e2e4" or "e7e8q". */
    @Override
    public String toString() {
        String s = square(fromRow, fromCol) + square(toRow, toCol);
        return promotion == 0 ? s : s + Character.toLowerCase(promotion);
    }

    static String square(int row, int col) {
        return "" + (char) ('a' + col) + (8 - row);
    }
}
