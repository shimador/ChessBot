package com.chessbot;

import java.util.ArrayList;
import java.util.List;

/**
 * Move generation into caller-provided int arrays (see {@link Moves}): no object is created.
 * Moves are pseudo-legal; the search keeps a move only if, once played, the mover's king is not attacked.
 * <p>
 * Everything that depends only on the square is precomputed once (knight and king targets,
 * sliding rays, pawn attacks), so the hot path has no board-edge tests and no row/column arithmetic.
 */
public final class MoveGenerator {
    /** Upper bound on pseudo-legal moves in a position (the legal maximum is 218). */
    static final int MAX_MOVES = 320;

    private static final int WHITE = 0, BLACK = 1;

    /** KNIGHT_TARGETS[sq]: squares a knight on sq reaches. Same idea for the king. */
    private static final int[][] KNIGHT_TARGETS = new int[64][];
    private static final int[][] KING_TARGETS = new int[64][];
    /** ROOK_RAYS[sq][dir]: squares from sq to the edge, nearest first. Same for bishops. */
    private static final int[][][] ROOK_RAYS = new int[64][][];
    private static final int[][][] BISHOP_RAYS = new int[64][][];
    /** PAWN_ATTACKS[colour][sq]: squares a pawn of that colour on sq attacks. */
    private static final int[][][] PAWN_ATTACKS = new int[2][64][];

