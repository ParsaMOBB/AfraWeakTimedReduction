package ir.ut.ce.awtr.weak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import ir.ut.ce.awtr.tts.InvalidModelException;
import ir.ut.ce.awtr.tts.Label;
import ir.ut.ce.awtr.tts.TransitionSystem;

/**
 * Specification-level tests for the weak timed semantics.
 *
 * <p>Each case is small enough to be checked by hand, and asserts the exact
 * partition as sets of state ids rather than only a yes/no answer, so a wrong
 * partition that happens to give the right boolean still fails.
 */
@DisplayName("weak timed semantics")
class WeakTimedSemanticsTest {

    private static final Label A = Label.observable("a");
    private static final Label B = Label.observable("b");

    private static Partition classesOf(TransitionSystem system) {
        return WeakTimedPartitionRefiner.refine(system).partition();
    }

    /** The partition as a sorted set of comma-joined blocks, for exact assertions. */
    private static Set<String> blocksOf(TransitionSystem system) {
        Set<String> blocks = new TreeSet<>();
        Partition partition = classesOf(system);
        for (int i = 0; i < partition.blockCount(); i++) {
            blocks.add(String.join(",", partition.block(i)));
        }
        return blocks;
    }

    @Nested
    @DisplayName("tau closure")
    class TauClosure {

        @Test
        @DisplayName("a state with no tau steps closes to itself alone")
        void emptyClosure() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s").state("t")
                    .transition("s", A, "t")
                    .transition("t", A, "t")
                    .build();
            WeakTransitionRelation relation = WeakTransitionRelation.of(system);
            assertEquals(1, relation.tauClosure(relation.indexOf("s")).cardinality());
        }

