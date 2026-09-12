package ir.ut.ce.awtr.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ir.ut.ce.awtr.TtsFixture;
import ir.ut.ce.awtr.app.ComparisonMethod;
import ir.ut.ce.awtr.app.ReductionRequest;
import ir.ut.ce.awtr.app.ReductionService;
import ir.ut.ce.awtr.source.Hiding;
import ir.ut.ce.awtr.source.ObservableSet;
import ir.ut.ce.awtr.tts.Label;
import ir.ut.ce.awtr.tts.TransitionSystem;
import ir.ut.ce.awtr.weak.TimeSemantics;

/**
 * The reduce-then-compare experiment.
 *
 * <p>Question: instead of refining the disjoint union of two models, can we
 * reduce each model on its own and compare the two reduced models as graphs?
 * That is sound exactly when the reduced form is canonical - when weak timed
 * bisimilar models always reduce to the same graph.
 *
 * <p>The answer depends on which reduced form is compared, and both are checked
 * here against the union method as the reference answer. See
 * {@code docs/experiments/quotient-isomorphism.md}.
 */
@DisplayName("reduce-then-compare experiment")
class ReducedFormComparisonTest {

    private static final ReductionRequest UNIT =
            ReductionRequest.of(ObservableSet.of(List.of("a", "b")));
    private static final ReductionRequest STRICT = UNIT.withTimeSemantics(TimeSemantics.STRICT_EDGE);

    private static boolean union(TransitionSystem a, TransitionSystem b, ReductionRequest request) {
        return ReductionService.compareSystems(a, b, request).bisimilar();
    }

    private static boolean iso(TransitionSystem a, TransitionSystem b, ReductionRequest request,
                               ComparisonMethod method) {
        var result = ReductionService.compareSystemsByReducedForm(a, b, request, method);
        assertTrue(result.conclusive(), "the isomorphism search must terminate with an answer");
        // A reduced model is rigid, so a positive answer must be forced rather
        // than found by trial and error. This is the claim that makes the
        // isomorphism test cheap on exactly these inputs.
        assertTrue(!result.equivalent() || result.isomorphism().forced(),
                "the isomorphism between two reduced models had to backtrack");
        return result.equivalent();
    }

    @Test
    @DisplayName("randomised differential: the saturated quotient decides what the union decides")
    void randomisedDifferential() {
        Random rng = new Random(20260913L);
        int pairs = 400;
        Tally unit = new Tally("unit");
        Tally strict = new Tally("strict");
        Tally shortcut = new Tally("spliced+strict vs unit reference");
        List<String> failures = new ArrayList<>();
        List<String> shortcutExamples = new ArrayList<>();

        for (int i = 0; i < pairs; i++) {
            TransitionSystem a = RandomSystems.random(rng, 5);
            // Half the pairs are equivalent by construction, so the experiment
            // sees both answers rather than trivially agreeing on "no".
            boolean mutant = i % 2 == 0;
            TransitionSystem b = mutant
                    ? RandomSystems.equivalentMutant(rng, a)
                    : RandomSystems.random(rng, 5);

            boolean unitReference = union(a, b, UNIT);
            boolean strictReference = union(a, b, STRICT);
            if (mutant && !unitReference) {
                failures.add("pair " + i + ": a mutant that cannot change behaviour was called"
                        + " inequivalent\n  left  = " + describe(a) + "\n  right = " + describe(b));
            }

            unit.record(unitReference,
                    iso(a, b, UNIT, ComparisonMethod.REDUCED_ISOMORPHISM),
                    iso(a, b, UNIT, ComparisonMethod.REDUCED_ISOMORPHISM_SPLICED));
            strict.record(strictReference,
                    iso(a, b, STRICT, ComparisonMethod.REDUCED_ISOMORPHISM),
                    iso(a, b, STRICT, ComparisonMethod.REDUCED_ISOMORPHISM_SPLICED));

            // The proposal under test: skip the unit-delay split, reduce each
            // side under the strict reading, and compare the quotients the
            // reduce command writes. Scored against the unit-additive answer,
            // which is the semantics the project actually claims.
            boolean splicedStrict = iso(a, b, STRICT, ComparisonMethod.REDUCED_ISOMORPHISM_SPLICED);
            shortcut.record(unitReference, splicedStrict, splicedStrict);
            if (unitReference != splicedStrict && shortcutExamples.size() < 3) {
                shortcutExamples.add("  union(unit)=" + unitReference + " spliced+strict="
                        + splicedStrict + "\n    left  = " + describe(a)
                        + "\n    right = " + describe(b));
            }

            if (unit.saturatedDisagreements() + strict.saturatedDisagreements() > 0
                    && failures.isEmpty()) {
                failures.add("pair " + i + ": the saturated quotient disagreed with the union"
                        + "\n  left  = " + describe(a) + "\n  right = " + describe(b));
            }
        }

        System.out.println("[experiment] randomised differential over " + pairs + " pairs");
        System.out.println("[experiment] " + unit);
        System.out.println("[experiment] " + strict);
        System.out.println("[experiment] " + shortcut);
        shortcutExamples.forEach(example ->
                System.out.println("[experiment] shortcut disagreement:\n" + example));

        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(0, unit.saturatedDisagreements(),
                "the saturated quotient must decide exactly what the union decides");
        assertEquals(0, strict.saturatedDisagreements(),
                "the saturated quotient must decide exactly what the union decides");
        assertEquals(0, unit.splicedFalsePositives(),
                "isomorphic quotients of equivalent-by-construction models cannot be a false yes");
        assertTrue(unit.positives() >= 100,
                "the sample must contain plenty of equivalent pairs, got " + unit.positives());
        assertTrue(unit.positives() < pairs,
                "the sample must contain inequivalent pairs too");
    }

