package ir.ut.ce.awtr.verify;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import ir.ut.ce.awtr.tts.Label;
import ir.ut.ce.awtr.tts.Transition;
import ir.ut.ce.awtr.tts.TransitionSystem;

/**
 * Checks that a given relation really is a weak timed bisimulation.
 *
 * <p>This is deliberately not the refinement algorithm run a second time. It
 * takes a relation someone else produced and tests the definition against it
 * directly: for every related pair and every weak move of one side, the other
 * side must have a matching weak move landing in a related state, in both
 * directions, and the two initial states must be related.
 *
 * <p>The closure and weak-step machinery here is written from scratch with
 * plain sets rather than reusing the reducer's bit-set implementation, so a
 * defect in that implementation cannot hide behind an identical defect here.
 *
 * <p>Callers pass systems whose delay labels are already in the form the chosen
 * time semantics calls for — under the unit-additive reading, both sides are
 * unit-delay refined first, and the relation must then cover the intermediate
 * instants too.
 */
public final class WeakTimedRelationVerifier {

    private final TransitionSystem left;
    private final TransitionSystem right;
    private final Map<String, String> relation;
    private final Map<String, Set<String>> inverse;
    private final List<String> violations = new ArrayList<>();
    private int checkedMoves;

    private WeakTimedRelationVerifier(TransitionSystem left, TransitionSystem right,
                                      Map<String, String> relation) {
        this.left = left;
        this.right = right;
        this.relation = relation;
        this.inverse = new HashMap<>();
        relation.forEach((l, r) -> inverse.computeIfAbsent(r, k -> new LinkedHashSet<>()).add(l));
    }

