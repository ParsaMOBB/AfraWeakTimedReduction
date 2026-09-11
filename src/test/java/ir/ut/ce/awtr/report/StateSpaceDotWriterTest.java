package ir.ut.ce.awtr.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ir.ut.ce.awtr.source.ActionIdentity;
import ir.ut.ce.awtr.source.RawTransition;
import ir.ut.ce.awtr.source.RawTransitionSystem;

class StateSpaceDotWriterTest {

    @Test
    void rendersEveryAfraLabelAndMarksTheInitialAndDelayEdges() {
        RawTransitionSystem model = RawTransitionSystem.builder("example.statespace")
                .state("1_0", "ready,busy")
                .state("next-state", "")
                .transition(RawTransition.message("1_0", "next-state",
                        new ActionIdentity("sensor", "room", "tempchange"), 0, 0))
                .transition(RawTransition.time("next-state", "1_0", 7, 7, 10))
                .transition(RawTransition.silent("next-state", "next-state", null, null))
                .build();

        String dot = new StateSpaceDotWriter().render(model);

        assertTrue(dot.contains("n0 [label=\"S1_0:\\nready\\nbusy\", shape=doublecircle]"));
        assertTrue(dot.contains("n1 [label=\"next-state\"]"));
        assertTrue(dot.contains("label=\"room.tempchange\\n @0\""));
        assertTrue(dot.contains(
                "label=\"time +=7\\n @7 -> shift(+10)\", style=bold, color=red"));
        assertTrue(dot.contains("label=\"tau\""));
    }

    @Test
    void outputIsDeterministicAndEscapesDotMetacharacters() {
        RawTransitionSystem model = RawTransitionSystem.builder("escaping.statespace")
                .state("state \"one", "line 1,line \"2")
                .state("state two", "")
                .transition(RawTransition.message("state \"one", "state two",
                        new ActionIdentity("", "owner", "say\"hi"), null, null))
                .build();
        StateSpaceDotWriter writer = new StateSpaceDotWriter();

        String first = writer.render(model);

        assertEquals(first, writer.render(model));
        assertTrue(first.contains("state \\\"one"));
        assertTrue(first.contains("line \\\"2"));
        assertTrue(first.contains("owner.say\\\"hi"));
        assertTrue(first.endsWith("}\n"));
    }

    @Test
    void rendersAnUnqualifiedIllustrativeActionWithoutALeadingDot() {
        RawTransitionSystem model = RawTransitionSystem.builder("illustrative.statespace")
                .state("s0", "")
                .state("s1", "")
                .transition(RawTransition.message("s0", "s1",
                        new ActionIdentity("", "", "a"), null, null))
                .build();

        String dot = new StateSpaceDotWriter().render(model);

        assertTrue(dot.contains("label=\"a\""));
        assertFalse(dot.contains("label=\".a\""));
    }
}
