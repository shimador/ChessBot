import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/**
 * Converts a JFR recording into "collapsed stacks" (input of flamegraph.pl) and a text summary.
 * <pre>
 *   java tools/JfrToFlame.java profile.jfr out/step
 * </pre>
 * Writes out/step-cpu.collapsed (CPU samples), out/step-alloc.collapsed (sampled allocations,
 * weighted in bytes) and out/step-summary.txt, then prints the summary. Summary lines are
 * tab-separated: kind, percentage, method ("self", "total", "alloc") or "cpuload" (average JVM
 * user CPU load).
 */
public class JfrToFlame {
    public static void main(String[] args) throws IOException {
        List<RecordedEvent> events = RecordingFile.readAllEvents(Path.of(args[0]));
        String out = args[1];
        Map<String, Long> cpuStacks = new HashMap<>(), allocStacks = new HashMap<>();
        Map<String, Long> self = new HashMap<>(), total = new HashMap<>(), alloc = new HashMap<>();
        long samples = 0, allocBytes = 0;
        double cpuLoadSum = 0;
        int cpuLoadCount = 0;

        for (RecordedEvent e : events) {
            String type = e.getEventType().getName();
            if (type.equals("jdk.CPULoad")) {
                cpuLoadSum += e.getFloat("jvmUser");
                cpuLoadCount++;
                continue;
            }
            boolean cpu = type.equals("jdk.ExecutionSample");
            boolean allocation = type.equals("jdk.ObjectAllocationSample");
            if (!(cpu || allocation) || e.getStackTrace() == null) continue;
            List<RecordedFrame> frames = e.getStackTrace().getFrames();
            if (frames.isEmpty()) continue;
            long weight = cpu ? 1 : e.getLong("weight");
            String stack = collapse(frames);
            String top = name(frames.get(0));
            if (cpu) {
                cpuStacks.merge(stack, 1L, Long::sum);
                self.merge(top, 1L, Long::sum);
                Set<String> seen = new HashSet<>();
                for (RecordedFrame f : frames) if (seen.add(name(f))) total.merge(name(f), 1L, Long::sum);
                samples++;
            } else {
                allocStacks.merge(stack, weight, Long::sum);
                alloc.merge(top, weight, Long::sum);
                allocBytes += weight;
            }
        }

        write(Path.of(out + "-cpu.collapsed"), cpuStacks);
        write(Path.of(out + "-alloc.collapsed"), allocStacks);
        StringBuilder summary = new StringBuilder();
        summary.append(String.format(Locale.ROOT, "samples\t%d%n", samples));
        summary.append(String.format(Locale.ROOT, "cpuload\t%.1f%n", cpuLoadCount == 0 ? 0 : 100 * cpuLoadSum / cpuLoadCount));
        top(summary, "self", self, samples);
        top(summary, "total", total, samples);
        top(summary, "alloc", alloc, allocBytes);
        Files.writeString(Path.of(out + "-summary.txt"), summary);
        System.out.print(summary);
    }

    private static String collapse(List<RecordedFrame> frames) {
        StringBuilder sb = new StringBuilder();
        for (int i = frames.size() - 1; i >= 0; i--) {
            if (sb.length() > 0) sb.append(';');
            sb.append(name(frames.get(i)));
        }
        return sb.toString();
    }

    private static void write(Path file, Map<String, Long> stacks) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
            stacks.forEach((stack, count) -> w.print(stack + " " + count + "\n")); // flamegraph.pl expects LF
        }
    }

    private static void top(StringBuilder sb, String kind, Map<String, Long> counts, long sum) {
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(15)
                .forEach(en -> sb.append(String.format(Locale.ROOT, "%s\t%.1f\t%s%n", kind, 100.0 * en.getValue() / sum, en.getKey())));
    }

    private static String name(RecordedFrame f) {
        String type = f.getMethod().getType().getName();
        return type.substring(type.lastIndexOf('.') + 1) + "." + f.getMethod().getName();
    }
}
