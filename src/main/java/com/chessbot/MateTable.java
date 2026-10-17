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
 * <p>
 * The parallel search shares one table between all its workers, without any lock. Each slot holds
 * two longs, {@code hash ^ data} and {@code data}: if two threads write the same slot at the same
 * time and a reader sees half of each, the XOR no longer gives back the hash and the entry is
 * simply ignored ("lockless hashing"). A wrong entry can never be read.
 */
final class MateTable {
    static final int UNKNOWN = -1, NO_MATE = 0, MATE = 1;

    private static final int BITS = 20; // 2^20 slots of 16 bytes: 16 MB
    private static final int MASK = (1 << BITS) - 1;
    private static final AtomicBoolean NEVER = new AtomicBoolean();

    private final long[] entries = new long[2 << BITS]; // [2 * slot] = hash ^ data, [2 * slot + 1] = data
    // Written by the thread that starts a search, before any worker task is submitted (happens-before)
    private int generation;
    private AtomicBoolean stop = NEVER;

    /** Forgets every stored result, in O(1). */
    void newSearch() {
        newSearch(NEVER);
    }

    /** Same, for a parallel search whose workers must stop as soon as {@code stopFlag} is set. */
    void newSearch(AtomicBoolean stopFlag) {
        generation++;
        stop = stopFlag;
    }

    boolean stopRequested() {
        return stop.get();
    }

    /** MATE or NO_MATE if a stored result answers "mate in n?", UNKNOWN otherwise. */
    int probe(long hash, int n) {
        int slot = ((int) hash & MASK) << 1;
        long data = entries[slot + 1];
        if ((entries[slot] ^ data) != hash || (int) (data >>> 8) != generation) return UNKNOWN;
        int storedN = (int) (data >>> 1) & 127;
        boolean mates = (data & 1) != 0;
        // A mate in k is also a mate in any n >= k; no mate in k means no mate in any n <= k
        if (mates && storedN <= n) return MATE;
        if (!mates && storedN >= n) return NO_MATE;
        return UNKNOWN;
    }

    void store(long hash, int n, boolean mates) {
        int slot = ((int) hash & MASK) << 1;
        long data = (long) generation << 8 | n << 1 | (mates ? 1 : 0);
        entries[slot + 1] = data;
        entries[slot] = hash ^ data;
    }
}
