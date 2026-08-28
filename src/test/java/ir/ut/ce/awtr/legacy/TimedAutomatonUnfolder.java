package ir.ut.ce.awtr.legacy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ir.ut.ce.awtr.source.ActionIdentity;
import ir.ut.ce.awtr.source.RawTransition;
import ir.ut.ce.awtr.source.RawTransitionSystem;

/**
 * Test-only bridge from a discrete-time timed automaton to the explicit TTS the
 * approved algorithm consumes.
 *
 * <p>A state of the TTS is a location together with a clock valuation. Time
 * advances one unit at a time, and is blocked when the resulting valuation would
 * break the location's invariant. Clock values are capped one above the largest
 * constant any constraint mentions: beyond that no guard or invariant can
 * distinguish two values, so capping keeps the unfolding finite without
 * changing which moves are enabled.
 *
 * <p>This deliberately mirrors the reading of "discrete time" the pseudocode
 * uses — delays are whole units — rather than introducing any zone or region
 * machinery.
 */
final class TimedAutomatonUnfolder {

    /** Actions are attributed to a synthetic owner so hiding works the usual way. */
    static final String OWNER = "ta";

    private TimedAutomatonUnfolder() {
    }

    static RawTransitionSystem unfold(TimedAutomaton automaton) {
        int cap = automaton.maximumConstant() + 1;
        List<String> clocks = automaton.clocks();

        Map<String, List<Integer>> valuations = new LinkedHashMap<>();
        List<Integer> zeros = new ArrayList<>();
        clocks.forEach(clock -> zeros.add(0));

        String initial = encode(automaton.initialLocation(), zeros);
        if (!satisfies(automaton.invariant(automaton.initialLocation()), clocks, zeros)) {
            throw new IllegalStateException(
                    automaton.id() + ": initial valuation violates the initial invariant");
        }

        Set<String> discovered = new LinkedHashSet<>();
        List<String[]> edges = new ArrayList<>();
        List<int[]> delayEdges = new ArrayList<>();
        Deque<String> pending = new ArrayDeque<>();
        Map<String, String> locationOf = new LinkedHashMap<>();

        discovered.add(initial);
        valuations.put(initial, zeros);
        locationOf.put(initial, automaton.initialLocation());
        pending.add(initial);

        List<String> order = new ArrayList<>();
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            order.add(current);
            String location = locationOf.get(current);
            List<Integer> valuation = valuations.get(current);

            // Action edges.
            for (TimedAutomaton.Edge edge : automaton.edges()) {
                if (!edge.source().equals(location)
                        || !satisfies(edge.guard(), clocks, valuation)) {
                    continue;
                }
                List<Integer> next = new ArrayList<>(valuation);
                for (int i = 0; i < clocks.size(); i++) {
                    if (edge.resets().contains(clocks.get(i))) {
                        next.set(i, 0);
                    }
                }
                if (!satisfies(automaton.invariant(edge.target()), clocks, next)) {
                    continue;
                }
                String target = encode(edge.target(), next);
                if (discovered.add(target)) {
                    valuations.put(target, next);
                    locationOf.put(target, edge.target());
                    pending.add(target);
                }
                edges.add(new String[] {current, target, edge.action()});
            }

            // One unit of time progress.
            List<Integer> advanced = new ArrayList<>(valuation);
            for (int i = 0; i < advanced.size(); i++) {
                advanced.set(i, Math.min(advanced.get(i) + 1, cap));
            }
            if (satisfies(automaton.invariant(location), clocks, advanced)) {
                String target = encode(location, advanced);
                if (discovered.add(target)) {
                    valuations.put(target, advanced);
                    locationOf.put(target, location);
                    pending.add(target);
                }
                delayEdges.add(new int[] {order.size() - 1, 0});
                edges.add(new String[] {current, target, null});
            }
        }

        RawTransitionSystem.Builder builder = RawTransitionSystem.builder(automaton.id());
        order.forEach(state -> builder.state(state, ""));
        builder.initialState(initial);
        for (String[] edge : edges) {
            if (edge[2] == null) {
                builder.transition(RawTransition.time(edge[0], edge[1], 1, 0, 0));
            } else {
                builder.transition(RawTransition.message(edge[0], edge[1],
                        new ActionIdentity("", OWNER, edge[2]), 0, 0));
            }
        }
        return builder.build();
    }

    private static boolean satisfies(List<TimedAutomaton.Constraint> constraints,
                                     List<String> clocks, List<Integer> valuation) {
        if (constraints.isEmpty()) {
            return true;
        }
        Map<String, Integer> named = new LinkedHashMap<>();
        for (int i = 0; i < clocks.size(); i++) {
            named.put(clocks.get(i), valuation.get(i));
        }
        return constraints.stream().allMatch(c -> c.holds(named));
    }

    private static String encode(String location, List<Integer> valuation) {
        return valuation.isEmpty() ? location : location + "|" + valuation;
    }
}
