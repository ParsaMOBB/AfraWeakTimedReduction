package ir.ut.ce.awtr.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import ir.ut.ce.awtr.app.ReductionRequest;
import ir.ut.ce.awtr.app.ReductionService;
import ir.ut.ce.awtr.source.Hiding;
import ir.ut.ce.awtr.source.ObservableSet;
import ir.ut.ce.awtr.source.RawTransitionSystem;
import ir.ut.ce.awtr.tts.TransitionSystem;

/**
 * The ten recorded weak-timed acceptance cases, reproduced case by case.
 *
 * <p>These are semantic regression data, not a supported input format. Each
 * fixture is a discrete-time timed automaton; a test-only unfolder turns it into
 * the explicit TTS the algorithm consumes, and the expected boolean is the one
 * recorded in the legacy manifest. No expected result was adjusted to suit this
 * implementation.
 */
@DisplayName("legacy weak-timed acceptance baseline")
class LegacyBaselineTest {

    /** Every action the fixtures use apart from the silent ones. */
    private static final List<String> OBSERVABLE = List.of("a", "b");

    record Case(String id, String left, String right, boolean expected) {
        @Override
        public String toString() {
            return id;
        }
    }

    static List<Case> cases() {
        String text;
        try (InputStream stream = LegacyBaselineTest.class.getClassLoader()
                .getResourceAsStream("legacy/manifest.txt")) {
            if (stream == null) {
                throw new IllegalStateException("legacy/manifest.txt is missing");
            }
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Case> cases = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\|");
            cases.add(new Case(parts[0].trim(), parts[1].trim(), parts[2].trim(),
                    Boolean.parseBoolean(parts[3].trim())));
        }
        return cases;
    }

    static Stream<Case> manifest() {
        return cases().stream();
    }

    @Test
    @DisplayName("the manifest still has exactly the ten recorded cases")
    void manifestIsComplete() {
        List<Case> cases = cases();
        assertEquals(10, cases.size(), "the recorded baseline has ten cases");
        Set<String> ids = new LinkedHashSet<>();
        cases.forEach(c -> assertTrue(ids.add(c.id()), "duplicate case id " + c.id()));
        assertEquals(5, cases.stream().filter(c -> !c.expected()).count(),
                "five of the recorded cases are negative");
        assertEquals(5, cases.stream().filter(Case::expected).count(),
                "five of the recorded cases are positive");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("manifest")
    @DisplayName("reproduces the recorded result")
    void reproducesRecordedResult(Case testCase) {
        TransitionSystem left = hidden(testCase.left());
        TransitionSystem right = hidden(testCase.right());
        ReductionRequest request = ReductionRequest.of(ObservableSet.of(OBSERVABLE));

        boolean actual = ReductionService.compareSystems(left, right, request).bisimilar();
        assertEquals(testCase.expected(), actual,
                testCase.id() + ": " + testCase.left() + " vs " + testCase.right());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("manifest")
    @DisplayName("each side reduces to something still equivalent to itself")
    void reductionPreservesEachSide(Case testCase) {
        ReductionRequest request = ReductionRequest.of(ObservableSet.of(OBSERVABLE));
        for (String model : List.of(testCase.left(), testCase.right())) {
            var result = ReductionService.reduce(
                    () -> TimedAutomatonUnfolder.unfold(TimedAutomatonFixture.load(model)),
                    request);
            assertTrue(result.verified(),
                    model + ": independent verifier rejected the quotient - "
                            + (result.verification() == null ? "" : result.verification().summary()));
            assertTrue(ReductionService.compareSystems(
                            result.hidden(), result.quotient().system(), request).bisimilar(),
                    model + ": quotient is not equivalent to its source");
        }
    }

    private static TransitionSystem hidden(String fixture) {
        RawTransitionSystem raw =
                TimedAutomatonUnfolder.unfold(TimedAutomatonFixture.load(fixture));
        return Hiding.apply(raw, ObservableSet.of(OBSERVABLE)).reachableFragment();
    }
}
