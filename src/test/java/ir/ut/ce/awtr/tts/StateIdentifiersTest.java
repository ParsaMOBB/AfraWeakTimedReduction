package ir.ut.ce.awtr.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ir.ut.ce.awtr.source.RawTransitionSystem;

@DisplayName("state identifier validation")
class StateIdentifiersTest {

    @Test
    @DisplayName("ordinary Afra and derived input identifiers are accepted")
    void acceptsIdentifiersWithoutTheReservedSeparator() {
        assertEquals("1_0", StateIdentifiers.requireValid("1_0"));
        assertEquals("L::S12", StateIdentifiers.requireValid("L::S12"));
        assertEquals("q7", StateIdentifiers.requireValid("q7"));
    }

    @Test
    @DisplayName("the generated-state separator is rejected in input identifiers")
    void rejectsTheReservedSeparator() {
        InvalidModelException error = assertThrows(InvalidModelException.class,
                () -> StateIdentifiers.requireValid("source#2#target@1"));

        assertTrue(error.getMessage().contains("source#2#target@1"), error.getMessage());
        assertTrue(error.getMessage().contains("reserved"), error.getMessage());
    }

    @Test
    @DisplayName("both model builders apply the identifier rule")
    void buildersRejectAnInvalidDeclaredState() {
        assertThrows(InvalidModelException.class,
                () -> TransitionSystem.builder("model").initialState("valid")
                        .state("invalid#state"));
        assertThrows(InvalidModelException.class,
                () -> TransitionSystem.builder("model").initialState("invalid#state"));
        assertThrows(InvalidModelException.class,
                () -> TransitionSystem.builder("model").initialState("valid")
                        .stateIfAbsent("invalid#state"));
        assertThrows(InvalidModelException.class,
                () -> RawTransitionSystem.builder("model")
                        .state("invalid#state", ""));
    }
}
