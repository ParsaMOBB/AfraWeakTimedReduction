package ir.ut.ce.awtr.afra;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

@DisplayName("README weak-timed example")
class ReadmeTimedExampleTest {

    private static final ReductionRequest REQUEST =
            ReductionRequest.of(ObservableSet.of(List.of("a")));

    private static AfraStateSpaceSource fixture(String name) throws URISyntaxException {
        var resource = Objects.requireNonNull(
                ReadmeTimedExampleTest.class.getClassLoader().getResource("readme/" + name),
                "missing README fixture " + name);
        return new AfraStateSpaceSource(Path.of(resource.toURI()));
    }

    @Test
    @DisplayName("tau can move inside an elapsed interval but observable a cannot")
    void comparesTheThreeDisplayedStateSpaces() throws URISyntaxException {
        AfraStateSpaceSource first = fixture("observable-then-split-delay.statespace");
        AfraStateSpaceSource second = fixture("observable-then-delay.statespace");
        AfraStateSpaceSource third = fixture("delay-observable-delay.statespace");

        assertTrue(ReductionService.compare(first, second, REQUEST).bisimilar());
        assertFalse(ReductionService.compare(first, third, REQUEST).bisimilar());
        assertFalse(ReductionService.compare(second, third, REQUEST).bisimilar());
    }
}
