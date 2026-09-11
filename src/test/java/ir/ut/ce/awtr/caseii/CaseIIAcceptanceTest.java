package ir.ut.ce.awtr.caseii;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import ir.ut.ce.awtr.TtsFixture;
import ir.ut.ce.awtr.app.ReductionRequest;
import ir.ut.ce.awtr.app.ReductionService;
import ir.ut.ce.awtr.source.Hiding;
import ir.ut.ce.awtr.source.ObservableSet;
import ir.ut.ce.awtr.source.RawTransition;
import ir.ut.ce.awtr.source.RawTransitionSystem;
import ir.ut.ce.awtr.tts.TransitionSystem;
import ir.ut.ce.awtr.weak.TimeSemantics;

/**
 * The owner-provided Case II oracle.
 *
 * <p>The project contract declares the three systems drawn in
 * {@code Examples/case II} to be pairwise weak timed bisimilar when exactly
 * {@code getSense}, {@code activateh} and {@code switchoff} are observable. This
 * suite asserts that, and asserts that small deliberate corruptions of the same
 * fixtures are rejected — a test that only ever says "yes" proves nothing.
 *
 * <p>These fixtures and their expected results were written from the diagrams
 * and the owner's stated assumptions, independently of any other implementation.
 */
@DisplayName("Case II acceptance oracle")
class CaseIIAcceptanceTest {

    private static final List<String> OBSERVABLE =
            List.of("getSense", "activateh", "switchoff");

    private static final String SMART_HOME = "caseii/smarthome.tts";
    private static final String NOTIFY = "caseii/smarthome-notify.tts";
    private static final String TC2STEP = "caseii/smarthome-tc2step.tts";

    private static ReductionRequest request() {
        return ReductionRequest.of(ObservableSet.of(OBSERVABLE));
    }

    private static TransitionSystem hidden(RawTransitionSystem model) {
        return Hiding.apply(model, ObservableSet.of(OBSERVABLE)).reachableFragment();
    }

    private static boolean bisimilar(RawTransitionSystem a, RawTransitionSystem b) {
        return ReductionService.compareSystems(hidden(a), hidden(b), request()).bisimilar();
    }

    @Test
    @DisplayName("transcriptions have the state and transition counts recorded in the provenance doc")
    void transcriptionShape() {
        RawTransitionSystem smartHome = TtsFixture.load(SMART_HOME);
        assertEquals(16, smartHome.states().size(), "SmartHome.png states");
        assertEquals(18, smartHome.transitions().size(), "SmartHome.png transitions");

        RawTransitionSystem notify = TtsFixture.load(NOTIFY);
        assertEquals(42, notify.states().size(), "SmartHome-notify.png states");
        assertEquals(52, notify.transitions().size(), "SmartHome-notify.png transitions");

        RawTransitionSystem tc2step = TtsFixture.load(TC2STEP);
        assertEquals(25, tc2step.states().size(), "SmartHome-tc2step.png states");
        assertEquals(28, tc2step.transitions().size(), "SmartHome-tc2step.png transitions");
    }

    @Test
    @DisplayName("every state is live, as it is in the diagrams")
    void noDeadlocks() {
        for (String fixture : List.of(SMART_HOME, NOTIFY, TC2STEP)) {
            TransitionSystem system = hidden(TtsFixture.load(fixture));
            for (String state : system.states()) {
                assertFalse(system.outgoing(state).isEmpty(),
                        fixture + ": state " + state + " has no outgoing transition");
            }
        }
    }

    @Nested
    @DisplayName("the owner's positive claim")
    class Positive {

        @Test
        @DisplayName("SmartHome and SmartHome-notify are weak timed bisimilar")
        void smartHomeVersusNotify() {
            assertTrue(bisimilar(TtsFixture.load(SMART_HOME), TtsFixture.load(NOTIFY)));
        }

        @Test
        @DisplayName("SmartHome and SmartHome-tc2step are weak timed bisimilar")
        void smartHomeVersusTc2Step() {
            assertTrue(bisimilar(TtsFixture.load(SMART_HOME), TtsFixture.load(TC2STEP)));
        }

        @Test
        @DisplayName("SmartHome-notify and SmartHome-tc2step are weak timed bisimilar")
        void notifyVersusTc2Step() {
            assertTrue(bisimilar(TtsFixture.load(NOTIFY), TtsFixture.load(TC2STEP)));
        }

        @Test
        @DisplayName("the initial states land in corresponding classes of the reduced models")
        void initialStatesShareAClass() {
            var left = ReductionService.reduce(() -> TtsFixture.load(SMART_HOME), request());
            var right = ReductionService.reduce(() -> TtsFixture.load(TC2STEP), request());

            // Each reduced model must still behave like the model it came from.
            assertTrue(ReductionService.compareSystems(
                            left.hidden(), left.quotient().system(), request()).bisimilar(),
                    "SmartHome must be weak timed bisimilar to its own reduction");
            assertTrue(ReductionService.compareSystems(
                            right.hidden(), right.quotient().system(), request()).bisimilar(),
                    "tc2step must be weak timed bisimilar to its own reduction");

            // ...and therefore to each other, which is the required claim
            // carried through the reduction.
            assertTrue(ReductionService.compareSystems(
                            left.quotient().system(), right.quotient().system(), request())
                    .bisimilar(), "the two reduced models must be weak timed bisimilar");

            assertTrue(left.verified(), "SmartHome quotient must pass the independent verifier");
            assertTrue(right.verified(), "tc2step quotient must pass the independent verifier");
            assertTrue(left.quotient().classesAccountedFor(),
                    "every class is either a quotient state or an instant on a delay edge");
            assertTrue(right.quotient().classesAccountedFor());
        }
    }

