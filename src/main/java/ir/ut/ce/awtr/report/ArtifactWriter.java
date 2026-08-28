package ir.ut.ce.awtr.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;

import ir.ut.ce.awtr.app.ReductionResult;
import ir.ut.ce.awtr.tts.Label;
import ir.ut.ce.awtr.tts.Transition;
import ir.ut.ce.awtr.tts.TransitionSystem;

/**
 * Writes the result artefacts.
 *
 * <p>Four files, each with a distinct job:
 *
 * <ul>
 *   <li>{@code partition.json} — which class every input state landed in;</li>
 *   <li>{@code reduced.json} — the reduced model, losslessly. This is the
 *       primary result: it is the only artefact that carries the labels, the
 *       class membership, and the observable set together;</li>
 *   <li>{@code reduced.statespace} — the same model in Afra's own dialect, so
 *       it can be fed straight back to {@code StateSpaceTransformer}. This view
 *       is lossy by construction and says so: a quotient edge may stand for
 *       several original message-server transitions with different senders, and
 *       Afra's schema has one {@code sender} slot;</li>
 *   <li>{@code metrics.json} — the numbers any table in the write-up must cite.</li>
 * </ul>
 */
public final class ArtifactWriter {

    public static final String PARTITION = "partition.json";
    public static final String REDUCED_JSON = "reduced.json";
    public static final String REDUCED_STATESPACE = "reduced.statespace";
    public static final String REDUCED_DOT = "reduced.dot";
    public static final String METRICS = "metrics.json";

    private final Path directory;

