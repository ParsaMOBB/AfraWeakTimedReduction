package ir.ut.ce.awtr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ir.ut.ce.awtr.source.ActionIdentity;
import ir.ut.ce.awtr.source.RawTransition;
import ir.ut.ce.awtr.source.RawTransitionSystem;
import ir.ut.ce.awtr.tts.InvalidModelException;

/**
 * Test-only reader for the small {@code .tts} fixture format.
 *
 * <p>Kept out of {@code src/main} on purpose: the production application's input
 * is an Afra {@code .statespace} export, and nothing about these fixtures should
 * become a supported product format. They exist so hand-checkable systems and
 * the transcribed Case II diagrams can be reviewed as text.
 *
 * <pre>
 * model Name
 * initial S0
 * trans S0 -&gt; S1 : msg owner.title
 * trans S1 -&gt; S2 : time 7
 * trans S2 -&gt; S3 : silent
 * </pre>
 */
public final class TtsFixture {

    private TtsFixture() {
    }

    public static RawTransitionSystem load(String resource) {
        try (InputStream stream = TtsFixture.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalArgumentException("no such fixture: " + resource);
            }
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8), resource);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static RawTransitionSystem parse(String text, String origin) {
        String name = origin;
        String initial = null;
        List<String[]> edges = new ArrayList<>();
        List<String> states = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new java.io.StringReader(text))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("model ")) {
                    name = trimmed.substring(6).trim();
                } else if (trimmed.startsWith("initial ")) {
                    initial = trimmed.substring(8).trim();
                } else if (trimmed.startsWith("state ")) {
                    states.add(trimmed.substring(6).trim());
                } else if (trimmed.startsWith("trans ")) {
                    edges.add(parseTransition(trimmed.substring(6), origin, number));
                } else {
                    throw new InvalidModelException(
                            origin + ":" + number + ": unrecognised line '" + trimmed + "'");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (initial == null) {
            throw new InvalidModelException(origin + ": no 'initial' line");
        }

        // Declare states in first-seen order, initial state first, so the
        // fixture behaves like an Afra export where the initial state is written
        // before anything else.
        RawTransitionSystem.Builder builder = RawTransitionSystem.builder(name);
        List<String> declared = new ArrayList<>();
        declared.add(initial);
        states.forEach(state -> addOnce(declared, state));
        for (String[] edge : edges) {
            addOnce(declared, edge[0]);
            addOnce(declared, edge[2]);
        }
        declared.forEach(state -> builder.state(state, ""));
        builder.initialState(initial);

        for (String[] edge : edges) {
            String source = edge[0];
            String label = edge[1];
            String target = edge[2];
            if (label.startsWith("msg ")) {
                String qualified = label.substring(4).trim();
                int dot = qualified.indexOf('.');
                if (dot <= 0 || dot == qualified.length() - 1) {
                    throw new InvalidModelException(
                            origin + ": message label must be 'owner.title', got '" + qualified + "'");
                }
                builder.transition(RawTransition.message(source, target,
                        new ActionIdentity("", qualified.substring(0, dot),
                                qualified.substring(dot + 1)), 0, 0));
            } else if (label.startsWith("time ")) {
                builder.transition(RawTransition.time(source, target,
                        Integer.parseInt(label.substring(5).trim()), 0, 0));
            } else if (label.equals("silent")) {
                builder.transition(RawTransition.silent(source, target, 0, 0));
            } else {
                throw new InvalidModelException(origin + ": unrecognised label '" + label + "'");
            }
        }
        return builder.build();
    }

    private static String[] parseTransition(String body, String origin, int line) {
        int arrow = body.indexOf("->");
        int colon = body.indexOf(':', arrow < 0 ? 0 : arrow);
        if (arrow < 0 || colon < 0) {
            throw new InvalidModelException(
                    origin + ":" + line + ": expected 'trans A -> B : label'");
        }
        return new String[] {
                body.substring(0, arrow).trim(),
                body.substring(colon + 1).trim(),
                body.substring(arrow + 2, colon).trim()
        };
    }

    private static void addOnce(List<String> target, String value) {
        if (!target.contains(value)) {
            target.add(value);
        }
    }
}
