package ir.ut.ce.awtr.afra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ir.ut.ce.awtr.source.ActionIdentity;
import ir.ut.ce.awtr.source.RawTransition;
import ir.ut.ce.awtr.source.RawTransitionSystem;
import ir.ut.ce.awtr.tts.InvalidModelException;

/**
 * Contract tests for the Afra {@code .statespace} reader.
 *
 * <p>The expectations here are taken from the official emitter and from the two
 * official readers, not from this implementation; see
 * {@code docs/afra-input-contract.md} for the revisions.
 */
@DisplayName("Afra .statespace reader")
class AfraStateSpaceSourceTest {

    @TempDir
    Path tempDir;

    private Path write(String name, String content) {
        try {
            Path file = tempDir.resolve(name);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path committedFixture() {
        try {
            InputStream stream = AfraStateSpaceSourceTest.class.getClassLoader()
                    .getResourceAsStream("afra/smarthome-tc2step.statespace");
            Path copy = Files.createTempFile("awtr-fixture", ".statespace");
            Files.write(copy, stream.readAllBytes());
            copy.toFile().deleteOnExit();
            return copy;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nested
    @DisplayName("the committed Afra export")
    class CommittedExport {

        private final RawTransitionSystem model =
                new AfraStateSpaceSource(committedFixture()).load();

        @Test
        @DisplayName("parses without hand editing and keeps every state and transition")
        void countsMatchAnIndependentCount() {
            assertEquals(25, model.states().size());
            assertEquals(28, model.transitions().size());
        }

        @Test
        @DisplayName("takes the first <state> as the initial state, as Afra's own loader does")
        void initialStateIsTheFirstOne() {
            assertEquals("1_0", model.initialState());
            assertEquals("1_0", model.states().get(0));
        }

        @Test
        @DisplayName("recovers the delay durations Afra recorded")
        void delaysMatchTheExport() {
            assertEquals(List.of(7, 3), List.copyOf(model.delays()));
            assertEquals(4, model.transitions().stream().filter(RawTransition::isDelay).count());
        }

        @Test
        @DisplayName("recovers every message-server identity")
        void actionsMatchTheExport() {
            List<String> titles = model.actions().stream()
                    .map(ActionIdentity::title).sorted().toList();
            assertEquals(List.of("ACTIVATEH", "CARRIER_CHANGE_OF_TEMP", "GETSENSE",
                    "GETTEMP", "REGULATE", "SWITCHOFF", "TEMPCHANGE"), titles);
        }

        @Test
        @DisplayName("keeps executionTime and shift as provenance without using them as duration")
        void shiftIsProvenanceOnly() {
            RawTransition shifted = model.transitions().stream()
                    .filter(t -> t.source().equals("21_0") && t.target().equals("10_0"))
                    .findFirst().orElseThrow();
            assertEquals(10, shifted.executionTime());
            assertEquals(10, shifted.shift());
            assertTrue(shifted.isMessage(), "a shifted transition is still a message step");
            assertEquals(Optional.empty(), shifted.delay(),
                    "shift must never be read as elapsed time");
        }
    }

    @Test
    @DisplayName("tolerates the missing root end tag a genuine RMC export has")
    void acceptsUnterminatedRoot() {
        Path file = write("open.statespace", """
                <transitionsystem>
                <state id="1_0" atomicpropositions="" >
                </state>
                <state id="2_0" atomicpropositions="" >
                </state>
                <transition source="1_0" destination="2_0" executionTime="0" shift="0"> \
                <time value="5"/></transition>
                """);
        RawTransitionSystem model = new AfraStateSpaceSource(file).load();
        assertEquals(2, model.states().size());
        assertEquals(Optional.of(5), model.transitions().get(0).delay());
    }

    @Test
    @DisplayName("also accepts a properly closed document")
    void acceptsClosedRoot() {
        Path file = write("closed.statespace", """
                <transitionsystem>
                <state id="1_0" atomicpropositions="" ></state>
                </transitionsystem>
                """);
        assertEquals(1, new AfraStateSpaceSource(file).load().states().size());
    }

    @Test
    @DisplayName("skips the per-actor detail non-simplified exports nest inside <state>")
    void ignoresNestedActorDetail() {
        Path file = write("nested.statespace", """
                <transitionsystem>
                <state id="1_0" atomicpropositions="p,q," >
                  <rebec name="room"><statevar name="temp">21</statevar></rebec>
                </state>
                <state id="2_0" atomicpropositions="" ></state>
                <transition source="1_0" destination="2_0" executionTime="0" shift="0"> \
                <messageserver sender="room" owner="sensor" title="getTemp"/></transition>
                </transitionsystem>
                """);
        RawTransitionSystem model = new AfraStateSpaceSource(file).load();
        assertEquals(2, model.states().size());
        assertEquals(1, model.transitions().size());
        assertEquals("p,q,", model.atomicPropositions().get("1_0"));
        assertEquals(new ActionIdentity("room", "sensor", "getTemp"),
                model.transitions().get(0).action().orElseThrow());
    }

    @Test
    @DisplayName("an --initial-state override replaces the document-order default")
    void initialStateOverride() {
        Path file = write("two.statespace", """
                <transitionsystem>
                <state id="1_0" atomicpropositions="" ></state>
                <state id="2_0" atomicpropositions="" ></state>
                </transitionsystem>
                """);
        assertEquals("2_0", new AfraStateSpaceSource(file, "2_0").load().initialState());
    }

    @Nested
    @DisplayName("malformed input is rejected with an actionable message")
    class Rejections {

        @Test
        @DisplayName("a dangling transition endpoint")
        void danglingEndpoint() {
            Path file = write("dangling.statespace", """
                    <transitionsystem>
                    <state id="1_0" atomicpropositions="" ></state>
                    <transition source="1_0" destination="9_9" executionTime="0" shift="0"> \
                    <time value="1"/></transition>
                    </transitionsystem>
                    """);
            InvalidModelException e = assertThrows(InvalidModelException.class,
                    () -> new AfraStateSpaceSource(file).load());
            assertTrue(e.getMessage().contains("9_9"), e.getMessage());
        }

        @Test
        @DisplayName("a duplicate state id")
        void duplicateState() {
            Path file = write("dupe.statespace", """
                    <transitionsystem>
                    <state id="1_0" atomicpropositions="" ></state>
                    <state id="1_0" atomicpropositions="" ></state>
                    </transitionsystem>
                    """);
            assertThrows(InvalidModelException.class,
                    () -> new AfraStateSpaceSource(file).load());
        }

        @Test
        @DisplayName("a negative time progress")
        void negativeDelay() {
            Path file = write("negative.statespace", """
                    <transitionsystem>
                    <state id="1_0" atomicpropositions="" ></state>
                    <transition source="1_0" destination="1_0" executionTime="0" shift="0"> \
                    <time value="-3"/></transition>
                    </transitionsystem>
                    """);
            assertThrows(InvalidModelException.class,
                    () -> new AfraStateSpaceSource(file).load());
        }

        @Test
        @DisplayName("a fractional duration, which would mean a dense-time front end")
        void fractionalDelay() {
            Path file = write("fraction.statespace", """
                    <transitionsystem>
                    <state id="1_0" atomicpropositions="" ></state>
                    <transition source="1_0" destination="1_0" executionTime="0" shift="0"> \
                    <time value="2.5"/></transition>
                    </transitionsystem>
                    """);
            InvalidModelException e = assertThrows(InvalidModelException.class,
                    () -> new AfraStateSpaceSource(file).load());
            assertTrue(e.getMessage().contains("discrete"), e.getMessage());
        }

        @Test
        @DisplayName("a transition carrying both a messageserver and a time")
        void twoLabels() {
            Path file = write("both.statespace", """
                    <transitionsystem>
                    <state id="1_0" atomicpropositions="" ></state>
                    <transition source="1_0" destination="1_0" executionTime="0" shift="0">\
                    <messageserver sender="a" owner="b" title="c"/><time value="1"/></transition>
                    </transitionsystem>
                    """);
            assertThrows(InvalidModelException.class,
                    () -> new AfraStateSpaceSource(file).load());
        }

        @Test
        @DisplayName("a messageserver missing a required attribute")
        void missingAttribute() {
            Path file = write("missing.statespace", """
                    <transitionsystem>
                    <state id="1_0" atomicpropositions="" ></state>
                    <transition source="1_0" destination="1_0" executionTime="0" shift="0"> \
                    <messageserver owner="b" title="c"/></transition>
                    </transitionsystem>
                    """);
            InvalidModelException e = assertThrows(InvalidModelException.class,
                    () -> new AfraStateSpaceSource(file).load());
            assertTrue(e.getMessage().contains("sender"), e.getMessage());
        }

        @Test
        @DisplayName("a document that is not a state space at all")
        void wrongRoot() {
            Path file = write("other.statespace", "<system-info><reached-states>3</reached-states></system-info>\n");
            InvalidModelException e = assertThrows(InvalidModelException.class,
                    () -> new AfraStateSpaceSource(file).load());
            assertTrue(e.getMessage().contains("transitionsystem"), e.getMessage());
        }

        @Test
        @DisplayName("an external entity is not resolved")
        void externalEntitiesAreDisabled() {
            Path file = write("xxe.statespace", """
                    <?xml version="1.0"?>
                    <!DOCTYPE t [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                    <transitionsystem>
                    <state id="1_0" atomicpropositions="&x;" ></state>
                    </transitionsystem>
                    """);
            // Either the DTD is refused outright or the entity is never expanded;
            // both are acceptable, silently inlining the file is not.
            try {
                RawTransitionSystem model = new AfraStateSpaceSource(file).load();
                assertFalse(String.valueOf(model.atomicPropositions().get("1_0")).contains("root:"),
                        "external entity must not be expanded");
            } catch (InvalidModelException expected) {
                assertTrue(true);
            }
        }
    }
}
