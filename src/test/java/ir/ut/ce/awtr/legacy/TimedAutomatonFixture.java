package ir.ut.ce.awtr.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only reader for the transcribed legacy fixtures.
 *
 * <pre>
 * automaton action-at-one
 * clock x
 * initial l0
 * loc l0 inv x &lt;= 2
 * edge l0 -&gt; l0 : a guard x &gt;= 1 reset x
 * </pre>
 */
final class TimedAutomatonFixture {

    private TimedAutomatonFixture() {
    }

    static TimedAutomaton load(String name) {
        String resource = "legacy/" + name + ".ta";
        try (InputStream stream =
                     TimedAutomatonFixture.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalArgumentException("no such fixture: " + resource);
            }
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8), resource);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static TimedAutomaton parse(String text, String origin) {
        TimedAutomaton automaton = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("automaton ")) {
                automaton = new TimedAutomaton(line.substring(10).trim());
            } else if (automaton == null) {
                throw new IllegalStateException(origin + ": expected 'automaton' first");
            } else if (line.startsWith("clock ")) {
                automaton.addClock(line.substring(6).trim());
            } else if (line.startsWith("initial ")) {
                automaton.setInitialLocation(line.substring(8).trim());
            } else if (line.startsWith("loc ")) {
                String body = line.substring(4).trim();
                int inv = body.indexOf(" inv ");
                if (inv < 0) {
                    automaton.addLocation(body, List.of());
                } else {
                    automaton.addLocation(body.substring(0, inv).trim(),
                            constraints(body.substring(inv + 5)));
                }
            } else if (line.startsWith("edge ")) {
                automaton.addEdge(edge(line.substring(5), origin));
            } else {
                throw new IllegalArgumentException(origin + ": unrecognised line '" + line + "'");
            }
        }
        if (automaton == null || automaton.initialLocation() == null) {
            throw new IllegalStateException(origin + ": incomplete automaton");
        }
        return automaton;
    }

    private static TimedAutomaton.Edge edge(String body, String origin) {
        int arrow = body.indexOf("->");
        int colon = body.indexOf(':', arrow);
        if (arrow < 0 || colon < 0) {
            throw new IllegalArgumentException(origin + ": expected 'edge A -> B : action'");
        }
        String source = body.substring(0, arrow).trim();
        String target = body.substring(arrow + 2, colon).trim();
        String tail = body.substring(colon + 1).trim();

        List<TimedAutomaton.Constraint> guard = new ArrayList<>();
        List<String> resets = new ArrayList<>();
        int guardAt = tail.indexOf(" guard ");
        int resetAt = tail.indexOf(" reset ");
        int end = tail.length();
        if (guardAt >= 0) {
            end = Math.min(end, guardAt);
        }
        if (resetAt >= 0) {
            end = Math.min(end, resetAt);
        }
        String action = tail.substring(0, end).trim();
        if (guardAt >= 0) {
            int guardEnd = resetAt > guardAt ? resetAt : tail.length();
            guard.addAll(constraints(tail.substring(guardAt + 7, guardEnd)));
        }
        if (resetAt >= 0) {
            for (String clock : tail.substring(resetAt + 7).trim().split("[,\\s]+")) {
                if (!clock.isBlank()) {
                    resets.add(clock.trim());
                }
            }
        }
        return new TimedAutomaton.Edge(source, target, action, List.copyOf(guard),
                List.copyOf(resets));
    }

    /** Comma-separated atoms of the form {@code clock op constant}. */
    private static List<TimedAutomaton.Constraint> constraints(String text) {
        List<TimedAutomaton.Constraint> result = new ArrayList<>();
        for (String atom : text.split(",")) {
            String[] parts = atom.trim().split("\\s+");
            if (parts.length != 3) {
                throw new IllegalArgumentException("bad constraint '" + atom.trim() + "'");
            }
            result.add(new TimedAutomaton.Constraint(
                    parts[0], parts[1], Integer.parseInt(parts[2])));
        }
        return result;
    }
}
