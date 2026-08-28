package ir.ut.ce.awtr.quotient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import ir.ut.ce.awtr.TtsFixture;
import ir.ut.ce.awtr.app.ReductionRequest;
import ir.ut.ce.awtr.app.ReductionResult;
import ir.ut.ce.awtr.app.ReductionService;
import ir.ut.ce.awtr.source.ObservableSet;
import ir.ut.ce.awtr.tts.Label;
import ir.ut.ce.awtr.tts.Transition;
import ir.ut.ce.awtr.tts.TransitionSystem;
import ir.ut.ce.awtr.verify.WeakTimedRelationVerifier;

/**
 * Quotient soundness, checked by an oracle that is not the reducer.
 *
 * <p>The structural invariants are asserted directly, and then the relation the
 * reducer produced is handed to {@link WeakTimedRelationVerifier}, which tests
 * the definition of weak timed bisimulation against it. Mutants exist so the
 * verifier is shown to be capable of saying no.
 */
@DisplayName("quotient soundness")
class QuotientVerificationTest {

    private static final List<String> OBSERVABLE =
            List.of("getSense", "activateh", "switchoff");

    static Stream<String> fixtures() {
        return Stream.of("caseii/smarthome.tts", "caseii/smarthome-notify.tts",
                "caseii/smarthome-tc2step.tts");
    }

    private static ReductionResult reduce(String fixture) {
        return ReductionService.reduce(() -> TtsFixture.load(fixture),
                ReductionRequest.of(ObservableSet.of(OBSERVABLE)));
    }

    @Nested
    @DisplayName("structural invariants")
    class Structure {

