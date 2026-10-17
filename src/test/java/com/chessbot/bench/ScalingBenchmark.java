package com.chessbot.bench;

import com.chessbot.MateFinder;
import com.chessbot.Position;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Speed-up of the parallel mate search with the number of worker threads (1 = sequential search). */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class ScalingBenchmark {
    @Param({"1", "2", "4", "8"})
    public int threads;

    private Position kiwipete;

    @Setup
    public void setup() {
        kiwipete = Position.fromFen(ChessBenchmark.KIWIPETE);
    }

    @Benchmark
    @Warmup(iterations = 3)
    @Measurement(iterations = 8)
    public Object mateIn4() {
        return MateFinder.findMate(kiwipete, 4, threads);
    }

    @Benchmark
    @Warmup(iterations = 1)
    @Measurement(iterations = 3)
    public Object mateIn5() {
        return MateFinder.findMate(kiwipete, 5, threads);
    }
}
