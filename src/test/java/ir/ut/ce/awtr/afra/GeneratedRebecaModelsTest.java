package ir.ut.ce.awtr.afra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ir.ut.ce.awtr.app.ReductionRequest;
import ir.ut.ce.awtr.app.ReductionService;
import ir.ut.ce.awtr.source.ObservableSet;

@DisplayName("state spaces generated from the example Rebeca models by RMC 2.14")
class GeneratedRebecaModelsTest {

    private static AfraStateSpaceSource fixture(String name) throws URISyntaxException {
        var resource = Objects.requireNonNull(
                GeneratedRebecaModelsTest.class.getClassLoader()
                        .getResource("rebeca-generated/" + name + ".statespace"),
                "missing RMC-generated fixture " + name);
        return new AfraStateSpaceSource(Path.of(resource.toURI()));
    }

    private static void assertEquivalent(String left, String right, String... observable)
            throws URISyntaxException {
        var request = ReductionRequest.of(ObservableSet.of(List.of(observable)));
        assertTrue(ReductionService.compare(fixture(left), fixture(right), request).bisimilar(),
                left + " and " + right + " must be weak timed bisimilar");
    }

    @Test
    @DisplayName("the production reader accepts every unmodified generated export")
    void loadsAllGeneratedStateSpacesWithTheirExpectedShapes() throws URISyntaxException {
        assertShape("mood1", 10, 12);
        assertShape("mood2", 6, 8);
        assertShape("teacher1", 19, 19);
        assertShape("teacher2", 7, 7);
        assertShape("teacher3", 19, 19);
        assertShape("chain1", 23, 25);
        assertShape("chain2", 10, 12);
    }

    @Test
    void moodModelsAreEquivalent() throws URISyntaxException {
        assertEquivalent("mood1", "mood2", "mood.LAUGH", "mood.CRY");
    }

    @Test
    void teacherModelsArePairwiseEquivalent() throws URISyntaxException {
        assertEquivalent("teacher1", "teacher2", "teacher.TEACH");
        assertEquivalent("teacher1", "teacher3", "teacher.TEACH");
        assertEquivalent("teacher2", "teacher3", "teacher.TEACH");
    }

    @Test
    void chainModelsAreEquivalent() throws URISyntaxException {
        assertEquivalent("chain1", "chain2",
                "worker.DECIDE", "controller.APPROVED", "controller.REJECTED");
    }

    private static void assertShape(String name, int states, int transitions)
            throws URISyntaxException {
        var model = fixture(name).load();
        assertEquals(states, model.states().size(), name + " states");
        assertEquals(transitions, model.transitions().size(), name + " transitions");
    }
}