    public ArtifactWriter(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** @return the artefacts written, in a stable order */
    public List<Path> writeAll(ReductionResult result, String inputDescription,
                               String inputHash, String toolVersion, String commit) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + directory, e);
        }
        List<Path> written = new ArrayList<>();
        written.add(write(PARTITION, partitionJson(result)));
        written.add(write(REDUCED_JSON, reducedJson(result)));
        written.add(write(REDUCED_STATESPACE, reducedStateSpace(result)));
        written.add(write(REDUCED_DOT, reducedDot(result)));
        written.add(write(METRICS,
                metricsJson(result, inputDescription, inputHash, toolVersion, commit)));
        return List.copyOf(written);
    }

    private Path write(String name, String content) {
        Path target = directory.resolve(name);
        try {
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + target, e);
        }
        return target;
    }

    String partitionJson(ReductionResult result) {
        Json json = new Json().beginObject()
                .member("schema", "awtr.partition/1")
                .member("model", result.acquired().id())
                .strings("observableActions", result.observables())
                .member("timeSemantics", result.timeSemanticsToken())
                .member("declaredStateCount", result.originalStateCount())
                .member("classCount", result.inputPartition().blockCount());

        json.name("classes").beginArray();
        for (int i = 0; i < result.inputPartition().blockCount(); i++) {
            json.beginObject().member("class", i);
            json.strings("states", result.inputPartition().block(i));
            json.endObject();
        }
        json.endArray();

        json.name("stateToClass").beginObject();
        for (String state : result.hidden().states()) {
            json.member(state, result.inputPartition().blockOf(state));
        }
        json.endObject();
        return json.endObject().toString();
    }

    String reducedJson(ReductionResult result) {
        TransitionSystem system = result.quotient().system();
        Json json = new Json().beginObject()
                .member("schema", "awtr.reduced-tts/1")
                .member("model", result.acquired().id())
                .member("timeSemantics", result.timeSemanticsToken())
                .strings("observableActions", result.observables())
                .member("initialState", system.initialState());

        json.name("states").beginArray();
        for (String state : system.states()) {
            SortedSet<String> members = result.quotient().members().get(state);
            json.beginObject().member("id", state);
            json.strings("represents", members == null ? List.of() : members);
            json.endObject();
        }
        json.endArray();

        json.name("transitions").beginArray();
        for (Transition t : system.transitions()) {
            json.beginObject().member("source", t.source());
            writeLabel(json, t.label());
            json.member("target", t.target()).endObject();
        }
        json.endArray();
        return json.endObject().toString();
    }

    private static void writeLabel(Json json, Label label) {
        if (label.isDelay()) {
            json.member("kind", "delay").member("units", label.delayUnits());
        } else if (label.isObservable()) {
            json.member("kind", "action").member("action", label.observableName());
        } else {
            json.member("kind", "tau");
        }
    }

    /**
     * Afra's dialect, as established from {@code transition.xsd} and the RMC
     * emitter. Delay steps use {@code <time value>}; observable steps use
     * {@code <messageserver>} with the quotient's own label as {@code title}.
     * Silent steps have no child element, which the schema's optional choice
     * permits.
     */
    String reducedStateSpace(ReductionResult result) {
        TransitionSystem system = result.quotient().system();
        StringBuilder xml = new StringBuilder();
        xml.append("<transitionsystem>\n");
        for (String state : system.states()) {
            xml.append("<state id=\"").append(escape(state)).append("\" atomicpropositions=\"\">")
                    .append("</state>\n");
        }
        for (Transition t : system.transitions()) {
            xml.append("<transition source=\"").append(escape(t.source()))
                    .append("\" destination=\"").append(escape(t.target()))
                    .append("\" executionTime=\"0\" shift=\"0\">");
            if (t.label().isDelay()) {
                xml.append("<time value=\"").append(t.label().delayUnits()).append("\"/>");
            } else if (t.label().isObservable()) {
                xml.append("<messageserver sender=\"\" owner=\"\" title=\"")
                        .append(escape(t.label().observableName())).append("\"/>");
            }
            xml.append("</transition>\n");
        }
        xml.append("</transitionsystem>\n");
        return xml.toString();
    }

    /** Mirrors the label style Afra's own Graphviz transformer uses. */
    String reducedDot(ReductionResult result) {
        TransitionSystem system = result.quotient().system();
        StringBuilder dot = new StringBuilder("digraph reduced {\n");
        dot.append("  rankdir=TB;\n");
        for (String state : system.states()) {
            boolean initial = state.equals(system.initialState());
            dot.append("  ").append(state).append(" [label=\"").append(state).append('"');
            if (initial) {
                dot.append(", shape=doublecircle");
            }
            dot.append("];\n");
        }
        for (Transition t : system.transitions()) {
            String label = t.label().isDelay()
                    ? "time +=" + t.label().delayUnits()
                    : (t.label().isTau() ? "tau" : t.label().observableName());
            dot.append("  ").append(t.source()).append(" -> ").append(t.target())
                    .append(" [label=\"").append(label).append('"');
            if (t.label().isDelay()) {
                dot.append(", style=bold, color=red");
            }
            dot.append("];\n");
        }
        return dot.append("}\n").toString();
    }

    String metricsJson(ReductionResult result, String inputDescription, String inputHash,
                       String toolVersion, String commit) {
        Json json = new Json().beginObject()
                .member("schema", "awtr.metrics/1")
                .member("tool", "awtr")
                .member("toolVersion", toolVersion)
                .member("commit", commit)
                .member("input", inputDescription)
                .member("inputSha256", inputHash)
                .member("model", result.acquired().id())
                .member("timeSemantics", result.timeSemanticsToken())
                .strings("observableActions", result.observables())
                .strings("observableActionsWithoutMatch", result.unmatchedObservables())
                .member("originalStates", result.originalStateCount())
                .member("originalTransitions", result.originalTransitionCount())
                .member("refinedStates", result.refined().stateCount())
                .member("refinedTransitions", result.refined().transitionCount())
                .member("classCount", result.partition().blockCount())
                .member("declaredStateClassCount", result.inputPartition().blockCount())
                .member("reducedStates", result.reducedStateCount())
                .member("reducedTransitions", result.reducedTransitionCount());
        json.name("stateReductionRatio").ratio(result.stateReductionRatio());
        json.name("transitionReductionRatio").ratio(result.transitionReductionRatio());
        json.member("refinementRounds", result.refinementRounds())
                .member("weakTransitions", result.weakTransitionCount())
                .member("splicedChains", result.quotient().splicedChains())
                .member("elapsedMillis", result.elapsedNanos() / 1_000_000L);
        if (result.verification() != null) {
            json.name("verification").beginObject()
                    .member("valid", result.verification().valid())
                    .member("relatedPairs", result.verification().relatedPairs())
                    .member("transferChecks", result.verification().checkedMoves())
                    .strings("violations", result.verification().violations())
                    .endObject();
        }
        return json.endObject().toString();
    }

    public static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot hash " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static String escape(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Exposed for tests that want the content without touching the file system. */
    public Map<String, String> renderAll(ReductionResult result, String inputDescription,
                                         String inputHash, String toolVersion, String commit) {
        return Map.of(
                PARTITION, partitionJson(result),
                REDUCED_JSON, reducedJson(result),
                REDUCED_STATESPACE, reducedStateSpace(result),
                REDUCED_DOT, reducedDot(result),
                METRICS, metricsJson(result, inputDescription, inputHash, toolVersion, commit));
    }
}
