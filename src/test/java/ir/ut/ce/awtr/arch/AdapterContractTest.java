package ir.ut.ce.awtr.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ir.ut.ce.awtr.afra.AfraStateSpaceSource;
import ir.ut.ce.awtr.app.ReductionRequest;
import ir.ut.ce.awtr.app.ReductionResult;
import ir.ut.ce.awtr.app.ReductionService;
import ir.ut.ce.awtr.source.InMemoryTransitionSystemSource;
import ir.ut.ce.awtr.source.ObservableSet;
import ir.ut.ce.awtr.source.RawTransitionSystem;
import ir.ut.ce.awtr.source.TransitionSystemSource;
import ir.ut.ce.awtr.tts.Transition;

/**
 * The adapter boundary has to be a real seam, not a comment.
 *
 * <p>These tests show that a source which builds the model in memory — the shape
 * a future in-process Afra adapter would take — and the file-backed
 * {@code .statespace} adapter produce the same domain model and therefore the
 * same reduction. Nothing downstream of {@link TransitionSystemSource} can tell
 * them apart.
 */
@DisplayName("adapter boundary contract")
class AdapterContractTest {

    private static final List<String> OBSERVABLE =
            List.of("GETSENSE", "ACTIVATEH", "SWITCHOFF");

    @TempDir
    Path tempDir;

    private Path exportOnDisk() {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("afra/smarthome-tc2step.statespace")) {
            Path file = tempDir.resolve("model.statespace");
            Files.write(file, stream.readAllBytes());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("an in-memory source and the file adapter yield identical domain models")
    void sourcesAgree() {
        RawTransitionSystem fromFile = new AfraStateSpaceSource(exportOnDisk()).load();
        RawTransitionSystem fromMemory =
                new InMemoryTransitionSystemSource(fromFile).load();

        assertEquals(fromFile.states(), fromMemory.states());
        assertEquals(fromFile.initialState(), fromMemory.initialState());
        assertEquals(fromFile.transitions(), fromMemory.transitions());
        assertEquals(fromFile.actions(), fromMemory.actions());
        assertEquals(fromFile.delays(), fromMemory.delays());
    }

    @Test
    @DisplayName("both sources drive the pipeline to the same reduction")
    void reductionsAgree() {
        ReductionRequest request = ReductionRequest.of(ObservableSet.of(OBSERVABLE));
        Path file = exportOnDisk();

        ReductionResult viaFile =
                ReductionService.reduce(new AfraStateSpaceSource(file), request);
        ReductionResult viaMemory = ReductionService.reduce(
                new InMemoryTransitionSystemSource(new AfraStateSpaceSource(file).load()),
                request);

        assertEquals(viaFile.reducedStateCount(), viaMemory.reducedStateCount());
        assertEquals(viaFile.reducedTransitionCount(), viaMemory.reducedTransitionCount());
        assertEquals(names(viaFile), names(viaMemory));
        assertEquals(edges(viaFile), edges(viaMemory));
        assertTrue(viaFile.verified() && viaMemory.verified());
    }

    @Test
    @DisplayName("a source built by hand needs nothing but the boundary interface")
    void aHandBuiltSourceIsEnough() {
        // This is the whole surface a future in-process Afra adapter has to
        // implement: one method returning a RawTransitionSystem.
        TransitionSystemSource source = () -> RawTransitionSystem.builder("hand-built")
                .state("s0", "")
                .state("s1", "")
                .initialState("s0")
                .transition(ir.ut.ce.awtr.source.RawTransition.message("s0", "s1",
                        new ir.ut.ce.awtr.source.ActionIdentity("a", "b", "GETSENSE"), 0, 0))
                .transition(ir.ut.ce.awtr.source.RawTransition.time("s1", "s0", 4, 0, 0))
                .build();

        ReductionResult result = ReductionService.reduce(
                source, ReductionRequest.of(ObservableSet.of(OBSERVABLE)));
        assertEquals(2, result.originalStateCount());
        assertTrue(result.verified());
    }

    @Test
    @DisplayName("reduction is byte-for-byte reproducible across runs")
    void deterministicOutput() {
        ReductionRequest request = ReductionRequest.of(ObservableSet.of(OBSERVABLE));
        Path file = exportOnDisk();
        var writer = new ir.ut.ce.awtr.report.ArtifactWriter(tempDir);

        var first = writer.renderAll(
                ReductionService.reduce(new AfraStateSpaceSource(file), request),
                "input", "hash", "1.0.0", "commit");
        var second = writer.renderAll(
                ReductionService.reduce(new AfraStateSpaceSource(file), request),
                "input", "hash", "1.0.0", "commit");

        first.forEach((name, content) -> {
            if (!name.equals(ir.ut.ce.awtr.report.ArtifactWriter.METRICS)) {
                assertEquals(content, second.get(name), name + " must be reproducible");
            }
        });
    }

    private static List<String> names(ReductionResult result) {
        return result.quotient().system().states();
    }

    private static List<String> edges(ReductionResult result) {
        return result.quotient().system().transitions().stream()
                .map(Transition::toString).toList();
    }
}