        @ParameterizedTest(name = "{0}")
        @MethodSource("ir.ut.ce.awtr.quotient.QuotientVerificationTest#fixtures")
        @DisplayName("every reachable state belongs to exactly one class")
        void everyStateHasExactlyOneClass(String fixture) {
            ReductionResult result = reduce(fixture);
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < result.inputPartition().blockCount(); i++) {
                for (String state : result.inputPartition().block(i)) {
                    assertTrue(seen.add(state), state + " appears in two classes");
                }
            }
            assertEquals(new LinkedHashSet<>(result.hidden().states()), seen,
                    "the partition must cover exactly the reachable states");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ir.ut.ce.awtr.quotient.QuotientVerificationTest#fixtures")
        @DisplayName("every class is either a quotient state or an instant on a delay edge")
        void classesAreAccountedFor(String fixture) {
            ReductionResult result = reduce(fixture);
            assertTrue(result.quotient().classesAccountedFor());
            assertEquals(result.quotient().stateCount(),
                    result.quotient().quotientStateOfClass().size(),
                    "quotient state count equals the number of classes that survived splicing");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ir.ut.ce.awtr.quotient.QuotientVerificationTest#fixtures")
        @DisplayName("no quotient transition points at a missing state")
        void noDanglingQuotientEdges(String fixture) {
            TransitionSystem quotient = reduce(fixture).quotient().system();
            Set<String> states = new LinkedHashSet<>(quotient.states());
            for (Transition t : quotient.transitions()) {
                assertTrue(states.contains(t.source()), "dangling source in " + t);
                assertTrue(states.contains(t.target()), "dangling target in " + t);
            }
            assertTrue(states.contains(quotient.initialState()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ir.ut.ce.awtr.quotient.QuotientVerificationTest#fixtures")
        @DisplayName("the quotient really is smaller")
        void quotientIsSmaller(String fixture) {
            ReductionResult result = reduce(fixture);
            assertTrue(result.reducedStateCount() <= result.originalStateCount(),
                    fixture + ": " + result.reducedStateCount()
                            + " reduced states vs " + result.originalStateCount());
        }
    }

    @Nested
    @DisplayName("the independent verifier accepts the produced relation")
    class Accepts {

        @ParameterizedTest(name = "{0}")
        @MethodSource("ir.ut.ce.awtr.quotient.QuotientVerificationTest#fixtures")
        @DisplayName("the transfer conditions hold in both directions")
        void transferConditionsHold(String fixture) {
            ReductionResult result = reduce(fixture);
            assertNotNull(result.verification(), "verification must run by default");
            assertTrue(result.verification().valid(),
                    fixture + ": " + result.verification().summary());
            assertTrue(result.verification().checkedMoves() > 0,
                    "a verification that checked nothing proves nothing");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ir.ut.ce.awtr.quotient.QuotientVerificationTest#fixtures")
        @DisplayName("the initial state and its class behave alike")
        void initialStatesAgree(String fixture) {
            ReductionResult result = reduce(fixture);
            assertTrue(ReductionService.compareSystems(result.hidden(),
                            result.quotient().system(),
                            ReductionRequest.of(ObservableSet.of(OBSERVABLE))).bisimilar(),
                    fixture + ": the quotient must preserve the initial state's behaviour");
        }
    }

    @Nested
    @DisplayName("the verifier rejects mutated quotients")
    class Rejects {

        private ReductionResult base() {
            return reduce("caseii/smarthome.tts");
        }

        private WeakTimedRelationVerifier.Report verifyAgainst(TransitionSystem mutant) {
            ReductionResult result = base();
            Map<String, String> relation = new LinkedHashMap<>();
            for (String state : result.refined().states()) {
                relation.put(state, result.quotient().expandedStateOfClass().get(state));
            }
            TransitionSystem expanded =
                    ir.ut.ce.awtr.weak.UnitDelayRefinement.of(mutant).refined();
            return WeakTimedRelationVerifier.verify(result.refined(), expanded, relation);
        }

        @Test
        @DisplayName("removing a quotient transition is caught")
        void removedEdge() {
            TransitionSystem quotient = base().quotient().system();
            Transition victim = quotient.transitions().stream()
                    .filter(t -> t.label().isObservable()).findFirst().orElseThrow();
            assertFalse(verifyAgainst(without(quotient, victim)).valid(),
                    "dropping " + victim + " must be rejected");
        }

        @Test
        @DisplayName("relabelling a quotient action is caught")
        void relabelledAction() {
            TransitionSystem quotient = base().quotient().system();
            Transition victim = quotient.transitions().stream()
                    .filter(t -> t.label().isObservable()).findFirst().orElseThrow();
            TransitionSystem mutant = replace(quotient, victim, new Transition(
                    victim.source(), Label.observable("bogus"), victim.target()));
            assertFalse(verifyAgainst(mutant).valid());
        }

        @Test
        @DisplayName("changing a quotient delay is caught")
        void changedDelay() {
            TransitionSystem quotient = base().quotient().system();
            Transition victim = quotient.transitions().stream()
                    .filter(t -> t.label().isDelay()).findFirst().orElseThrow();
            TransitionSystem mutant = replace(quotient, victim, new Transition(
                    victim.source(), Label.delay(victim.label().delayUnits() + 1),
                    victim.target()));
            assertFalse(verifyAgainst(mutant).valid());
        }

        @Test
        @DisplayName("redirecting a quotient transition is caught")
        void redirectedEdge() {
            TransitionSystem quotient = base().quotient().system();
            Transition victim = quotient.transitions().stream()
                    .filter(t -> t.label().isObservable()
                            && !t.target().equals(quotient.initialState()))
                    .findFirst().orElseThrow();
            TransitionSystem mutant = replace(quotient, victim, new Transition(
                    victim.source(), victim.label(), quotient.initialState()));
            assertFalse(verifyAgainst(mutant).valid());
        }

        @Test
        @DisplayName("an added transition that is not in the source is caught")
        void addedEdge() {
            TransitionSystem quotient = base().quotient().system();
            TransitionSystem.Builder builder = copy(quotient);
            builder.transition(quotient.initialState(), Label.observable("switchoff"),
                    quotient.initialState());
            assertFalse(verifyAgainst(builder.build()).valid());
        }

        private static TransitionSystem without(TransitionSystem system, Transition victim) {
            TransitionSystem.Builder builder = copy(system);
            system.transitions().stream().filter(t -> !t.equals(victim))
                    .forEach(builder::transition);
            return builder.build();
        }

        private static TransitionSystem replace(TransitionSystem system, Transition victim,
                                                Transition replacement) {
            TransitionSystem.Builder builder = copy(system);
            List<Transition> kept = new ArrayList<>(system.transitions());
            kept.remove(victim);
            kept.add(replacement);
            kept.forEach(builder::transition);
            return builder.build();
        }

        private static TransitionSystem.Builder copy(TransitionSystem system) {
            TransitionSystem.Builder builder = TransitionSystem.builder(system.id() + "-mutant")
                    .initialState(system.initialState());
            system.states().forEach(builder::stateIfAbsent);
            return builder;
        }
    }
}
