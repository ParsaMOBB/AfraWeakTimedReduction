package ir.ut.ce.awtr.source;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import ir.ut.ce.awtr.tts.InvalidModelException;

/**
 * The set of interactions the user chose to observe, supplied at run time.
 *
 * <p>Two token shapes are accepted, and the shape decides what the resulting
 * observable label is called:
 *
 * <ul>
 *   <li>a bare message-server name, e.g. {@code getSense}, matches any
 *       transition whose {@code title} is exactly that, regardless of which
 *       actor owns it, and yields the observable label {@code getSense};</li>
 *   <li>a qualified name, e.g. {@code hc_unit.activateh}, matches only that
 *       owner's message server and yields the label {@code hc_unit.activateh}.</li>
 * </ul>
 *
 * <p>The bare form matches the convention the project owner stated for the
 * Case II oracle — actor prefixes and parameters are not additional observable
 * message names. The qualified form matches the identity Afra's own Graphviz
 * transformer prints ({@code owner + "." + title}) and is available when two
 * actors happen to share a message-server name.
 *
 * <p>Matching is case sensitive. Afra identifiers are case sensitive, so
 * folding case here would silently merge distinct message servers.
 */
public final class ObservableSet {

    private final Set<String> bareNames;
    private final Set<String> qualifiedNames;
    private final List<String> tokens;

    private ObservableSet(Set<String> bareNames, Set<String> qualifiedNames, List<String> tokens) {
        this.bareNames = Set.copyOf(bareNames);
        this.qualifiedNames = Set.copyOf(qualifiedNames);
        this.tokens = List.copyOf(tokens);
    }

    /**
     * @param tokens message-server names, bare or {@code owner.title}-qualified
     * @throws InvalidModelException if a token is blank or malformed
     */
    public static ObservableSet of(Collection<String> tokens) {
        Objects.requireNonNull(tokens, "tokens");
        Set<String> bare = new LinkedHashSet<>();
        Set<String> qualified = new LinkedHashSet<>();
        List<String> normalised = new ArrayList<>();
        for (String raw : tokens) {
            if (raw == null) {
                throw new InvalidModelException("observable token must not be null");
            }
            String token = raw.trim();
            if (token.isEmpty()) {
                throw new InvalidModelException("observable token must not be blank");
            }
            if (token.startsWith(".") || token.endsWith(".")) {
                throw new InvalidModelException(
                        "malformed observable token '" + token + "': expected 'name' or 'owner.name'");
            }
            int dot = token.indexOf('.');
            if (dot < 0) {
                bare.add(token);
            } else if (token.indexOf('.', dot + 1) >= 0) {
                throw new InvalidModelException("malformed observable token '" + token
                        + "': expected at most one '.' separating owner from message-server name");
            } else {
                qualified.add(token);
            }
            normalised.add(token);
        }
        return new ObservableSet(bare, qualified, normalised);
    }

    public static ObservableSet none() {
        return of(List.of());
    }

    /** The tokens exactly as supplied, for recording in every result artefact. */
    public List<String> tokens() {
        return tokens;
    }

    public boolean isEmpty() {
        return tokens.isEmpty();
    }

    /**
     * The observable label for {@code action}, or empty when the action is
     * hidden. Afra continuation steps ({@code tau=>...}) are never matched by a
     * bare name: Afra itself marks them as resumptions of an already running
     * message server rather than fresh invocations.
     */
    public Optional<String> observableNameOf(ActionIdentity action) {
        if (qualifiedNames.contains(action.qualifiedName())) {
            return Optional.of(action.qualifiedName());
        }
        if (!action.isContinuation() && bareNames.contains(action.title())) {
            return Optional.of(action.title());
        }
        return Optional.empty();
    }

    /**
     * Tokens that match nothing in {@code model}. Reported as a diagnostic: a
     * typo in an observable name would otherwise silently hide everything and
     * produce a spuriously small quotient.
     */
    public List<String> tokensNotMatching(RawTransitionSystem model) {
        Set<String> matchedBare = new LinkedHashSet<>();
        Set<String> matchedQualified = new LinkedHashSet<>();
        for (ActionIdentity action : model.actions()) {
            if (!action.isContinuation() && bareNames.contains(action.title())) {
                matchedBare.add(action.title());
            }
            if (qualifiedNames.contains(action.qualifiedName())) {
                matchedQualified.add(action.qualifiedName());
            }
        }
        List<String> unmatched = new ArrayList<>();
        for (String token : tokens) {
            boolean matched = token.indexOf('.') < 0
                    ? matchedBare.contains(token)
                    : matchedQualified.contains(token);
            if (!matched) {
                unmatched.add(token);
            }
        }
        return unmatched;
    }

    @Override
    public String toString() {
        return String.join(",", tokens);
    }
}