        @Test
        @DisplayName("a tau chain closes transitively")
        void chain() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s0").state("s1").state("s2")
                    .transition("s0", Label.TAU, "s1")
                    .transition("s1", Label.TAU, "s2")
                    .transition("s2", A, "s2")
                    .build();
            WeakTransitionRelation relation = WeakTransitionRelation.of(system);
            assertEquals(3, relation.tauClosure(relation.indexOf("s0")).cardinality());
            assertEquals(1, relation.tauClosure(relation.indexOf("s2")).cardinality());
        }

        @Test
        @DisplayName("a tau cycle terminates and closes to the whole cycle")
        void cycle() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s0").state("s1")
                    .transition("s0", Label.TAU, "s1")
                    .transition("s1", Label.TAU, "s0")
                    .transition("s0", A, "s0")
                    .build();
            WeakTransitionRelation relation = WeakTransitionRelation.of(system);
            assertEquals(2, relation.tauClosure(relation.indexOf("s0")).cardinality());
            assertEquals(Set.of("s0,s1"), blocksOf(system));
        }

        @Test
        @DisplayName("a tau prefix is invisible")
        void prefix() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s0").state("s1")
                    .transition("s0", Label.TAU, "s1")
                    .transition("s1", A, "s1")
                    .build();
            assertEquals(Set.of("s0,s1"), blocksOf(system));
        }

        @Test
        @DisplayName("a tau suffix is invisible")
        void suffix() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s0").state("s1").state("s2")
                    .transition("s0", A, "s1")
                    .transition("s1", Label.TAU, "s2")
                    .build();
            assertEquals(Set.of("s0", "s1,s2"), blocksOf(system));
        }

        @Test
        @DisplayName("a tau branch into a dead end is NOT invisible")
        void branchToDeadEnd() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s0").state("live").state("dead")
                    .transition("s0", Label.TAU, "live")
                    .transition("s0", Label.TAU, "dead")
                    .transition("live", A, "live")
                    .build();
            // s0 can silently reach a state that cannot do `a`, so it is not
            // equivalent to `live`. This is the d=0 case of the weak delay
            // relation; without it the three states would collapse.
            assertEquals(Set.of("s0", "dead", "live"), blocksOf(system));
        }
    }

    @Nested
    @DisplayName("observable actions")
    class Observables {

        @Test
        @DisplayName("different action names are distinguished")
        void differentActions() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s").state("t")
                    .transition("s", A, "s")
                    .transition("t", B, "t")
                    .build();
            assertEquals(Set.of("s", "t"), blocksOf(system));
        }

        @Test
        @DisplayName("a nondeterministic action with equivalent targets merges them")
        void nondeterministicTargets() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s").state("t1").state("t2")
                    .transition("s", A, "t1")
                    .transition("s", A, "t2")
                    .transition("t1", B, "t1")
                    .transition("t2", B, "t2")
                    .build();
            assertEquals(Set.of("s", "t1,t2"), blocksOf(system));
        }

        @Test
        @DisplayName("a nondeterministic action with distinguishable targets does not")
        void nondeterministicDistinguishableTargets() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s").state("t1").state("t2")
                    .transition("s", A, "t1")
                    .transition("s", A, "t2")
                    .transition("t1", B, "t1")
                    .build();
            assertEquals(Set.of("s", "t1", "t2"), blocksOf(system));
        }

        @Test
        @DisplayName("a deadlock is not equivalent to a live state")
        void deadlock() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s").state("stuck")
                    .transition("s", A, "s")
                    .build();
            assertEquals(Set.of("s", "stuck"), blocksOf(system));
        }
    }

    @Nested
    @DisplayName("delays")
    class Delays {

        @Test
        @DisplayName("exact durations are distinguished")
        void exactDuration() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s").state("s2").state("t").state("t2")
                    .transition("s", Label.delay(1), "s2")
                    .transition("t", Label.delay(2), "t2")
                    .transition("s2", A, "s2")
                    .transition("t2", A, "t2")
                    .build();
            assertNotEquals(classesOf(system).blockOf("s"), classesOf(system).blockOf("t"));
        }

        @Test
        @DisplayName("under time additivity a 2-unit step answers 1 unit then 1 unit")
        void additivity() {
            TransitionSystem oneStep = TransitionSystem.builder("one")
                    .initialState("a0").state("a1")
                    .transition("a0", Label.delay(2), "a1")
                    .transition("a1", A, "a1")
                    .build();
            TransitionSystem twoSteps = TransitionSystem.builder("two")
                    .initialState("b0").state("bm").state("b1")
                    .transition("b0", Label.delay(1), "bm")
                    .transition("bm", Label.delay(1), "b1")
                    .transition("b1", A, "b1")
                    .build();

            assertTrue(bisimilar(oneStep, twoSteps, TimeSemantics.UNIT_ADDITIVE),
                    "time additivity must let 1+1 answer 2");
            assertFalse(bisimilar(oneStep, twoSteps, TimeSemantics.STRICT_EDGE),
                    "the literal single-edge reading must not");
        }

        @Test
        @DisplayName("only total delay between observable actions matters")
        void tauPositionInsideADelayIsUnobservable() {
            TransitionSystem tenThenTau = TransitionSystem.builder("10+tau")
                    .initialState("a0").state("am").state("a1")
                    .transition("a0", Label.delay(10), "am")
                    .transition("am", Label.TAU, "a1")
                    .transition("a1", A, "a1")
                    .build();
            TransitionSystem threeThenSeven = TransitionSystem.builder("3+tau+7")
                    .initialState("b0").state("bm").state("bn").state("b1")
                    .transition("b0", Label.delay(3), "bm")
                    .transition("bm", Label.TAU, "bn")
                    .transition("bn", Label.delay(7), "b1")
                    .transition("b1", A, "b1")
                    .build();
            TransitionSystem twoThenEight = TransitionSystem.builder("2+tau+8")
                    .initialState("c0").state("cm").state("cn").state("c1")
                    .transition("c0", Label.delay(2), "cm")
                    .transition("cm", Label.TAU, "cn")
                    .transition("cn", Label.delay(8), "c1")
                    .transition("c1", A, "c1")
                    .build();

            assertTrue(bisimilar(threeThenSeven, twoThenEight,
                    TimeSemantics.UNIT_ADDITIVE));
            assertTrue(bisimilar(threeThenSeven, tenThenTau,
                    TimeSemantics.UNIT_ADDITIVE));
            assertTrue(bisimilar(twoThenEight, tenThenTau,
                    TimeSemantics.UNIT_ADDITIVE));

            assertFalse(bisimilar(threeThenSeven, twoThenEight,
                    TimeSemantics.STRICT_EDGE));
            assertFalse(bisimilar(threeThenSeven, tenThenTau,
                    TimeSemantics.STRICT_EDGE));
        }

        @Test
        @DisplayName("a zero-duration time step is an internal step, not a delay")
        void zeroDelayIsTau() {
            assertThrows(IllegalArgumentException.class, () -> Label.delay(0));
        }

        @Test
        @DisplayName("several distinct durations are all refined on")
        void multipleDurations() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s").state("x").state("y")
                    .transition("s", Label.delay(1), "x")
                    .transition("s", Label.delay(3), "y")
                    .transition("x", A, "x")
                    .transition("y", B, "y")
                    .build();
            assertEquals(Set.of("s", "x", "y"), blocksOf(system));
        }

        @Test
        @DisplayName("a delay cycle terminates")
        void delayCycle() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s").state("t")
                    .transition("s", Label.delay(2), "t")
                    .transition("t", Label.delay(2), "s")
                    .build();
            assertEquals(1, classesOf(UnitDelayRefinement.of(system).refined()).blockCount(),
                    "every instant of an endless wait behaves alike");
        }
    }

    @Nested
    @DisplayName("refinement termination and determinism")
    class Stability {

        @Test
        @DisplayName("the refinement reaches a stable partition")
        void reachesFixpoint() {
            TransitionSystem system = ladder(12);
            var result = WeakTimedPartitionRefiner.refine(system);
            assertTrue(result.refinementRounds() >= 2);
            assertTrue(result.refinementRounds() <= system.stateCount() + 2,
                    "refinement must not need more rounds than there are states");
        }

        @Test
        @DisplayName("running twice on the same system gives the identical partition")
        void deterministic() {
            TransitionSystem system = ladder(9);
            assertEquals(blocksOf(system), blocksOf(system));
            assertEquals(classesOf(system).blocks().toString(),
                    classesOf(system).blocks().toString());
        }

        @Test
        @DisplayName("unreachable states are dropped before refinement")
        void unreachableStatesAreDropped() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s").state("orphan")
                    .transition("s", A, "s")
                    .transition("orphan", B, "orphan")
                    .build();
            assertEquals(List.of("s"), system.reachableFragment().states());
        }

        private static TransitionSystem ladder(int rungs) {
            TransitionSystem.Builder builder = TransitionSystem.builder("ladder")
                    .initialState("n0");
            for (int i = 1; i <= rungs; i++) {
                builder.state("n" + i);
            }
            for (int i = 0; i < rungs; i++) {
                builder.transition("n" + i, i % 2 == 0 ? A : Label.delay(1), "n" + (i + 1));
            }
            builder.transition("n" + rungs, B, "n" + rungs);
            return builder.build();
        }
    }

    @Nested
    @DisplayName("model validation")
    class Validation {

        @Test
        @DisplayName("a transition to an undeclared state is rejected")
        void danglingTransition() {
            InvalidModelException e = assertThrows(InvalidModelException.class,
                    () -> TransitionSystem.builder("m").initialState("s")
                            .transition("s", A, "ghost").build());
            assertTrue(e.getMessage().contains("ghost"), e.getMessage());
        }

        @Test
        @DisplayName("a model with no initial state is rejected")
        void noInitialState() {
            assertThrows(InvalidModelException.class,
                    () -> TransitionSystem.builder("m").state("s").build());
        }

        @Test
        @DisplayName("a duplicate state id is rejected")
        void duplicateState() {
            assertThrows(InvalidModelException.class,
                    () -> TransitionSystem.builder("m").initialState("s").state("t").state("t"));
        }

        @Test
        @DisplayName("the transition relation is a set, so duplicate edges collapse")
        void duplicateEdgesCollapse() {
            TransitionSystem system = TransitionSystem.builder("m")
                    .initialState("s")
                    .transition("s", A, "s")
                    .transition("s", A, "s")
                    .build();
            assertEquals(1, system.transitionCount());
        }
    }

    private static boolean bisimilar(TransitionSystem left, TransitionSystem right,
                                     TimeSemantics semantics) {
        TransitionSystem combinedLeft = left;
        TransitionSystem combinedRight = right;
        DisjointUnion union = DisjointUnion.of(combinedLeft, combinedRight);
        TransitionSystem system = semantics == TimeSemantics.UNIT_ADDITIVE
                ? UnitDelayRefinement.of(union.combined()).refined()
                : union.combined();
        return WeakTimedPartitionRefiner.refine(system).partition()
                .sameBlock(union.leftInitial(), union.rightInitial());
    }
}
