package com.chessbot.bench;

import com.chessbot.MateFinder;
import com.chessbot.Perft;
import com.chessbot.Position;

/**
 * Reference workload for profiling with JFR (see the "profile" execution in pom.xml).
 * Runs perft 4 and a mate-in-3 search in a loop for a fixed duration, after a short warm-up.
 */
public final class ProfileWorkload {
    private static volatile Object sink;

    public static void main(String[] args) {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        Position start = Position.fromFen(Position.START_FEN);
        Position kiwipete = Position.fromFen(ChessBenchmark.KIWIPETE);

        long end = System.nanoTime() + seconds * 1_000_000_000L;
        long rounds = 0;
        while (System.nanoTime() < end) {
            sink = Perft.perft(start, 4);
            sink = MateFinder.findMate(kiwipete, 3);
            rounds++;
        }
        System.out.println("Rounds: " + rounds);
    }
}
