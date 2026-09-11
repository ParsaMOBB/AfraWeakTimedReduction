package ir.ut.ce.awtr.afra;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import ir.ut.ce.awtr.source.ActionIdentity;
import ir.ut.ce.awtr.source.RawTransition;
import ir.ut.ce.awtr.source.RawTransitionSystem;
import ir.ut.ce.awtr.source.TransitionSystemSource;
import ir.ut.ce.awtr.tts.InvalidModelException;

/**
 * Reads an unmodified Afra/RMC {@code .statespace} export produced with TTS
 * semantics.
 *
 * <p>The dialect is documented in {@code docs/afra-input-contract.md}; in short,
 * the generated model checker streams
 *
 * <pre>{@code
 * <transitionsystem>
 * <state id="1_0" atomicpropositions="" >...</state>
 * <transition source="1_0" destination="2_0" executionTime="0" shift="0">
 *   <messageserver sender="s" owner="o" title="t"/></transition>
 * <transition source="2_0" destination="3_0" executionTime="0" shift="0">
 *   <time value="7"/></transition>
 * }</pre>
 *
 * <p>Two properties of that stream drive this implementation.
 *
 * <p><b>The root may be unterminated.</b> Current RMC versions write
 * {@code </transitionsystem>} after a normal search, but an interrupted
 * streaming export and some historical fixtures may lack it. A synthetic end
 * tag is appended only when the document does not already carry one.
 *
 * <p><b>The initial state is the first {@code <state>} written.</b> RMC emits it
 * from {@code storeInitialState()} before any transition, and assigns it
 * {@code stateID} 1. Document order is therefore authoritative, and
 * {@code --initial-state} exists only to override it explicitly.
 */
public final class AfraStateSpaceSource implements TransitionSystemSource {

    static final String ROOT = "transitionsystem";
    static final String STATE = "state";
    static final String TRANSITION = "transition";
    static final String MESSAGE_SERVER = "messageserver";
    static final String TIME = "time";

    private static final byte[] SYNTHETIC_END =
            ("\n</" + ROOT + ">\n").getBytes(StandardCharsets.UTF_8);
    private static final int TAIL_SCAN_BYTES = 64 * 1024;

    private final Path path;
    private final String initialStateOverride;

    public AfraStateSpaceSource(Path path) {
        this(path, null);
    }

    public AfraStateSpaceSource(Path path, String initialStateOverride) {
        this.path = Objects.requireNonNull(path, "path");
        this.initialStateOverride = initialStateOverride;
    }

    @Override
    public RawTransitionSystem load() {
        if (!Files.isRegularFile(path)) {
            throw new InvalidModelException("not a readable file: " + path);
        }
        RawTransitionSystem.Builder builder =
                RawTransitionSystem.builder(path.getFileName().toString());
        try (InputStream stream = openTolerantly()) {
            read(stream, builder);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        } catch (XMLStreamException e) {
            throw new InvalidModelException(
                    "malformed state-space XML in " + path + ": " + e.getMessage(), e);
        }
        if (initialStateOverride != null) {
            builder.initialState(initialStateOverride);
        }
        return builder.build();
    }

    /** Appends a root end tag when RMC's unterminated stream needs one. */
    private InputStream openTolerantly() throws IOException {
        if (hasRootEndTag()) {
            return Files.newInputStream(path);
        }
        return new SequenceInputStream(
                Files.newInputStream(path), new ByteArrayInputStream(SYNTHETIC_END));
    }

    private boolean hasRootEndTag() throws IOException {
        long size = Files.size(path);
        int window = (int) Math.min(size, TAIL_SCAN_BYTES);
        byte[] tail = new byte[window];
        try (InputStream in = Files.newInputStream(path)) {
            in.skipNBytes(size - window);
            in.readNBytes(tail, 0, window);
        }
        return new String(tail, StandardCharsets.UTF_8).contains("</" + ROOT);
    }

