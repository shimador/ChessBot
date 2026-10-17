package com.chessbot;

/**
 * A chess position: a flat 64-char array (one contiguous object) copied on every move.
 * <p>
 * board[sq(row, col)]: row 0 = rank 8, col 0 = file a.
 * Uppercase = white, lowercase = black, '.' = empty.
 */
public class Position {
    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    final char[] board;
    boolean whiteToMove;
    boolean whiteKingside, whiteQueenside, blackKingside, blackQueenside;
    int epRow = -1, epCol = -1; // square a pawn may capture onto en passant, -1 if none

    private Position(char[] board) {
        this.board = board;
    }

    /** Index of a square in the flat board. */
    static int sq(int row, int col) {
        return row * 8 + col;
    }

    public static Position fromFen(String fen) {
        String[] parts = fen.trim().split("\\s+");
        String[] rows = parts[0].split("/");
        if (rows.length != 8) throw new IllegalArgumentException("Bad FEN: " + fen);

        Position p = new Position(new char[64]);
        for (int r = 0; r < 8; r++) {
            int c = 0;
            for (char ch : rows[r].toCharArray()) {
                int count = Character.isDigit(ch) ? ch - '0' : 1;
                for (int i = 0; i < count; i++) {
                    if (c >= 8) throw new IllegalArgumentException("Bad FEN: " + fen);
                    p.board[sq(r, c++)] = Character.isDigit(ch) ? '.' : ch;
                }
            }
            if (c != 8) throw new IllegalArgumentException("Bad FEN: " + fen);
        }

        p.whiteToMove = parts.length < 2 || parts[1].equals("w");
        String castling = parts.length > 2 ? parts[2] : "-";
        p.whiteKingside = castling.contains("K");
        p.whiteQueenside = castling.contains("Q");
        p.blackKingside = castling.contains("k");
        p.blackQueenside = castling.contains("q");
        if (parts.length > 3 && !parts[3].equals("-")) {
            p.epCol = parts[3].charAt(0) - 'a';
            p.epRow = 8 - (parts[3].charAt(1) - '0');
        }
        return p;
    }

    /** Returns a new position with the move played. This position is left untouched. */
    public Position makeMove(Move m) {
        Position next = copy();
        char piece = board[sq(m.fromRow(), m.fromCol())];
        boolean isPawn = Character.toLowerCase(piece) == 'p';
        boolean isKing = Character.toLowerCase(piece) == 'k';

        // En passant: the captured pawn sits beside the moving pawn, not on the target square
        if (isPawn && m.toRow() == epRow && m.toCol() == epCol && m.fromCol() != m.toCol()) {
            next.board[sq(m.fromRow(), m.toCol())] = '.';
        }

        next.board[sq(m.fromRow(), m.fromCol())] = '.';
        next.board[sq(m.toRow(), m.toCol())] = m.promotion() != 0 ? m.promotion() : piece;

        // Castling: the king moves two squares, bring the rook across
        if (isKing && Math.abs(m.toCol() - m.fromCol()) == 2) {
            int row = m.fromRow();
            if (m.toCol() == 6) {
                next.board[sq(row, 5)] = next.board[sq(row, 7)];
                next.board[sq(row, 7)] = '.';
            } else {
                next.board[sq(row, 3)] = next.board[sq(row, 0)];
                next.board[sq(row, 0)] = '.';
            }
        }

        if (piece == 'K') next.whiteKingside = next.whiteQueenside = false;
        if (piece == 'k') next.blackKingside = next.blackQueenside = false;
        // A rook leaving its corner, or being captured there
        next.clearCastlingRightsFor(m.fromRow(), m.fromCol());
        next.clearCastlingRightsFor(m.toRow(), m.toCol());

        next.epRow = next.epCol = -1;
        if (isPawn && Math.abs(m.toRow() - m.fromRow()) == 2) {
            next.epRow = (m.fromRow() + m.toRow()) / 2;
            next.epCol = m.fromCol();
        }

        next.whiteToMove = !whiteToMove;
        return next;
    }

    /** True if the side to move is in check. */
    public boolean isInCheck() {
        return MoveGenerator.isKingAttacked(this, whiteToMove);
    }

    private void clearCastlingRightsFor(int row, int col) {
        if (row == 7 && col == 7) whiteKingside = false;
        if (row == 7 && col == 0) whiteQueenside = false;
        if (row == 0 && col == 7) blackKingside = false;
        if (row == 0 && col == 0) blackQueenside = false;
    }

    private Position copy() {
        Position p = new Position(board.clone());
        p.whiteToMove = whiteToMove;
        p.whiteKingside = whiteKingside;
        p.whiteQueenside = whiteQueenside;
        p.blackKingside = blackKingside;
        p.blackQueenside = blackQueenside;
        p.epRow = epRow;
        p.epCol = epCol;
        return p;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            sb.append(8 - r).append("  ");
            for (int c = 0; c < 8; c++) sb.append(board[sq(r, c)]).append(' ');
            sb.append('\n');
        }
        sb.append("   a b c d e f g h\n");
        sb.append(whiteToMove ? "White" : "Black").append(" to move\n");
        return sb.toString();
    }
}
