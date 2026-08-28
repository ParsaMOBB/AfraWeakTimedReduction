package ir.ut.ce.awtr.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import ir.ut.ce.awtr.tts.InvalidModelException;

/**
 * A transition system as acquired, with every interaction still named.
 *
 * <p>This is the type the adapter boundary trades in. Hiding — which turns
 * unobserved interactions into tau — is a separate, explicit step
 * ({@code Hiding}) driven by the runtime observable set, so the same acquired
 * model can be reduced under different observation choices without re-reading
 * the source.
 */
public final class RawTransitionSystem {

    private final String id;
    private final String initialState;
    private final List<String> states;
    private final Map<String, String> atomicPropositions;
    private final List<RawTransition> transitions;

    private RawTransitionSystem(Builder builder) {
        this.id = builder.id;
        this.initialState = builder.initialState;
        this.states = List.copyOf(builder.states);
        this.atomicPropositions = Map.copyOf(builder.atomicPropositions);
        this.transitions = List.copyOf(builder.transitions);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    public String initialState() {
        return initialState;
    }

    public List<String> states() {
        return states;
    }

    /** Afra's {@code atomicpropositions} attribute per state; kept as provenance. */
    public Map<String, String> atomicPropositions() {
        return atomicPropositions;
    }

    public List<RawTransition> transitions() {
        return transitions;
    }

    /** Every distinct message-server identity in the model, in first-seen order. */
    public Set<ActionIdentity> actions() {
        Set<ActionIdentity> result = new LinkedHashSet<>();
        for (RawTransition t : transitions) {
            t.action().ifPresent(result::add);
        }
        return result;
    }

    /** Every distinct strictly positive delay duration, in first-seen order. */
    public Set<Integer> delays() {
        Set<Integer> result = new LinkedHashSet<>();
        for (RawTransition t : transitions) {
            t.delay().filter(d -> d > 0).ifPresent(result::add);
        }
        return result;
    }

    public static final class Builder {
        private final String id;
        private final Set<String> states = new LinkedHashSet<>();
        private final Map<String, String> atomicPropositions = new LinkedHashMap<>();
        private final List<RawTransition> transitions = new ArrayList<>();
        private String initialState;

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public Builder state(String state, String propositions) {
            Objects.requireNonNull(state, "state");
            if (!states.add(state)) {
                throw new InvalidModelException(
                        "model '" + id + "' declares state '" + state + "' more than once");
            }
            atomicPropositions.put(state, propositions == null ? "" : propositions);
            if (initialState == null) {
                initialState = state;
            }
            return this;
        }

        /** Overrides the "first state wins" default used for the initial state. */
        public Builder initialState(String state) {
            this.initialState = Objects.requireNonNull(state, "initialState");
            return this;
        }

        public Builder transition(RawTransition transition) {
            transitions.add(Objects.requireNonNull(transition, "transition"));
            return this;
        }

        public RawTransitionSystem build() {
            if (states.isEmpty()) {
                throw new InvalidModelException("model '" + id + "' contains no <state> elements");
            }
            if (initialState == null || !states.contains(initialState)) {
                throw new InvalidModelException("model '" + id + "' has no usable initial state"
                        + (initialState == null ? "" : ": '" + initialState + "' was not declared"));
            }
            for (RawTransition t : transitions) {
                if (!states.contains(t.source())) {
                    throw new InvalidModelException("model '" + id + "' transition "
                            + t.source() + " -> " + t.target()
                            + " starts at undeclared state '" + t.source() + "'");
                }
                if (!states.contains(t.target())) {
                    throw new InvalidModelException("model '" + id + "' transition "
                            + t.source() + " -> " + t.target()
                            + " ends at undeclared state '" + t.target() + "'");
                }
            }
            return new RawTransitionSystem(this);
        }
    }
}
