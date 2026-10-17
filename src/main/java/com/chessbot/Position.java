package com.chessbot;

/**
 * A mutable chess position: moves are played with {@link #make} and taken back with {@link #unmake},
 * on the same flat 64-char board. Nothing is allocated while searching: the information needed to
 * undo a move is kept in preallocated arrays indexed by ply.
 * <p>
 * board[sq(row, col)]: row 0 = rank 8, col 0 = file a.
 * Uppercase = white, lowercase = black, '.' = empty.
 */
public class Position {
    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    static final int MAX_PLY = 64;

    // Castling rights, as bits of the 'castling' field
    static final int WHITE_KINGSIDE = 1, WHITE_QUEENSIDE = 2, BLACK_KINGSIDE = 4, BLACK_QUEENSIDE = 8;

    final char[] board = new char[64];
    boolean whiteToMove;
    int castling;
    int epSquare = -1; // square a pawn may capture onto en passant, -1 if none

    // Undo stack: what make() overwrote, restored by unmake()
    private final char[] undoCaptured = new char[MAX_PLY];
    private final int[] undoCastling = new int[MAX_PLY];
    private final int[] undoEpSquare = new int[MAX_PLY];
    private int ply;

    /** Index of a square in the flat board. */
    static int sq(int row, int col) {
        return row * 8 + col;
    }

    public static Position fromFen(String fen) {
        String[] parts = fen.trim().split("\\s+");
        String[] rows = parts[0].split("/");
        if (rows.length != 8) throw new IllegalArgumentException("Bad FEN: " + fen);

        Position p = new Position();
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
        if (castling.contains("K")) p.castling |= WHITE_KINGSIDE;
        if (castling.contains("Q")) p.castling |= WHITE_QUEENSIDE;
        if (castling.contains("k")) p.castling |= BLACK_KINGSIDE;
        if (castling.contains("q")) p.castling |= BLACK_QUEENSIDE;
        if (parts.length > 3 && !parts[3].equals("-")) {
            int col = parts[3].charAt(0) - 'a';
            int row = 8 - (parts[3].charAt(1) - '0');
            p.epSquare = sq(row, col);
        }
        return p;
    }

    /** Copies another position into this one, without allocating. */
    void copyFrom(Position other) {
        System.arraycopy(other.board, 0, board, 0, 64);
        whiteToMove = other.whiteToMove;
        castling = other.castling;
        epSquare = other.epSquare;
        ply = 0;
    }

    /** Returns an independent copy (for callers that must not see their position modified). */
    public Position copy() {
        Position p = new Position();
        p.copyFrom(this);
        return p;
    }

    /** Plays a move encoded by {@link Moves} on this position. */
    void make(int move) {
        int from = Moves.from(move), to = Moves.to(move), promo = Moves.promotion(move);
        char piece = board[from];
        boolean isPawn = piece == 'P' || piece == 'p';

        undoCaptured[ply] = board[to];
        undoCastling[ply] = castling;
        undoEpSquare[ply] = epSquare;
        ply++;

        // En passant: the captured pawn sits beside the moving pawn, not on the target square
        if (isPawn && to == epSquare && (from & 7) != (to & 7)) {
            board[(from & ~7) | (to & 7)] = '.';
        }

        board[from] = '.';
        board[to] = promo != 0 ? Moves.promotionPiece(promo, whiteToMove) : piece;

        // Castling: the king moves two squares, bring the rook across
        if ((piece == 'K' || piece == 'k') && Math.abs(to - from) == 2) {
            int rowStart = from & ~7;
            if ((to & 7) == 6) {
                board[rowStart + 5] = board[rowStart + 7];
                board[rowStart + 7] = '.';
            } else {
                board[rowStart + 3] = board[rowStart];
                board[rowStart] = '.';
            }
        }

        if (piece == 'K') castling &= ~(WHITE_KINGSIDE | WHITE_QUEENSIDE);
        if (piece == 'k') castling &= ~(BLACK_KINGSIDE | BLACK_QUEENSIDE);
        // A rook leaving its corner, or being captured there
        castling &= ~(cornerRight(from) | cornerRight(to));

        epSquare = isPawn && Math.abs(to - from) == 16 ? (from + to) / 2 : -1;
        whiteToMove = !whiteToMove;
    }

    /** Takes back the last move played with {@link #make}. */
    void unmake(int move) {
        whiteToMove = !whiteToMove;
        ply--;
        int from = Moves.from(move), to = Moves.to(move), promo = Moves.promotion(move);
        char piece = promo != 0 ? (whiteToMove ? 'P' : 'p') : board[to];
        char captured = undoCaptured[ply];

        board[from] = piece;
        board[to] = captured;

        boolean isPawn = piece == 'P' || piece == 'p';
        // A pawn moving diagonally onto an empty square can only be an en passant capture
        if (isPawn && (from & 7) != (to & 7) && captured == '.') {
            board[(from & ~7) | (to & 7)] = whiteToMove ? 'p' : 'P';
        }
        if ((piece == 'K' || piece == 'k') && Math.abs(to - from) == 2) {
            int rowStart = from & ~7;
            if ((to & 7) == 6) {
                board[rowStart + 7] = board[rowStart + 5];
                board[rowStart + 5] = '.';
            } else {
                board[rowStart] = board[rowStart + 3];
                board[rowStart + 3] = '.';
            }
        }

        castling = undoCastling[ply];
        epSquare = undoEpSquare[ply];
    }

    /** True if the side to move is in check. */
    public boolean isInCheck() {
        return MoveGenerator.isKingAttacked(this, whiteToMove);
    }

    private static int cornerRight(int square) {
        return switch (square) {
            case 63 -> WHITE_KINGSIDE;  // h1
            case 56 -> WHITE_QUEENSIDE; // a1
            case 7 -> BLACK_KINGSIDE;   // h8
            case 0 -> BLACK_QUEENSIDE;  // a8
            default -> 0;
        };
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
