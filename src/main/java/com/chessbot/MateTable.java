package com.chessbot;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transposition table for the mate search: remembers, for a position (Zobrist hash) and a number
 * of moves n, whether the side to move can force mate in n. The same position is often reached
 * through different move orders; its result is then read instead of searched again.
 * <p>
 * Fixed size (no growth, no OOM): each hash maps to one slot and a new result simply replaces the
 * old one. A generation counter invalidates every entry in O(1) at the start of each search, so a
 * search never benefits from the previous one (fair benchmarks).
 */
final class MateTable {
    static final int UNKNOWN = -1, NO_MATE = 0, MATE = 1;

    private static final int BITS = 20; // 2^20 slots: 8 MB of keys + 4 MB of data
    private static final int MASK = (1 << BITS) - 1;

    private static final AtomicBoolean NEVER = new AtomicBoolean();

    private final long[] keys = new long[1 << BITS];
    private final int[] data = new int[1 << BITS]; // generation << 8 | n << 1 | mates
    private int generation;
    private long searchId = -1;
    private AtomicBoolean stop = NEVER; // set by the parallel search to stop its workers early

    /** Forgets every stored result, in O(1). */
    void newSearch() {
        generation++;
        searchId = -1;
        stop = NEVER;
    }

    /**
     * Parallel search: a worker runs several root moves of the same search, and keeps its
     * results between them; it only forgets them when a new search starts.
     */
    void joinSearch(long id, AtomicBoolean stopFlag) {
        if (id != searchId) {
            generation++;
            searchId = id;
        }
        stop = stopFlag;
    }

    boolean stopRequested() {
        return stop.get();
    }

    /** MATE or NO_MATE if a stored result answers "mate in n?", UNKNOWN otherwise. */
    int probe(long hash, int n) {
        int slot = (int) hash & MASK;
        int d = data[slot];
        if (keys[slot] != hash || d >>> 8 != generation) return UNKNOWN;
        int storedN = (d >>> 1) & 127;
        boolean mates = (d & 1) != 0;
        // A mate in k is also a mate in any n >= k; no mate in k means no mate in any n <= k
        if (mates && storedN <= n) return MATE;
        if (!mates && storedN >= n) return NO_MATE;
        return UNKNOWN;
    }

    void store(long hash, int n, boolean mates) {
        int slot = (int) hash & MASK;
        keys[slot] = hash;
        data[slot] = generation << 8 | n << 1 | (mates ? 1 : 0);
    }
}
