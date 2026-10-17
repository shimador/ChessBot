package com.chessbot.bench;

import com.chessbot.MateFinder;
import com.chessbot.Position;
import java.lang.management.ManagementFactory;

/**
 * Explains the parallel speed-up: for each thread count, measures wall time and the CPU time used
 * by the whole process during one mate search.
 * <ul>
 *   <li>CPU / wall = how many cores were really busy on average.</li>
 *   <li>CPU / CPU with 1 thread = how much the total work grew (work done twice by workers that share nothing).</li>
 * </ul>
 */
public final class ParallelDiagnostic {
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        Position kiwipete = Position.fromFen(ChessBenchmark.KIWIPETE);
        for (int t : new int[]{1, 2, 4, 8}) MateFinder.findMate(kiwipete, n - 1, t); // JIT warm-up

        double cpuOneThread = 0;
        System.out.printf("Mate in %d on Kiwipete%n%8s %10s %10s %12s %14s%n", n, "threads", "wall (s)", "CPU (s)", "busy cores", "work vs 1 thr");
        for (int t : new int[]{1, 2, 4, 8}) {
            long cpu0 = os.getProcessCpuTime(), wall0 = System.nanoTime();
            MateFinder.findMate(kiwipete, n, t);
            double wall = (System.nanoTime() - wall0) / 1e9, cpu = (os.getProcessCpuTime() - cpu0) / 1e9;
            if (t == 1) cpuOneThread = cpu;
            System.out.printf("%8d %10.2f %10.2f %12.2f %13.2fx%n", t, wall, cpu, cpu / wall, cpu / cpuOneThread);
        }
    }
}
