package ir.ut.ce.awtr.experiment;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import ir.ut.ce.awtr.tts.Label;
import ir.ut.ce.awtr.tts.Transition;
import ir.ut.ce.awtr.tts.TransitionSystem;
import ir.ut.ce.awtr.weak.WeakTransitionRelation;

/**
 * Test-only generator of small transition systems, and of mutations that are
 * known not to change weak timed behaviour.
 *
 * <p>It exists so the reduce-then-compare experiment can be run against many
 * more systems than anybody can write by hand, with a seed so a disagreement it
 * finds can be reproduced exactly.
 */
final class RandomSystems {

    /** The alphabet the generated systems draw from. */
    static final List<Label> LABELS = List.of(
            Label.TAU, Label.observable("a"), Label.observable("b"),
            Label.delay(1), Label.delay(2));

    private RandomSystems() {
    }

    /** A random system on up to {@code maxStates} states, restricted to what is reachable. */
    static TransitionSystem random(Random rng, int maxStates) {
        int states = 2 + rng.nextInt(Math.max(1, maxStates - 1));
        TransitionSystem.Builder builder = TransitionSystem.builder("r").initialState("s0");
        for (int i = 1; i < states; i++) {
            builder.state("s" + i);
        }
        for (int i = 0; i < states; i++) {
            int degree = rng.nextInt(3);
            for (int k = 0; k < degree; k++) {
                builder.transition("s" + i, LABELS.get(rng.nextInt(LABELS.size())),
                        "s" + rng.nextInt(states));
            }
        }
        return builder.build().reachableFragment();
    }

    /**
     * A system that is weak timed bisimilar to {@code system} by construction,
     * built by applying one or more of:
     *
     * <ul>
     *   <li>renaming every state;</li>
     *   <li>adding a silent step the tau closure already provides;</li>
     *   <li>adding a silent self loop;</li>
     *   <li>duplicating a state and sending one edge to the copy.</li>
     * </ul>
     *
     * <p>None of these changes what any state can weakly do, under either time
     * semantics, so a checker that calls the result inequivalent is wrong.
     */
    static TransitionSystem equivalentMutant(Random rng, TransitionSystem system) {
        TransitionSystem current = system;
        int rounds = 1 + rng.nextInt(3);
        for (int i = 0; i < rounds; i++) {
            current = switch (rng.nextInt(4)) {
                case 0 -> renamed(current, "m" + i + "_");
                case 1 -> withRedundantTau(rng, current);
                case 2 -> withSilentSelfLoop(rng, current);
                default -> withDuplicatedState(rng, current);
            };
        }
        return renamed(current, "t_");
    }

    static TransitionSystem renamed(TransitionSystem system, String prefix) {
        TransitionSystem.Builder builder = TransitionSystem.builder(system.id())
                .initialState(prefix + system.initialState());
        system.states().forEach(state -> builder.syntheticStateIfAbsent(prefix + state));
        for (Transition t : system.transitions()) {
            builder.transition(prefix + t.source(), t.label(), prefix + t.target());
        }
        return builder.build();
    }

    /** Adds {@code s --tau--> t} for some {@code t} already silently reachable from {@code s}. */
    private static TransitionSystem withRedundantTau(Random rng, TransitionSystem system) {
        WeakTransitionRelation relation = WeakTransitionRelation.of(system);
        List<Transition> options = new ArrayList<>();
        for (int s = 0; s < relation.stateCount(); s++) {
            var closure = relation.tauClosure(s);
            for (int t = closure.nextSetBit(0); t >= 0; t = closure.nextSetBit(t + 1)) {
                if (t != s) {
                    options.add(new Transition(relation.stateAt(s), Label.TAU, relation.stateAt(t)));
                }
            }
        }
        if (options.isEmpty()) {
            return system;
        }
        return plus(system, options.get(rng.nextInt(options.size())));
    }

    private static TransitionSystem withSilentSelfLoop(Random rng, TransitionSystem system) {
        String state = system.states().get(rng.nextInt(system.stateCount()));
        return plus(system, new Transition(state, Label.TAU, state));
    }

    /**
     * Adds a copy of one state carrying the same outgoing edges, and points one
     * edge that led to the original at the copy instead. The copy is strongly
     * bisimilar to the original, so nothing observable changes.
     */
    private static TransitionSystem withDuplicatedState(Random rng, TransitionSystem system) {
        String original = system.states().get(rng.nextInt(system.stateCount()));
        String copy = original + "_copy";
        if (system.states().contains(copy)) {
            return system;
        }
        TransitionSystem.Builder builder = TransitionSystem.builder(system.id())
                .initialState(system.initialState());
        system.states().forEach(builder::syntheticStateIfAbsent);
        builder.syntheticStateIfAbsent(copy);

        List<Transition> inbound = system.transitions().stream()
                .filter(t -> t.target().equals(original))
                .toList();
        Transition redirected = inbound.isEmpty()
                ? null
                : inbound.get(rng.nextInt(inbound.size()));
        for (Transition t : system.transitions()) {
            if (t.equals(redirected)) {
                builder.transition(t.source(), t.label(), copy);
            } else {
                builder.transition(t);
            }
        }
        for (Transition t : system.outgoing(original)) {
            builder.transition(copy, t.label(),
                    t.target().equals(original) ? copy : t.target());
        }
        return builder.build().reachableFragment();
    }

    private static TransitionSystem plus(TransitionSystem system, Transition extra) {
        TransitionSystem.Builder builder = TransitionSystem.builder(system.id())
                .initialState(system.initialState());
        system.states().forEach(builder::syntheticStateIfAbsent);
        system.transitions().forEach(builder::transition);
        builder.transition(extra);
        return builder.build();
    }
}
