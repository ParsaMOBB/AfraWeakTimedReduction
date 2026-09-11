package ir.ut.ce.awtr.source;

import java.util.Objects;

/**
 * The identity Afra records for a message-server transition.
 *
 * <p>Field names and meanings come from the official transition schema
 * ({@code transition.xsd} in {@code org.rebecalang.afra}) and from the C++
 * emitter in {@code org.rebecalang.rmc}, which writes
 * {@code <messageserver sender="..." owner="..." title="..."/>}.
 *
 * <p>Afra's own Graphviz transformer renders ordinary exports as
 * {@code owner + "." + title} and ignores {@code sender}; {@link #qualifiedName()}
 * follows that convention while avoiding a leading dot for minimal
 * illustrative inputs whose owner is empty.
 */
public record ActionIdentity(String sender, String owner, String title) {

    /**
     * Afra's marker for a step that resumes a partially executed message server
     * rather than starting a new one.
     */
    public static final String CONTINUATION_PREFIX = "tau=>";

    public ActionIdentity {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(title, "title");
    }

    /**
     * {@code owner.title} — or just {@code title} when an illustrative input
     * has no owner.
     */
    public String qualifiedName() {
        return owner.isEmpty() ? title : owner + "." + title;
    }

    /**
     * True when Afra marked this step as the continuation of an already running
     * message server. Such steps are internal by Afra's own reading, so they are
     * never matched by a bare message-server name.
     */
    public boolean isContinuation() {
        return title.startsWith(CONTINUATION_PREFIX);
    }

    @Override
    public String toString() {
        return qualifiedName() + " (sender=" + sender + ")";
    }
}
