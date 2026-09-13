package ir.ut.ce.awtr.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ir.ut.ce.awtr.app.ComparisonMethod;
import ir.ut.ce.awtr.app.ReductionRequest;
import ir.ut.ce.awtr.app.ReductionService;
import ir.ut.ce.awtr.source.ObservableSet;
import ir.ut.ce.awtr.tts.TransitionSystem;

/**
 * How the two reduced forms behave as the systems get bigger.
 *
 * <p>Every pair here is equivalent by construction — one side is the other
 * under changes that provably cannot alter weak timed behaviour — so any
 * "not equivalent" answer is a false negative and needs no oracle beyond the
 * construction itself.
 *
 * <p>The saturated form must never produce one. The spliced form may, and the
 * counts printed here are the recorded measurement behind the frequency figures
 * in {@code docs/experiments/quotient-isomorphism.md}: the defect is rare on
 * small systems and becomes visible as they grow, which is exactly why it
 * cannot be ruled out by testing alone.
 */
@DisplayName("reduced forms as systems grow")
class ReducedFormSizeSweepTest {

    private static final ReductionRequest UNIT =
            ReductionRequest.of(ObservableSet.of(List.of("a", "b")));

    @Test
    @DisplayName("the saturated form survives every size; the spliced form does not")
    void sweep() {
        List<String> failures = new ArrayList<>();
        List<String> measurements = new ArrayList<>();

        for (int size : new int[] {5, 8, 12}) {
            Random rng = new Random(99L);
            int pairs = 300;
            int splicedFalseNegatives = 0;
            for (int i = 0; i < pairs; i++) {
                TransitionSystem a = RandomSystems.random(rng, size);
                TransitionSystem b = RandomSystems.equivalentMutant(rng, a);

                if (!ReductionService.compareSystems(a, b, UNIT).bisimilar()) {
                    failures.add("size " + size + " pair " + i
                            + ": a mutant that cannot change behaviour was called inequivalent");
                    continue;
                }
                if (!equivalentBy(a, b, ComparisonMethod.REDUCED_ISOMORPHISM)) {
                    failures.add("size " + size + " pair " + i
                            + ": the saturated quotients of equivalent models differ");
                }
                if (!equivalentBy(a, b, ComparisonMethod.REDUCED_ISOMORPHISM_SPLICED)) {
                    splicedFalseNegatives++;
                }
            }
            measurements.add("states<=" + size + ": " + pairs + " equivalent pairs, "
                    + splicedFalseNegatives + " spliced-form false negatives");
        }

        measurements.forEach(line -> System.out.println("[experiment] " + line));
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(3, measurements.size());
    }

    private static boolean equivalentBy(TransitionSystem a, TransitionSystem b,
                                        ComparisonMethod method) {
        var result = ReductionService.compareSystemsByReducedForm(a, b, UNIT, method);
        assertTrue(result.conclusive(), "the isomorphism search must terminate with an answer");
        return result.equivalent();
    }
}
