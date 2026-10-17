package com.chessbot;

import java.util.ArrayList;
import java.util.List;

/**
 * Move generation into caller-provided int arrays (see {@link Moves}): no object is created.
 * Moves are pseudo-legal; the search keeps a move only if, once played, the mover's king is not attacked.
 */
public final class MoveGenerator {
    /** Upper bound on pseudo-legal moves in a position (the legal maximum is 218). */
    static final int MAX_MOVES = 320;

    private static final int[][] KNIGHT_OFFSETS = {{-2, -1}, {-2, 1}, {-1, -2}, {-1, 2}, {1, -2}, {1, 2}, {2, -1}, {2, 1}};
    private static final int[][] KING_OFFSETS = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}};
    private static final int[][] ROOK_DIRS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final int[][] BISHOP_DIRS = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
    // Was a "new int[]{-1, 1}" literal in each call: JFR showed escape analysis did not remove it
    private static final int[] PAWN_CAPTURE_COLS = {-1, 1};

    private MoveGenerator() {
    }

    /** Legal moves as a list of {@link Move} (cold path: UI and tests). */
    public static List<Move> legalMoves(Position position) {
        Position p = position.copy();
        int[] buffer = new int[MAX_MOVES];
        int count = generate(p, buffer);
        List<Move> legal = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            p.make(buffer[i]);
            if (!isKingAttacked(p, !p.whiteToMove)) legal.add(Moves.toMove(buffer[i], !p.whiteToMove));
            p.unmake(buffer[i]);
        }
        return legal;
    }

    /** Writes the pseudo-legal moves of the side to move into {@code moves}; returns how many. */
    static int generate(Position p, int[] moves) {
        int n = 0;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                char piece = p.board[Position.sq(r, c)];
                if (piece == '.' || isWhite(piece) != p.whiteToMove) continue;
                switch (Character.toLowerCase(piece)) {
                    case 'p' -> n = addPawnMoves(p, r, c, moves, n);
                    case 'n' -> n = addStepMoves(p, r, c, KNIGHT_OFFSETS, moves, n);
                    case 'b' -> n = addSlideMoves(p, r, c, BISHOP_DIRS, moves, n);
                    case 'r' -> n = addSlideMoves(p, r, c, ROOK_DIRS, moves, n);
                    case 'q' -> {
                        n = addSlideMoves(p, r, c, ROOK_DIRS, moves, n);
                        n = addSlideMoves(p, r, c, BISHOP_DIRS, moves, n);
                    }
                    case 'k' -> {
                        n = addStepMoves(p, r, c, KING_OFFSETS, moves, n);
                        n = addCastlingMoves(p, r, c, moves, n);
                    }
                }
            }
        }
        return n;
    }

    /** True if the king of the given colour is attacked by the other side. */
    static boolean isKingAttacked(Position p, boolean whiteKing) {
        char king = whiteKing ? 'K' : 'k';
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (p.board[Position.sq(r, c)] == king) return isSquareAttacked(p, r, c, !whiteKing);
            }
        }
        return false;
    }

    static boolean isSquareAttacked(Position p, int r, int c, boolean byWhite) {
        // A white pawn attacks diagonally upwards, so it sits one row below the square it attacks
        int pawnRow = byWhite ? r + 1 : r - 1;
        char pawn = byWhite ? 'P' : 'p';
        for (int dc : PAWN_CAPTURE_COLS) {
            if (onBoard(pawnRow, c + dc) && p.board[Position.sq(pawnRow, c + dc)] == pawn) return true;
        }
        if (attackedByStep(p, r, c, KNIGHT_OFFSETS, byWhite ? 'N' : 'n')) return true;
        if (attackedByStep(p, r, c, KING_OFFSETS, byWhite ? 'K' : 'k')) return true;
        char queen = byWhite ? 'Q' : 'q';
        if (attackedBySlide(p, r, c, ROOK_DIRS, byWhite ? 'R' : 'r', queen)) return true;
        return attackedBySlide(p, r, c, BISHOP_DIRS, byWhite ? 'B' : 'b', queen);
    }

    private static int addPawnMoves(Position p, int r, int c, int[] moves, int n) {
        boolean white = p.whiteToMove;
        int dir = white ? -1 : 1;
        int startRow = white ? 6 : 1;
        int toRow = r + dir; // always on the board: a pawn never stands on its last rank

        if (p.board[Position.sq(toRow, c)] == '.') {
            n = addPawnMove(r, c, toRow, c, moves, n);
            if (r == startRow && p.board[Position.sq(r + 2 * dir, c)] == '.') {
                moves[n++] = Moves.encode(Position.sq(r, c), Position.sq(r + 2 * dir, c), 0);
            }
        }
        for (int dc : PAWN_CAPTURE_COLS) {
            int toCol = c + dc;
            if (toCol < 0 || toCol > 7) continue;
            char target = p.board[Position.sq(toRow, toCol)];
            boolean capture = target != '.' && isWhite(target) != white;
            boolean enPassant = Position.sq(toRow, toCol) == p.epSquare;
            if (capture || enPassant) n = addPawnMove(r, c, toRow, toCol, moves, n);
        }
        return n;
    }

    private static int addPawnMove(int r, int c, int toRow, int toCol, int[] moves, int n) {
        int from = Position.sq(r, c), to = Position.sq(toRow, toCol);
        if (toRow == 0 || toRow == 7) {
            for (int promo = 1; promo <= 4; promo++) moves[n++] = Moves.encode(from, to, promo);
        } else {
            moves[n++] = Moves.encode(from, to, 0);
        }
        return n;
    }

    private static int addStepMoves(Position p, int r, int c, int[][] offsets, int[] moves, int n) {
        for (int[] d : offsets) {
            int tr = r + d[0], tc = c + d[1];
            if (!onBoard(tr, tc)) continue;
            char target = p.board[Position.sq(tr, tc)];
            if (target == '.' || isWhite(target) != p.whiteToMove) {
                moves[n++] = Moves.encode(Position.sq(r, c), Position.sq(tr, tc), 0);
            }
        }
        return n;
    }

    private static int addSlideMoves(Position p, int r, int c, int[][] dirs, int[] moves, int n) {
        for (int[] d : dirs) {
            int tr = r + d[0], tc = c + d[1];
            while (onBoard(tr, tc)) {
                char target = p.board[Position.sq(tr, tc)];
                if (target == '.') {
                    moves[n++] = Moves.encode(Position.sq(r, c), Position.sq(tr, tc), 0);
                } else {
                    if (isWhite(target) != p.whiteToMove) moves[n++] = Moves.encode(Position.sq(r, c), Position.sq(tr, tc), 0);
                    break;
                }
                tr += d[0];
                tc += d[1];
            }
        }
        return n;
    }

    private static int addCastlingMoves(Position p, int r, int c, int[] moves, int n) {
        boolean white = p.whiteToMove;
        int home = white ? 7 : 0;
        if (r != home || c != 4) return n;
        char rook = white ? 'R' : 'r';
        boolean enemy = !white;
        char[] b = p.board;
        int h = home * 8;

        // The king may not castle out of, through, or into check
        int kingside = white ? Position.WHITE_KINGSIDE : Position.BLACK_KINGSIDE;
        if ((p.castling & kingside) != 0 && b[h + 5] == '.' && b[h + 6] == '.' && b[h + 7] == rook
                && !isSquareAttacked(p, home, 4, enemy)
                && !isSquareAttacked(p, home, 5, enemy)
                && !isSquareAttacked(p, home, 6, enemy)) {
            moves[n++] = Moves.encode(h + 4, h + 6, 0);
        }
        int queenside = white ? Position.WHITE_QUEENSIDE : Position.BLACK_QUEENSIDE;
        if ((p.castling & queenside) != 0 && b[h + 3] == '.' && b[h + 2] == '.' && b[h + 1] == '.' && b[h] == rook
                && !isSquareAttacked(p, home, 4, enemy)
                && !isSquareAttacked(p, home, 3, enemy)
                && !isSquareAttacked(p, home, 2, enemy)) {
            moves[n++] = Moves.encode(h + 4, h + 2, 0);
        }
        return n;
    }

    private static boolean attackedByStep(Position p, int r, int c, int[][] offsets, char attacker) {
        for (int[] d : offsets) {
            int tr = r + d[0], tc = c + d[1];
            if (onBoard(tr, tc) && p.board[Position.sq(tr, tc)] == attacker) return true;
        }
        return false;
    }

    private static boolean attackedBySlide(Position p, int r, int c, int[][] dirs, char slider, char queen) {
        for (int[] d : dirs) {
            int tr = r + d[0], tc = c + d[1];
            while (onBoard(tr, tc)) {
                char piece = p.board[Position.sq(tr, tc)];
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