    /** How one method scored against the union answer over a run of pairs. */
    private static final class Tally {
        private final String name;
        private int positives;
        private int saturatedFalseNegatives;
        private int saturatedFalsePositives;
        private int splicedFalseNegatives;
        private int splicedFalsePositives;

        Tally(String name) {
            this.name = name;
        }

        void record(boolean reference, boolean saturated, boolean spliced) {
            if (reference) {
                positives++;
                if (!saturated) {
                    saturatedFalseNegatives++;
                }
                if (!spliced) {
                    splicedFalseNegatives++;
                }
            } else {
                if (saturated) {
                    saturatedFalsePositives++;
                }
                if (spliced) {
                    splicedFalsePositives++;
                }
            }
        }

        int positives() {
            return positives;
        }

        int saturatedDisagreements() {
            return saturatedFalseNegatives + saturatedFalsePositives;
        }

        int splicedFalsePositives() {
            return splicedFalsePositives;
        }

        @Override
        public String toString() {
            return String.format("%-34s reference-yes=%3d  saturated wrong=%d"
                            + "  spliced false-no=%d  spliced false-yes=%d",
                    name, positives, saturatedDisagreements(),
                    splicedFalseNegatives, splicedFalsePositives);
        }
    }

    @Test
    @DisplayName("the spliced quotient the reduce command writes is not canonical")
    void splicedQuotientIsNotCanonical() {
        // Two systems that differ only in a silent step the tau closure already
        // provides: the left one spells out s0 --tau--> s2, the right one gets
        // there through s1. Nothing about the behaviour differs.
        TransitionSystem withShortcut = TransitionSystem.builder("with-shortcut")
                .initialState("s0").state("s1").state("s2").state("e")
                .transition("s0", Label.TAU, "s1")
                .transition("s1", Label.TAU, "s2")
                .transition("s0", Label.TAU, "s2")
                .transition("s0", Label.observable("a"), "e")
                .transition("s1", Label.observable("b"), "e")
                .transition("s2", Label.delay(1), "e")
                .build();
        TransitionSystem withoutShortcut = TransitionSystem.builder("without-shortcut")
                .initialState("t0").state("t1").state("t2").state("f")
                .transition("t0", Label.TAU, "t1")
                .transition("t1", Label.TAU, "t2")
                .transition("t0", Label.observable("a"), "f")
                .transition("t1", Label.observable("b"), "f")
                .transition("t2", Label.delay(1), "f")
                .build();

        assertTrue(union(withShortcut, withoutShortcut, UNIT),
                "the two models are weak timed bisimilar");
        assertTrue(iso(withShortcut, withoutShortcut, UNIT, ComparisonMethod.REDUCED_ISOMORPHISM),
                "the saturated quotient must not care which silent steps are spelled out");
        assertFalse(iso(withShortcut, withoutShortcut, UNIT,
                        ComparisonMethod.REDUCED_ISOMORPHISM_SPLICED),
                "the spliced quotient keeps the redundant silent step and so differs");
    }

