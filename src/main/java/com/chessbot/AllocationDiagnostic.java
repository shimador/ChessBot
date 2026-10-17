package com.chessbot;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.function.Supplier;

/**
 * Escape analysis diagnostic: measures how many bytes each hot operation allocates on the heap.
 * <p>
 * HotSpot's C2 compiler performs escape analysis at runtime: an object that never leaves the
 * compiled method (after inlining) is "scalar replaced" and never touches the heap. Compare runs:
 * <pre>
 *   java -cp target/classes com.chessbot.AllocationDiagnostic                          # EA on (default)
 *   java -XX:-DoEscapeAnalysis -cp target/classes com.chessbot.AllocationDiagnostic    # EA off
 * </pre>
 * Bytes that disappear with EA on were eliminated by the JIT; bytes that remain escape to the heap.
 * To see which classes and call sites allocate, record with JFR:
 * <pre>
 *   java -XX:StartFlightRecording=filename=alloc.jfr,settings=profile -cp target/classes com.chessbot.AllocationDiagnostic
 *   jfr view allocation-by-class alloc.jfr
 *   jfr view allocation-by-site alloc.jfr
 * </pre>
 */
public final class AllocationDiagnostic {
    private static final String KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    // Results are stored here so the JIT cannot treat the measured work as dead code
    private static volatile Object sink;

    private AllocationDiagnostic() {
    }

    public static void main(String[] args) {
        Position start = Position.fromFen(Position.START_FEN);
        Position kiwipete = Position.fromFen(KIWIPETE);
        Move e2e4 = new Move(6, 4, 4, 4);

        System.out.println("Escape analysis: " + vmOption("DoEscapeAnalysis"));
        System.out.printf("%-28s %12s %14s%n", "operation", "ns/op", "bytes/op");
        report("makeMove", () -> start.makeMove(e2e4));
        report("isInCheck", kiwipete::isInCheck);
        report("legalMoves (kiwipete)", () -> MoveGenerator.legalMoves(kiwipete));
        report("findMateInOne (kiwipete)", () -> MateFinder.findMateInOne(kiwipete));
        report("perft 3 (start)", () -> Perft.perft(start, 3));
    }

    private static void report(String name, Supplier<Object> op) {
        // Warm-up: give C2 time to compile the hot paths, which is when escape analysis happens
        long warmUpEnd = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < warmUpEnd) sink = op.get();

        long iterations = 0;
        long bytesBefore = THREADS.getCurrentThreadAllocatedBytes();
        long t0 = System.nanoTime();
        long end = t0 + 1_000_000_000L;
        do {
            sink = op.get();
            iterations++;
        } while (System.nanoTime() < end);
        long elapsed = System.nanoTime() - t0;
        long bytes = THREADS.getCurrentThreadAllocatedBytes() - bytesBefore;

        System.out.printf("%-28s %,12.0f %,14.0f%n", name, (double) elapsed / iterations, (double) bytes / iterations);
    }

    private static String vmOption(String name) {
        return ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).getVMOption(name).getValue();
    }
}
