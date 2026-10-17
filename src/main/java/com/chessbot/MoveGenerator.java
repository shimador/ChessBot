package com.chessbot;

import java.util.ArrayList;
import java.util.List;

/**
 * Naive legal move generation: generate every pseudo-legal move, play it on a copy of the board,
 * and keep it only if the mover's king is not attacked afterwards.
 */
public final class MoveGenerator {
    private static final int[][] KNIGHT_OFFSETS = {{-2, -1}, {-2, 1}, {-1, -2}, {-1, 2}, {1, -2}, {1, 2}, {2, -1}, {2, 1}};
    private static final int[][] KING_OFFSETS = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}};
    private static final int[][] ROOK_DIRS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final int[][] BISHOP_DIRS = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

    private MoveGenerator() {
    }

    public static List<Move> legalMoves(Position p) {
        List<Move> legal = new ArrayList<>();
        for (Move m : pseudoLegalMoves(p)) {
            Position next = p.makeMove(m);
            if (!isKingAttacked(next, p.whiteToMove)) legal.add(m);
        }
        return legal;
    }

    static List<Move> pseudoLegalMoves(Position p) {
        List<Move> moves = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                char piece = p.board[r][c];
                if (piece == '.' || isWhite(piece) != p.whiteToMove) continue;
                switch (Character.toLowerCase(piece)) {
                    case 'p' -> addPawnMoves(p, r, c, moves);
                    case 'n' -> addStepMoves(p, r, c, KNIGHT_OFFSETS, moves);
                    case 'b' -> addSlideMoves(p, r, c, BISHOP_DIRS, moves);
                    case 'r' -> addSlideMoves(p, r, c, ROOK_DIRS, moves);
                    case 'q' -> {
                        addSlideMoves(p, r, c, ROOK_DIRS, moves);
                        addSlideMoves(p, r, c, BISHOP_DIRS, moves);
                    }
                    case 'k' -> {
                        addStepMoves(p, r, c, KING_OFFSETS, moves);
                        addCastlingMoves(p, r, c, moves);
                    }
                }
            }
        }
        return moves;
    }

    /** True if the king of the given colour is attacked by the other side. */
    static boolean isKingAttacked(Position p, boolean whiteKing) {
        char king = whiteKing ? 'K' : 'k';
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (p.board[r][c] == king) return isSquareAttacked(p, r, c, !whiteKing);
            }
        }
        return false;
    }

    static boolean isSquareAttacked(Position p, int r, int c, boolean byWhite) {
        // A white pawn attacks diagonally upwards, so it sits one row below the square it attacks
        int pawnRow = byWhite ? r + 1 : r - 1;
        char pawn = byWhite ? 'P' : 'p';
        for (int dc : new int[]{-1, 1}) {
            if (onBoard(pawnRow, c + dc) && p.board[pawnRow][c + dc] == pawn) return true;
        }
        if (attackedByStep(p, r, c, KNIGHT_OFFSETS, byWhite ? 'N' : 'n')) return true;
        if (attackedByStep(p, r, c, KING_OFFSETS, byWhite ? 'K' : 'k')) return true;
        char queen = byWhite ? 'Q' : 'q';
        if (attackedBySlide(p, r, c, ROOK_DIRS, byWhite ? 'R' : 'r', queen)) return true;
        return attackedBySlide(p, r, c, BISHOP_DIRS, byWhite ? 'B' : 'b', queen);
    }

    private static void addPawnMoves(Position p, int r, int c, List<Move> moves) {
        boolean white = p.whiteToMove;
        int dir = white ? -1 : 1;
        int startRow = white ? 6 : 1;
        int toRow = r + dir; // always on the board: a pawn never stands on its last rank

        if (p.board[toRow][c] == '.') {
            addPawnMove(r, c, toRow, c, white, moves);
            if (r == startRow && p.board[r + 2 * dir][c] == '.') moves.add(new Move(r, c, r + 2 * dir, c));
        }
        for (int dc : new int[]{-1, 1}) {
            int toCol = c + dc;
            if (toCol < 0 || toCol > 7) continue;
            char target = p.board[toRow][toCol];
            boolean capture = target != '.' && isWhite(target) != white;
            boolean enPassant = toRow == p.epRow && toCol == p.epCol;
            if (capture || enPassant) addPawnMove(r, c, toRow, toCol, white, moves);
        }
    }

    private static void addPawnMove(int r, int c, int toRow, int toCol, boolean white, List<Move> moves) {
        if (toRow == 0 || toRow == 7) {
            for (char promo : (white ? "QRBN" : "qrbn").toCharArray()) moves.add(new Move(r, c, toRow, toCol, promo));
        } else {
            moves.add(new Move(r, c, toRow, toCol));
        }
    }

    private static void addStepMoves(Position p, int r, int c, int[][] offsets, List<Move> moves) {
        for (int[] d : offsets) {
            int tr = r + d[0], tc = c + d[1];
            if (!onBoard(tr, tc)) continue;
            char target = p.board[tr][tc];
            if (target == '.' || isWhite(target) != p.whiteToMove) moves.add(new Move(r, c, tr, tc));
        }
    }

    private static void addSlideMoves(Position p, int r, int c, int[][] dirs, List<Move> moves) {
        for (int[] d : dirs) {
            int tr = r + d[0], tc = c + d[1];
            while (onBoard(tr, tc)) {
                char target = p.board[tr][tc];
                if (target == '.') {
                    moves.add(new Move(r, c, tr, tc));
                } else {
                    if (isWhite(target) != p.whiteToMove) moves.add(new Move(r, c, tr, tc));
                    break;
                }
                tr += d[0];
                tc += d[1];
            }
        }
    }

    private static void addCastlingMoves(Position p, int r, int c, List<Move> moves) {
        boolean white = p.whiteToMove;
        int home = white ? 7 : 0;
        if (r != home || c != 4) return;
        char rook = white ? 'R' : 'r';
        boolean enemy = !white;
        char[] row = p.board[home];

        // The king may not castle out of, through, or into check
        boolean kingside = white ? p.whiteKingside : p.blackKingside;
        if (kingside && row[5] == '.' && row[6] == '.' && row[7] == rook
                && !isSquareAttacked(p, home, 4, enemy)
                && !isSquareAttacked(p, home, 5, enemy)
                && !isSquareAttacked(p, home, 6, enemy)) {
            moves.add(new Move(home, 4, home, 6));
        }
        boolean queenside = white ? p.whiteQueenside : p.blackQueenside;
        if (queenside && row[3] == '.' && row[2] == '.' && row[1] == '.' && row[0] == rook
                && !isSquareAttacked(p, home, 4, enemy)
                && !isSquareAttacked(p, home, 3, enemy)
                && !isSquareAttacked(p, home, 2, enemy)) {
            moves.add(new Move(home, 4, home, 2));
        }
    }

    private static boolean attackedByStep(Position p, int r, int c, int[][] offsets, char attacker) {
        for (int[] d : offsets) {
            int tr = r + d[0], tc = c + d[1];
            if (onBoard(tr, tc) && p.board[tr][tc] == attacker) return true;
        }
        return false;
    }

    private static boolean attackedBySlide(Position p, int r, int c, int[][] dirs, char slider, char queen) {
        for (int[] d : dirs) {
            int tr = r + d[0], tc = c + d[1];
            while (onBoard(tr, tc)) {
                char piece = p.board[tr][tc];
                if (piece != '.') {
                    if (piece == slider || piece == queen) return true;
                    break;
                }
                tr += d[0];
                tc += d[1];
            }
        }
        return false;
    }

    private static boolean onBoard(int r, int c) {
        return r >= 0 && r < 8 && c >= 0 && c < 8;
    }

    private static boolean isWhite(char piece) {
        return Character.isUpperCase(piece);
    }
}