    static {
        int[][] knight = {{-2, -1}, {-2, 1}, {-1, -2}, {-1, 2}, {1, -2}, {1, 2}, {2, -1}, {2, 1}};
        int[][] king = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}};
        int[][] rookDirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        int[][] bishopDirs = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
        for (int sq = 0; sq < 64; sq++) {
            int r = sq >> 3, c = sq & 7;
            KNIGHT_TARGETS[sq] = steps(r, c, knight);
            KING_TARGETS[sq] = steps(r, c, king);
            PAWN_ATTACKS[WHITE][sq] = steps(r, c, new int[][]{{-1, -1}, {-1, 1}}); // white moves up (row - 1)
            PAWN_ATTACKS[BLACK][sq] = steps(r, c, new int[][]{{1, -1}, {1, 1}});
            ROOK_RAYS[sq] = new int[4][];
            BISHOP_RAYS[sq] = new int[4][];
            for (int d = 0; d < 4; d++) {
                ROOK_RAYS[sq][d] = ray(r, c, rookDirs[d]);
                BISHOP_RAYS[sq][d] = ray(r, c, bishopDirs[d]);
            }
        }
    }

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
        char[] b = p.board;
        boolean white = p.whiteToMove;
        int n = 0;
        for (int from = 0; from < 64; from++) {
            char piece = b[from];
            if (piece == '.' || isWhite(piece) != white) continue;
            switch (piece) {
                case 'P', 'p' -> n = addPawnMoves(p, from, moves, n);
                case 'N', 'n' -> n = addStepMoves(b, white, from, KNIGHT_TARGETS[from], moves, n);
                case 'B', 'b' -> n = addSlideMoves(b, white, from, BISHOP_RAYS[from], moves, n);
                case 'R', 'r' -> n = addSlideMoves(b, white, from, ROOK_RAYS[from], moves, n);
                case 'Q', 'q' -> {
                    n = addSlideMoves(b, white, from, ROOK_RAYS[from], moves, n);
                    n = addSlideMoves(b, white, from, BISHOP_RAYS[from], moves, n);
                }
                case 'K', 'k' -> {
                    n = addStepMoves(b, white, from, KING_TARGETS[from], moves, n);
                    n = addCastlingMoves(p, from, moves, n);
                }
                default -> { }
            }
        }
        return n;
    }

    /** True if the king of the given colour is attacked by the other side. */
    static boolean isKingAttacked(Position p, boolean whiteKing) {
        int king = whiteKing ? p.whiteKing : p.blackKing;
        return king >= 0 && isSquareAttacked(p, king, !whiteKing);
    }

    static boolean isSquareAttacked(Position p, int sq, boolean byWhite) {
        char[] b = p.board;
        // A white pawn attacks sq if it stands where a black pawn on sq would attack
        char pawn = byWhite ? 'P' : 'p';
        for (int s : PAWN_ATTACKS[byWhite ? BLACK : WHITE][sq]) if (b[s] == pawn) return true;
        char knight = byWhite ? 'N' : 'n';
        for (int s : KNIGHT_TARGETS[sq]) if (b[s] == knight) return true;
        char king = byWhite ? 'K' : 'k';
        for (int s : KING_TARGETS[sq]) if (b[s] == king) return true;
        char queen = byWhite ? 'Q' : 'q';
        return attackedAlongRays(b, ROOK_RAYS[sq], byWhite ? 'R' : 'r', queen)
                || attackedAlongRays(b, BISHOP_RAYS[sq], byWhite ? 'B' : 'b', queen);
    }

    private static boolean attackedAlongRays(char[] b, int[][] rays, char slider, char queen) {
        for (int[] ray : rays) {
            for (int s : ray) {
                char piece = b[s];
                if (piece != '.') {
                    if (piece == slider || piece == queen) return true;
                    break;
                }
            }
        }
        return false;
    }

    private static int addPawnMoves(Position p, int from, int[] moves, int n) {
        char[] b = p.board;
        boolean white = p.whiteToMove;
        int to = from + (white ? -8 : 8); // always on the board: a pawn never stands on its last rank

        if (b[to] == '.') {
            n = addPawnMove(from, to, moves, n);
            int startRow = white ? 6 : 1;
            int twoSteps = to + (white ? -8 : 8);
            if ((from >> 3) == startRow && b[twoSteps] == '.') moves[n++] = Moves.encode(from, twoSteps, 0);
        }
        for (int target : PAWN_ATTACKS[white ? WHITE : BLACK][from]) {
            char piece = b[target];
            boolean capture = piece != '.' && isWhite(piece) != white;
            if (capture || target == p.epSquare) n = addPawnMove(from, target, moves, n);
        }
        return n;
    }

    private static int addPawnMove(int from, int to, int[] moves, int n) {
        int row = to >> 3;
        if (row == 0 || row == 7) {
            for (int promo = 1; promo <= 4; promo++) moves[n++] = Moves.encode(from, to, promo);
        } else {
            moves[n++] = Moves.encode(from, to, 0);
        }
        return n;
    }

    private static int addStepMoves(char[] b, boolean white, int from, int[] targets, int[] moves, int n) {
        for (int to : targets) {
            char piece = b[to];
            if (piece == '.' || isWhite(piece) != white) moves[n++] = Moves.encode(from, to, 0);
        }
        return n;
    }

    private static int addSlideMoves(char[] b, boolean white, int from, int[][] rays, int[] moves, int n) {
        for (int[] ray : rays) {
            for (int to : ray) {
                char piece = b[to];
                if (piece == '.') {
                    moves[n++] = Moves.encode(from, to, 0);
                } else {
                    if (isWhite(piece) != white) moves[n++] = Moves.encode(from, to, 0);
                    break;
                }
            }
        }
        return n;
    }

    private static int addCastlingMoves(Position p, int from, int[] moves, int n) {
        boolean white = p.whiteToMove;
        int h = white ? 56 : 0; // first square of the home row
        if (from != h + 4) return n;
        char rook = white ? 'R' : 'r';
        boolean enemy = !white;
        char[] b = p.board;

        // The king may not castle out of, through, or into check
        int kingside = white ? Position.WHITE_KINGSIDE : Position.BLACK_KINGSIDE;
        if ((p.castling & kingside) != 0 && b[h + 5] == '.' && b[h + 6] == '.' && b[h + 7] == rook
                && !isSquareAttacked(p, h + 4, enemy)
                && !isSquareAttacked(p, h + 5, enemy)
                && !isSquareAttacked(p, h + 6, enemy)) {
            moves[n++] = Moves.encode(h + 4, h + 6, 0);
        }
        int queenside = white ? Position.WHITE_QUEENSIDE : Position.BLACK_QUEENSIDE;
        if ((p.castling & queenside) != 0 && b[h + 3] == '.' && b[h + 2] == '.' && b[h + 1] == '.' && b[h] == rook
                && !isSquareAttacked(p, h + 4, enemy)
                && !isSquareAttacked(p, h + 3, enemy)
                && !isSquareAttacked(p, h + 2, enemy)) {
            moves[n++] = Moves.encode(h + 4, h + 2, 0);
        }
        return n;
    }

    private static int[] steps(int r, int c, int[][] offsets) {
        int[] result = new int[offsets.length];
        int count = 0;
        for (int[] d : offsets) {
            int tr = r + d[0], tc = c + d[1];
            if (tr >= 0 && tr < 8 && tc >= 0 && tc < 8) result[count++] = Position.sq(tr, tc);
        }
        return java.util.Arrays.copyOf(result, count);
    }

    private static int[] ray(int r, int c, int[] dir) {
        int[] result = new int[7];
        int count = 0;
        for (int tr = r + dir[0], tc = c + dir[1]; tr >= 0 && tr < 8 && tc >= 0 && tc < 8; tr += dir[0], tc += dir[1]) {
            result[count++] = Position.sq(tr, tc);
        }
        return java.util.Arrays.copyOf(result, count);
    }

    private static boolean isWhite(char piece) {
        return Character.isUpperCase(piece);
    }
}
