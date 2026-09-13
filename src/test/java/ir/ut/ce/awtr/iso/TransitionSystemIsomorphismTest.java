package ir.ut.ce.awtr.iso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import ir.ut.ce.awtr.tts.Label;
import ir.ut.ce.awtr.tts.TransitionSystem;

/**
 * Specification-level tests for the structural comparison.
 *
 * <p>Every case is small enough to decide by hand, and the negative cases say
 * <em>why</em> they are negative, so a checker that rejected everything would
 * fail here rather than pass by accident.
 */
@DisplayName("transition system isomorphism")
class TransitionSystemIsomorphismTest {

    private static final Label A = Label.observable("a");
    private static final Label B = Label.observable("b");
    private static final Label D1 = Label.delay(1);

    private static TransitionSystem chain(String prefix, Label first, Label second) {
        return TransitionSystem.builder("m")
                .initialState(prefix + "0").state(prefix + "1").state(prefix + "2")
                .transition(prefix + "0", first, prefix + "1")
                .transition(prefix + "1", second, prefix + "2")
                .build();
    }

    @Nested
    @DisplayName("positive cases")
    class Positive {

        @Test
        @DisplayName("the same system under a renaming of states is isomorphic")
        void renaming() {
            var result = TransitionSystemIsomorphism.check(chain("s", A, B), chain("t", A, B));
            assertTrue(result.isomorphic(), result.reason());
            assertEquals(Map.of("s0", "t0", "s1", "t1", "s2", "t2"), result.mapping());
            assertTrue(result.forced(), "a rigid system should need no backtracking");
        }

        @Test
        @DisplayName("a system is isomorphic to itself even when it has symmetric branches")
        void symmetricBranches() {
            TransitionSystem left = TransitionSystem.builder("m")
                    .initialState("s").state("x").state("y")
                    .transition("s", A, "x")
                    .transition("s", A, "y")
                    .build();
            TransitionSystem right = TransitionSystem.builder("m")
                    .initialState("p").state("q").state("r")
                    .transition("p", A, "q")
                    .transition("p", A, "r")
                    .build();

            var result = TransitionSystemIsomorphism.check(left, right);
            assertTrue(result.isomorphic(), result.reason());
            assertEquals("p", result.mapping().get("s"));
            assertEquals(List.of(),
                    TransitionSystemIsomorphism.violations(left, right, result.mapping()));
        }

        @Test
        @DisplayName("cycles and self loops are matched")
        void cycles() {
            TransitionSystem left = TransitionSystem.builder("m")
                    .initialState("s").state("t")
                    .transition("s", D1, "t")
                    .transition("t", D1, "s")
                    .transition("t", A, "t")
                    .build();
            TransitionSystem right = TransitionSystem.builder("m")
                    .initialState("u").state("v")
                    .transition("u", D1, "v")
                    .transition("v", D1, "u")
                    .transition("v", A, "v")
                    .build();

            assertTrue(TransitionSystemIsomorphism.check(left, right).isomorphic());
        }
    }

    @Nested
    @DisplayName("negative cases")
    class Negative {

        @Test
        @DisplayName("different state counts are rejected without any search")
        void stateCounts() {
            TransitionSystem left = chain("s", A, B);
            TransitionSystem right = TransitionSystem.builder("m")
                    .initialState("t0").state("t1")
                    .transition("t0", A, "t1")
                    .build();

            var result = TransitionSystemIsomorphism.check(left, right);
            assertFalse(result.isomorphic());
            assertTrue(result.reason().contains("state counts differ"), result.reason());
            assertEquals(0, result.searchNodes());
        }

        @Test
        @DisplayName("the same shape with different labels is rejected")
        void labels() {
            var result = TransitionSystemIsomorphism.check(chain("s", A, B), chain("t", A, A));
            assertFalse(result.isomorphic());
            assertTrue(result.reason().contains("label multisets differ"), result.reason());
        }

        @Test
        @DisplayName("an isomorphism must map the initial state to the initial state")
        void initialStateIsAnchored() {
            TransitionSystem left = TransitionSystem.builder("m")
                    .initialState("s0").state("s1")
                    .transition("s0", A, "s1")
                    .transition("s1", B, "s0")
                    .build();
            TransitionSystem right = TransitionSystem.builder("m")
                    .initialState("t1").state("t0")
                    .transition("t0", A, "t1")
                    .transition("t1", B, "t0")
                    .build();

            // The two are the same two-cycle; only the starting point differs,
            // and from the other starting point the first label is b, not a.
            var result = TransitionSystemIsomorphism.check(left, right);
            assertFalse(result.isomorphic(), "the initial states have different behaviour");
        }

        @Test
        @DisplayName("colour refinement rejects same-size systems with different structure")
        void colourRefinementRejects() {
            TransitionSystem cycle = TransitionSystem.builder("m")
                    .initialState("s0").state("s1").state("s2")
                    .transition("s0", A, "s1")
                    .transition("s1", A, "s2")
                    .transition("s2", A, "s0")
                    .build();
            TransitionSystem lasso = TransitionSystem.builder("m")
                    .initialState("t0").state("t1").state("t2")
                    .transition("t0", A, "t1")
                    .transition("t1", A, "t2")
                    .transition("t2", A, "t2")
                    .build();

            var result = TransitionSystemIsomorphism.check(cycle, lasso);
            assertFalse(result.isomorphic());
            assertEquals(0, result.searchNodes(),
                    "the neighbourhood colouring should settle this before the search");
            assertTrue(result.reason().contains("neighbourhood structure"), result.reason());
        }
    }

    @Nested
    @DisplayName("the independent check of a claimed mapping")
    class Violations {

        @Test
        @DisplayName("a bijection that moves an edge is rejected")
        void wrongEdges() {
            TransitionSystem left = chain("s", A, B);
            TransitionSystem right = chain("t", A, B);
            List<String> violations = TransitionSystemIsomorphism.violations(left, right,
                    Map.of("s0", "t0", "s1", "t2", "s2", "t1"));
            assertFalse(violations.isEmpty());
        }

        @Test
        @DisplayName("a mapping that is not onto is rejected")
        void notOnto() {
            TransitionSystem left = chain("s", A, B);
            TransitionSystem right = chain("t", A, B);
            List<String> violations = TransitionSystemIsomorphism.violations(left, right,
                    Map.of("s0", "t0", "s1", "t1", "s2", "t1"));
            assertTrue(violations.stream().anyMatch(v -> v.contains("injective")), violations.toString());
        }

        @Test
        @DisplayName("a mapping that moves the initial state is rejected")
        void movedInitialState() {
            TransitionSystem left = TransitionSystem.builder("m")
                    .initialState("s0").state("s1")
                    .transition("s0", A, "s1")
                    .transition("s1", A, "s0")
                    .build();
            TransitionSystem right = TransitionSystem.builder("m")
                    .initialState("t0").state("t1")
                    .transition("t0", A, "t1")
                    .transition("t1", A, "t0")
                    .build();
            List<String> violations = TransitionSystemIsomorphism.violations(left, right,
                    Map.of("s0", "t1", "s1", "t0"));
            assertTrue(violations.stream().anyMatch(v -> v.contains("initial state")),
                    violations.toString());
        }
    }

    @Test
    @DisplayName("an exhausted search budget is reported, never guessed")
    void budget() {
        var result = TransitionSystemIsomorphism.check(chain("s", A, B), chain("t", A, B), 0);
        assertEquals(TransitionSystemIsomorphism.Outcome.UNDETERMINED, result.outcome());
        assertFalse(result.conclusive());
        assertTrue(result.reason().contains("budget"), result.reason());
    }
}