    private void read(InputStream stream, RawTransitionSystem.Builder builder)
            throws XMLStreamException {
        XMLStreamReader reader = secureFactory().createXMLStreamReader(stream);
        boolean sawRoot = false;
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                String element = reader.getLocalName();
                if (ROOT.equals(element)) {
                    sawRoot = true;
                } else if (STATE.equals(element)) {
                    readState(reader, builder);
                } else if (TRANSITION.equals(element)) {
                    readTransition(reader, builder);
                } else if (!sawRoot) {
                    throw new InvalidModelException("expected <" + ROOT + "> as the root element of "
                            + path + " but found <" + element + ">");
                }
                // Anything else is per-actor detail nested inside <state>; it is
                // provenance for Afra's own viewers and carries no TTS semantics.
            }
        } finally {
            reader.close();
        }
        if (!sawRoot) {
            throw new InvalidModelException(
                    "no <" + ROOT + "> element in " + path + "; this is not an Afra state-space export");
        }
    }

    private void readState(XMLStreamReader reader, RawTransitionSystem.Builder builder) {
        String id = required(reader, "id", STATE);
        builder.state(id, reader.getAttributeValue(null, "atomicpropositions"));
    }

    private void readTransition(XMLStreamReader reader, RawTransitionSystem.Builder builder)
            throws XMLStreamException {
        String source = required(reader, "source", TRANSITION);
        String destination = required(reader, "destination", TRANSITION);
        Integer executionTime = optionalInt(reader, "executionTime", TRANSITION, source, destination);
        Integer shift = optionalInt(reader, "shift", TRANSITION, source, destination);

        Optional<ActionIdentity> action = Optional.empty();
        Optional<Integer> delay = Optional.empty();

        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String child = reader.getLocalName();
                if (MESSAGE_SERVER.equals(child)) {
                    reject(action.isPresent() || delay.isPresent(), source, destination);
                    action = Optional.of(new ActionIdentity(
                            required(reader, "sender", MESSAGE_SERVER),
                            required(reader, "owner", MESSAGE_SERVER),
                            required(reader, "title", MESSAGE_SERVER)));
                } else if (TIME.equals(child)) {
                    reject(action.isPresent() || delay.isPresent(), source, destination);
                    delay = Optional.of(timeValue(reader, source, destination));
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }

        if (action.isPresent()) {
            builder.transition(RawTransition.message(
                    source, destination, action.get(), executionTime, shift));
        } else if (delay.isPresent()) {
            builder.transition(RawTransition.time(
                    source, destination, delay.get(), executionTime, shift));
        } else {
            builder.transition(RawTransition.silent(source, destination, executionTime, shift));
        }
    }

    private void reject(boolean alreadyLabelled, String source, String destination) {
        if (alreadyLabelled) {
            throw new InvalidModelException("transition " + source + " -> " + destination
                    + " carries more than one label element; the schema allows at most one of <"
                    + MESSAGE_SERVER + "> or <" + TIME + ">");
        }
    }

    /**
     * {@code <time value>} is declared {@code xs:decimal} but RMC always writes
     * an {@code (int)}-cast value. A fractional duration would mean the export
     * was produced by a dense-time front end this tool does not support.
     */
    private int timeValue(XMLStreamReader reader, String source, String destination) {
        String raw = required(reader, "value", TIME);
        int units;
        try {
            units = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidModelException("transition " + source + " -> " + destination
                    + " has non-integer <" + TIME + " value=\"" + raw
                    + "\">; only discrete whole-unit time progress is supported", e);
        }
        if (units < 0) {
            throw new InvalidModelException("transition " + source + " -> " + destination
                    + " has negative time progress " + units);
        }
        return units;
    }

    private String required(XMLStreamReader reader, String attribute, String element) {
        String value = reader.getAttributeValue(null, attribute);
        if (value == null) {
            throw new InvalidModelException("<" + element + "> at " + location(reader)
                    + " is missing the required '" + attribute + "' attribute");
        }
        return value;
    }

    private Integer optionalInt(XMLStreamReader reader, String attribute, String element,
                                String source, String destination) {
        String value = reader.getAttributeValue(null, attribute);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new InvalidModelException("<" + element + "> " + source + " -> " + destination
                    + " has non-integer " + attribute + "=\"" + value + "\"", e);
        }
    }

    private String location(XMLStreamReader reader) {
        return "line " + reader.getLocation().getLineNumber()
                + ", column " + reader.getLocation().getColumnNumber();
    }

    /** No DTDs, no external entities, no entity expansion. */
    private static XMLInputFactory secureFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        return factory;
    }

    /** Convenience for callers that only have a path and an observable list. */
    public static List<String> supportedElements() {
        return List.of(ROOT, STATE, TRANSITION, MESSAGE_SERVER, TIME);
    }
}
