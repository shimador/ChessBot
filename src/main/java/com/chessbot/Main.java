package com.chessbot;

import java.util.Optional;

public class Main {
    // Scholar's mate set-up: white mates with Qxf7#
    private static final String DEFAULT_FEN = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 2 3";

    /** No arguments: open the window. With a FEN as arguments: solve it in the console. */
    public static void main(String[] args) {
        if (args.length == 0) {
            ChessUI.show(DEFAULT_FEN);
            return;
        }
        String fen = String.join(" ", args);
        Position position = Position.fromFen(fen);
        System.out.println(position);

        long start = System.nanoTime();
        Optional<Move> mate = MateFinder.findMateInOne(position);
        long micros = (System.nanoTime() - start) / 1_000;

        System.out.println(mate.map(m -> "Mate in 1: " + m).orElse("No mate in 1"));
        System.out.println("Search took " + micros + " us");
    }
}