    @Test
    @DisplayName("strict time changes the question, so it is not a way to skip the unit split")
    void strictTimeIsNotAShortcut() {
        // "time passes, one unit at a time" against "time passes, two units at a
        // time". Under the run-based reading both simply let any amount of time
        // pass with nothing observable, so they are equivalent; under the strict
        // reading the left one has a one-unit step that the right one has not.
        TransitionSystem tickOne = TransitionSystem.builder("tick-1")
                .initialState("s0")
                .transition("s0", Label.delay(1), "s0")
                .build();
        TransitionSystem tickTwo = TransitionSystem.builder("tick-2")
                .initialState("t0")
                .transition("t0", Label.delay(2), "t0")
                .build();

        assertTrue(union(tickOne, tickTwo, UNIT), "equivalent under the run-based reading");
        assertFalse(union(tickOne, tickTwo, STRICT), "not equivalent under the strict reading");

        // Every reduced-form method inherits that, because it is a property of
        // the semantics and not of how the two models are compared.
        assertTrue(iso(tickOne, tickTwo, UNIT, ComparisonMethod.REDUCED_ISOMORPHISM));
        assertFalse(iso(tickOne, tickTwo, STRICT, ComparisonMethod.REDUCED_ISOMORPHISM));
        assertFalse(iso(tickOne, tickTwo, STRICT, ComparisonMethod.REDUCED_ISOMORPHISM_SPLICED));
    }

    @Test
    @DisplayName("the Case II oracle itself is lost under strict time, whichever method is used")
    void caseIIOracleNeedsTheRunBasedReading() {
        ObservableSet observable =
                ObservableSet.of(List.of("getSense", "activateh", "switchoff"));
        ReductionRequest unit = ReductionRequest.of(observable);
        ReductionRequest strict = unit.withTimeSemantics(TimeSemantics.STRICT_EDGE);
        TransitionSystem smartHome = hidden("caseii/smarthome.tts", observable);
        TransitionSystem tc2step = hidden("caseii/smarthome-tc2step.tts", observable);

        assertTrue(union(smartHome, tc2step, unit),
                "the owner declares these two weak timed bisimilar");
        assertTrue(iso(smartHome, tc2step, unit, ComparisonMethod.REDUCED_ISOMORPHISM),
                "the saturated quotients agree with the oracle");
        assertFalse(union(smartHome, tc2step, strict),
                "tc2step takes two time steps where SmartHome takes one, so strict time"
                        + " separates them - the oracle only holds for the run-based reading");
        assertFalse(iso(smartHome, tc2step, strict, ComparisonMethod.REDUCED_ISOMORPHISM),
                "and the reduced-form method inherits exactly that");
    }

    private static TransitionSystem hidden(String fixture, ObservableSet observable) {
        return Hiding.apply(TtsFixture.load(fixture), observable).reachableFragment();
    }

    @Test
    @DisplayName("comparing the spliced quotient never reports a false positive")
    void splicedQuotientIsSoundUnderUnitSemantics() {
        Random rng = new Random(4242L);
        for (int i = 0; i < 200; i++) {
            TransitionSystem a = RandomSystems.random(rng, 5);
            TransitionSystem b = i % 2 == 0
                    ? RandomSystems.equivalentMutant(rng, a)
                    : RandomSystems.random(rng, 5);
            if (iso(a, b, UNIT, ComparisonMethod.REDUCED_ISOMORPHISM_SPLICED)) {
                assertTrue(union(a, b, UNIT),
                        "isomorphic spliced quotients but not bisimilar:\n  left  = "
                                + describe(a) + "\n  right = " + describe(b));
            }
        }
    }

    @Test
    @DisplayName("the compared spliced form is the artefact the reduce command writes")
    void splicedFormDoesNotDrift() {
        Random rng = new Random(7L);
        for (int i = 0; i < 50; i++) {
            TransitionSystem system = RandomSystems.random(rng, 5);
            TransitionSystem viaExperiment = ReductionService.reducedForm(
                    system, UNIT, ComparisonMethod.REDUCED_ISOMORPHISM_SPLICED);
            TransitionSystem viaReduce = ReductionService.reduce(
                    () -> InMemoryModels.raw(system), UNIT.withVerification(false))
                    .quotient().system();
            assertEquals(viaReduce.states(), viaExperiment.states());
            assertEquals(viaReduce.transitions(), viaExperiment.transitions());
        }
    }

    private static String describe(TransitionSystem system) {
        StringBuilder text = new StringBuilder(system.initialState() + " | ");
        system.transitions().forEach(t -> text.append(t).append("  "));
        return text.toString();
    }
}
