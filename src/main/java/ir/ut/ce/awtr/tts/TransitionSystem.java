package ir.ut.ce.awtr.tts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A finite, validated, immutable discrete-time transition system.
 *
 * <p>This is the only shape the reduction algorithm ever sees. It knows nothing
 * about XML, Afra, actors, or the command line: acquiring a model is the job of
 * a {@code TransitionSystemSource}, and every such source funnels into this
 * type. That is the seam a future in-process Afra adapter plugs into.
 *
 * <p>States are kept in insertion order and every derived collection is sorted
 * or insertion-ordered, so two runs over the same input produce byte-identical
 * output.
 */
public final class TransitionSystem {

    private final String id;
    private final String initialState;
    private final List<String> states;
    private final List<Transition> transitions;

    private final Map<String, List<Transition>> outgoing;
    private final Set<Label> alphabet;

    private TransitionSystem(String id, String initialState, List<String> states,
                             List<Transition> transitions) {
        this.id = id;
        this.initialState = initialState;
        this.states = List.copyOf(states);
        this.transitions = List.copyOf(transitions);

        Map<String, List<Transition>> bySource = new LinkedHashMap<>();
        for (String state : this.states) {
            bySource.put(state, new ArrayList<>());
        }
        for (Transition t : this.transitions) {
            bySource.get(t.source()).add(t);
        }
        Map<String, List<Transition>> frozen = new LinkedHashMap<>();
        bySource.forEach((state, list) -> {
            Collections.sort(list);
            frozen.put(state, List.copyOf(list));
        });
        this.outgoing = Collections.unmodifiableMap(frozen);

        Set<Label> labels = new TreeSet<>();
        this.transitions.forEach(t -> labels.add(t.label()));
        this.alphabet = Collections.unmodifiableSet(new LinkedHashSet<>(labels));
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

    /** All states, in the order the source produced them. */
    public List<String> states() {
        return states;
    }

    /** All transitions, in the order the source produced them. */
    public List<Transition> transitions() {
        return transitions;
    }

    /** Outgoing transitions of {@code state}, sorted; never null for a known state. */
    public List<Transition> outgoing(String state) {
        List<Transition> result = outgoing.get(state);
        if (result == null) {
            throw new InvalidModelException("unknown state: " + state);
        }
        return result;
    }

    /** Every label that actually occurs, tau first, then observables, then delays. */
    public Set<Label> alphabet() {
        return alphabet;
    }

    public int stateCount() {
        return states.size();
    }

    public int transitionCount() {
        return transitions.size();
    }

    /** States reachable from the initial state, in deterministic breadth-first order. */
    public List<String> reachableStates() {
        List<String> order = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(initialState);
        seen.add(initialState);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            order.add(current);
            for (Transition t : outgoing(current)) {
                if (seen.add(t.target())) {
                    queue.addLast(t.target());
                }
            }
        }
        return List.copyOf(order);
    }

    /**
     * The sub-system induced by the states reachable from the initial state.
     * Returns {@code this} when nothing is unreachable, so the common case
     * allocates nothing.
     */
    public TransitionSystem reachableFragment() {
        List<String> reachable = reachableStates();
        if (reachable.size() == states.size()) {
            return this;
        }
        Set<String> keep = new LinkedHashSet<>(reachable);
        Builder builder = builder(id).initialState(initialState);
        for (String state : states) {
            if (keep.contains(state)) {
                builder.state(state);
            }
        }
        for (Transition t : transitions) {
            if (keep.contains(t.source()) && keep.contains(t.target())) {
                builder.transition(t);
            }
        }
        return builder.build();
    }

    @Override
    public String toString() {
        return "TransitionSystem[" + id + ", states=" + states.size()
                + ", transitions=" + transitions.size() + ", initial=" + initialState + "]";
    }

    /**
     * Collects states and transitions and validates them on {@link #build()}.
     *
     * <p>Validation is part of the public contract: a system that survives
     * {@code build()} has a declared initial state, no duplicate state ids, and
     * no transition pointing at an unknown state.
     */
    public static final class Builder {

        private final String id;
        private final Set<String> states = new LinkedHashSet<>();
        private final List<Transition> transitions = new ArrayList<>();
        private final Set<Transition> seenTransitions = new LinkedHashSet<>();
        private String initialState;

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public Builder initialState(String state) {
            this.initialState = Objects.requireNonNull(state, "initialState");
            this.states.add(state);
            return this;
        }

        /** @throws InvalidModelException if the same id is declared twice */
        public Builder state(String state) {
            Objects.requireNonNull(state, "state");
            if (!states.add(state) && !state.equals(initialState)) {
                throw new InvalidModelException("duplicate state id: " + state);
            }
            return this;
        }

        /** Adds a state without complaining if it is already known. */
        public Builder stateIfAbsent(String state) {
            states.add(Objects.requireNonNull(state, "state"));
            return this;
        }

        public Builder transition(String source, Label label, String target) {
            return transition(new Transition(source, label, target));
        }

        /** Duplicate edges are dropped: the transition relation is a set. */
        public Builder transition(Transition transition) {
            Objects.requireNonNull(transition, "transition");
            if (seenTransitions.add(transition)) {
                transitions.add(transition);
            }
            return this;
        }

        public Builder transitions(Collection<Transition> values) {
            values.forEach(this::transition);
            return this;
        }

        public TransitionSystem build() {
            if (initialState == null) {
                throw new InvalidModelException(
                        "model '" + id + "' declares no initial state");
            }
            if (states.isEmpty()) {
                throw new InvalidModelException("model '" + id + "' declares no states");
            }
            if (!states.contains(initialState)) {
                throw new InvalidModelException("model '" + id
                        + "' initial state '" + initialState + "' is not a declared state");
            }
            for (Transition t : transitions) {
                if (!states.contains(t.source())) {
                    throw new InvalidModelException("model '" + id + "' transition " + t
                            + " starts at undeclared state '" + t.source() + "'");
                }
                if (!states.contains(t.target())) {
                    throw new InvalidModelException("model '" + id + "' transition " + t
                            + " ends at undeclared state '" + t.target() + "'");
                }
            }
            return new TransitionSystem(id, initialState, new ArrayList<>(states), transitions);
        }
    }
}