    @Nested
    @DisplayName("the oracle depends on accumulated weak delays")
    class SemanticSensitivity {

        /**
         * The project semantics requires that a delay may be observed part way
         * through: SmartHome waits ten units in one step, tc2step waits seven,
         * takes an internal step, then waits three. Under the literal
         * single-edge reading of the pseudocode's DelayClosure the two are not
         * equivalent. Recording that here keeps the semantic choice explicit.
         */
        @Test
        @DisplayName("under strict single-edge delays the three models are NOT equivalent")
        void strictSemanticsRejectsTheOracle() {
            ReductionRequest strict =
                    request().withTimeSemantics(TimeSemantics.STRICT_EDGE);
            assertFalse(ReductionService.compareSystems(
                            hidden(TtsFixture.load(SMART_HOME)),
                            hidden(TtsFixture.load(TC2STEP)), strict).bisimilar(),
                    "a 10-unit edge cannot answer a 7-unit edge without time additivity");
            assertFalse(ReductionService.compareSystems(
                            hidden(TtsFixture.load(SMART_HOME)),
                            hidden(TtsFixture.load(NOTIFY)), strict).bisimilar());
        }
    }

    @Nested
    @DisplayName("negative mutants must be rejected")
    class Mutants {

        @Test
        @DisplayName("changing one delay duration breaks the equivalence")
        void mutatedDelay() {
            RawTransitionSystem mutated = mutateFirstDelay(TtsFixture.load(TC2STEP), 8);
            assertFalse(bisimilar(TtsFixture.load(SMART_HOME), mutated),
                    "7+3 matches a 10-unit wait, 8+3 must not");
        }

        @Test
        @DisplayName("renaming one observable message server breaks the equivalence")
        void mutatedObservableLabel() {
            RawTransitionSystem mutated =
                    renameTitle(TtsFixture.load(TC2STEP), "switchoff", "switchoff2");
            assertFalse(bisimilar(TtsFixture.load(SMART_HOME), mutated));
        }

        @Test
        @DisplayName("dropping one observable message server breaks the equivalence")
        void mutatedObservableSet() {
            RawTransitionSystem mutated =
                    renameTitle(TtsFixture.load(NOTIFY), "activateh", "activateh_hidden");
            assertFalse(bisimilar(TtsFixture.load(SMART_HOME), mutated));
        }

        @Test
        @DisplayName("removing one transition breaks the equivalence")
        void mutatedEdge() {
            RawTransitionSystem original = TtsFixture.load(TC2STEP);
            RawTransitionSystem mutated = withoutTransition(original, "S24_0", "S25_0");
            assertFalse(bisimilar(TtsFixture.load(SMART_HOME), mutated));
        }

        @Test
        @DisplayName("a model observing nothing is not equivalent to one observing the three names")
        void differentObservableSetsDiffer() {
            TransitionSystem visible = hidden(TtsFixture.load(SMART_HOME));
            TransitionSystem blind = Hiding.apply(TtsFixture.load(SMART_HOME), ObservableSet.none())
                    .reachableFragment();
            assertFalse(ReductionService.compareSystems(visible, blind, request()).bisimilar());
        }
    }

    // --- mutation helpers -------------------------------------------------

    private static RawTransitionSystem mutateFirstDelay(RawTransitionSystem model, int newValue) {
        RawTransitionSystem.Builder builder = copyStates(model);
        boolean[] done = {false};
        for (RawTransition t : model.transitions()) {
            if (!done[0] && t.isDelay()) {
                done[0] = true;
                builder.transition(RawTransition.time(t.source(), t.target(), newValue,
                        t.executionTime(), t.shift()));
            } else {
                builder.transition(t);
            }
        }
        return builder.build();
    }

    private static RawTransitionSystem renameTitle(RawTransitionSystem model,
                                                   String from, String to) {
        RawTransitionSystem.Builder builder = copyStates(model);
        for (RawTransition t : model.transitions()) {
            if (t.action().isPresent() && t.action().get().title().equals(from)) {
                var action = t.action().get();
                builder.transition(RawTransition.message(t.source(), t.target(),
                        new ir.ut.ce.awtr.source.ActionIdentity(
                                action.sender(), action.owner(), to),
                        t.executionTime(), t.shift()));
            } else {
                builder.transition(t);
            }
        }
        return builder.build();
    }

    private static RawTransitionSystem withoutTransition(RawTransitionSystem model,
                                                         String source, String target) {
        RawTransitionSystem.Builder builder = copyStates(model);
        boolean removed = false;
        for (RawTransition t : model.transitions()) {
            if (!removed && t.source().equals(source) && t.target().equals(target)) {
                removed = true;
                continue;
            }
            builder.transition(t);
        }
        assertTrue(removed, "mutation target " + source + " -> " + target + " must exist");
        return builder.build();
    }

    private static RawTransitionSystem.Builder copyStates(RawTransitionSystem model) {
        RawTransitionSystem.Builder builder = RawTransitionSystem.builder(model.id() + "-mutant");
        model.states().forEach(
                state -> builder.state(state, model.atomicPropositions().get(state)));
        builder.initialState(model.initialState());
        return builder;
    }
}
