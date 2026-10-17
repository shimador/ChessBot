package com.chessbot;

/**
 * Everything a search needs, allocated once per thread and reused by every call:
 * a working position and one move buffer per ply. The hot path then allocates nothing.
 */
final class SearchContext {
    private static final ThreadLocal<SearchContext> CURRENT = ThreadLocal.withInitial(SearchContext::new);

    final Position position = new Position();
    final int[][] moves = new int[Position.MAX_PLY][MoveGenerator.MAX_MOVES];
    private MateTable mateTable; // created on first mate search only (perft does not need it)

    MateTable mateTable() {
        if (mateTable == null) mateTable = new MateTable();
        return mateTable;
    }

    /** The calling thread's context, loaded with a copy of {@code start}. */
    static SearchContext load(Position start) {
        SearchContext ctx = CURRENT.get();
        ctx.position.copyFrom(start);
        return ctx;
    }
}
