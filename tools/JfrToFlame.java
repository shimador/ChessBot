import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/**
 * Converts the CPU samples of a JFR recording into "collapsed stacks" (input of flamegraph.pl)
 * and prints the methods with the highest self time and total time.
 * <pre>
 *   java tools/JfrToFlame.java target/profile.jfr target/profile.collapsed
 *   perl flamegraph.pl target/profile.collapsed > docs/flamegraphs/xx.svg
 * </pre>
 */
public class JfrToFlame {
    public static void main(String[] args) throws IOException {
        Path jfr = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Map<String, Long> stacks = new HashMap<>();
        Map<String, Long> self = new HashMap<>();
        Map<String, Long> total = new HashMap<>();
        long samples = 0;

        for (RecordedEvent e : RecordingFile.readAllEvents(jfr)) {
            if (!e.getEventType().getName().equals("jdk.ExecutionSample") || e.getStackTrace() == null) continue;
            List<RecordedFrame> frames = e.getStackTrace().getFrames();
            if (frames.isEmpty()) continue;
            StringBuilder sb = new StringBuilder();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = frames.size() - 1; i >= 0; i--) {
                String name = name(frames.get(i));
                if (sb.length() > 0) sb.append(';');
                sb.append(name);
                if (seen.add(name)) total.merge(name, 1L, Long::sum);
            }
            stacks.merge(sb.toString(), 1L, Long::sum);
            self.merge(name(frames.get(0)), 1L, Long::sum);
            samples++;
        }

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            stacks.forEach((stack, count) -> w.print(stack + " " + count + "\n")); // flamegraph.pl expects LF
        }
        System.out.println("CPU samples: " + samples);
        print("Top self time", self, samples);
        print("Top total time", total, samples);
    }

    private static void print(String title, Map<String, Long> counts, long samples) {
        System.out.println("\n" + title);
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(12)
                .forEach(en -> System.out.printf("  %5.1f%%  %s%n", 100.0 * en.getValue() / samples, en.getKey()));
    }

    private static String name(RecordedFrame f) {
        String type = f.getMethod().getType().getName();
        return type.substring(type.lastIndexOf('.') + 1) + "." + f.getMethod().getName();
    }
}