    /**
     * @param left     the system being reduced
     * @param right    the reduced system
     * @param relation a total map from every state of {@code left} to a state of
     *                 {@code right}; the relation being verified is its graph
     */
    public static Report verify(TransitionSystem left, TransitionSystem right,
                                Map<String, String> relation) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(relation, "relation");
        return new WeakTimedRelationVerifier(left, right, relation).run();
    }

    private Report run() {
        Closures leftClosures = new Closures(left);
        Closures rightClosures = new Closures(right);

        for (String state : left.states()) {
            if (!relation.containsKey(state)) {
                violations.add("relation does not cover state '" + state + "'");
            }
        }
        if (violations.isEmpty()) {
            String relatedInitial = relation.get(left.initialState());
            if (!right.initialState().equals(relatedInitial)) {
                violations.add("initial states are not related: '" + left.initialState()
                        + "' maps to '" + relatedInitial
                        + "' but the reduced initial state is '" + right.initialState() + "'");
            }
        }

        Set<Label> alphabet = new TreeSet<>();
        left.alphabet().stream().filter(l -> !l.isTau()).forEach(alphabet::add);
        right.alphabet().stream().filter(l -> !l.isTau()).forEach(alphabet::add);

        for (String source : left.states()) {
            String image = relation.get(source);
            if (image == null) {
                continue;
            }
            checkEmptyMove(source, image, leftClosures, rightClosures);
            for (Label label : alphabet) {
                checkForward(source, image, label, leftClosures, rightClosures);
                checkBackward(source, image, label, leftClosures, rightClosures);
            }
            if (violations.size() > MAX_REPORTED) {
                break;
            }
        }
        return new Report(violations.isEmpty(), List.copyOf(violations),
                relation.size(), checkedMoves);
    }

    private static final int MAX_REPORTED = 50;

    /**
     * The {@code d = 0} case: a silent move of one side must be answered by a
     * silent move of the other. Without this a tau branch into a state with
     * fewer capabilities would go unchecked.
     */
    private void checkEmptyMove(String source, String image,
                                Closures leftClosures, Closures rightClosures) {
        for (String reached : leftClosures.tau(source)) {
            checkedMoves++;
            String target = relation.get(reached);
            if (target == null || !rightClosures.tau(image).contains(target)) {
                violations.add("silent move " + source + " =tau*=> " + reached
                        + " has no match: " + image + " cannot reach "
                        + target + " silently");
            }
        }
        for (String reached : rightClosures.tau(image)) {
            checkedMoves++;
            if (leftClosures.tau(source).stream()
                    .noneMatch(candidate -> reached.equals(relation.get(candidate)))) {
                violations.add("reduced silent move " + image + " =tau*=> " + reached
                        + " has no match from " + source);
            }
        }
    }

    /** Every weak move of the left system must be answered by the right one. */
    private void checkForward(String source, String image, Label label,
                              Closures leftClosures, Closures rightClosures) {
        Set<String> reachable = leftClosures.weak(source, label);
        if (reachable.isEmpty()) {
            return;
        }
        Set<String> answers = rightClosures.weak(image, label);
        for (String reached : reachable) {
            checkedMoves++;
            String target = relation.get(reached);
            if (target == null || !answers.contains(target)) {
                violations.add(source + " =" + label + "=> " + reached
                        + " has no match: " + image + " cannot reach " + target
                        + " under " + label);
            }
        }
    }

    /** ...and symmetrically, every weak move of the right system. */
    private void checkBackward(String source, String image, Label label,
                               Closures leftClosures, Closures rightClosures) {
        Set<String> reachable = rightClosures.weak(image, label);
        if (reachable.isEmpty()) {
            return;
        }
        Set<String> answers = leftClosures.weak(source, label);
        for (String reached : reachable) {
            checkedMoves++;
            boolean matched = answers.stream()
                    .anyMatch(candidate -> reached.equals(relation.get(candidate)));
            if (!matched) {
                violations.add("reduced move " + image + " =" + label + "=> " + reached
                        + " has no match from " + source + " under " + label);
            }
        }
    }

    /** Straightforward set-based tau closure and weak steps. */
    private static final class Closures {
        private final Map<String, List<Transition>> outgoing = new HashMap<>();
        private final Map<String, Set<String>> tauCache = new HashMap<>();
        private final Map<String, Map<Label, Set<String>>> weakCache = new HashMap<>();

        Closures(TransitionSystem system) {
            for (String state : system.states()) {
                outgoing.put(state, new ArrayList<>());
            }
            for (Transition t : system.transitions()) {
                outgoing.get(t.source()).add(t);
            }
        }

        Set<String> tau(String state) {
            Set<String> cached = tauCache.get(state);
            if (cached != null) {
                return cached;
            }
            Set<String> reached = new LinkedHashSet<>();
            Deque<String> pending = new ArrayDeque<>();
            reached.add(state);
            pending.push(state);
            while (!pending.isEmpty()) {
                for (Transition t : outgoing.getOrDefault(pending.pop(), List.of())) {
                    if (t.label().isTau() && reached.add(t.target())) {
                        pending.push(t.target());
                    }
                }
            }
            tauCache.put(state, reached);
            return reached;
        }

        /** {@code tau* label tau*} */
        Set<String> weak(String state, Label label) {
            Map<Label, Set<String>> perLabel =
                    weakCache.computeIfAbsent(state, k -> new HashMap<>());
            Set<String> cached = perLabel.get(label);
            if (cached != null) {
                return cached;
            }
            Set<String> result = new HashSet<>();
            for (String middle : tau(state)) {
                for (Transition t : outgoing.getOrDefault(middle, List.of())) {
                    if (t.label().equals(label)) {
                        result.addAll(tau(t.target()));
                    }
                }
            }
            perLabel.put(label, result);
            return result;
        }
    }

    /**
     * @param valid        true when the relation satisfies the definition
     * @param violations   human-readable failures, capped for readability
     * @param relatedPairs how many pairs the relation contained
     * @param checkedMoves how many transfer obligations were discharged
     */
    public record Report(boolean valid, List<String> violations,
                         int relatedPairs, int checkedMoves) {

        public String summary() {
            return valid
                    ? "relation is a weak timed bisimulation (" + relatedPairs
                            + " pairs, " + checkedMoves + " transfer checks)"
                    : violations.size() + " violation(s), first: " + violations.get(0);
        }
    }
}
