package com.chessbot.bench;

import com.chessbot.MateFinder;
import com.chessbot.Perft;
import com.chessbot.Position;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Reference workloads of the audit. The same workloads are measured at every optimisation step.
 * <ul>
 *   <li>{@code perft4}: move generation only, 197 281 positions from the start position.</li>
 *   <li>{@code perft3Latency}: sampled latency distribution (p50 / p99) of a perft 3 call.</li>
 *   <li>{@code mateIn3}: full mate-in-3 search on Kiwipete (no mate exists, so nothing is pruned early).</li>
 *   <li>{@code mateIn4}: same search one move deeper, measured as single shots because it is long.</li>
 * </ul>
 */
@State(Scope.Benchmark)
@Fork(value = 3, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class ChessBenchmark {
    static final String KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";

    private Position start;
    private Position kiwipete;

    @Setup
    public void setup() {
        start = Position.fromFen(Position.START_FEN);
        kiwipete = Position.fromFen(KIWIPETE);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public long perft4() {
        return Perft.perft(start, 4);
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Fork(2)
    public long perft3Latency() {
        return Perft.perft(start, 3);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Object mateIn3() {
        return MateFinder.findMate(kiwipete, 3);
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 3)
    public Object mateIn4() {
        return MateFinder.findMate(kiwipete, 4);
    }
}
