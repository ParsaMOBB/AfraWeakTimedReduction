package ir.ut.ce.awtr.weak;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import ir.ut.ce.awtr.tts.Label;
import ir.ut.ce.awtr.tts.Transition;
import ir.ut.ce.awtr.tts.TransitionSystem;

/**
 * The weak transition relation of a system: tau closure, and, for every
 * observable label and every delay label, the set of states reachable by
 * {@code tau* l tau*}.
 *
 * <p>The empty move {@code tau*} is carried as its own label. It is the
 * {@code d = 0} case of the weak delay relation, and omitting it would make the
 * refinement blind to a silent branch into a state with strictly fewer
 * capabilities — a tau step into a deadlock would go unnoticed.
 *
 * <p>States are indexed once and target sets are {@link BitSet}s, so the
 * refinement inner loop is bit-parallel and allocation free.
 */
public final class WeakTransitionRelation {

    /** The label under which {@code tau*} itself is refined. */
    public static final Label EMPTY_MOVE = null;

    private final TransitionSystem system;
    private final List<String> states;
    private final Map<String, Integer> index;
    private final List<Label> labels;
    private final BitSet[] tauClosure;
    private final BitSet[][] weak;

    private WeakTransitionRelation(TransitionSystem system, List<String> states,
                                   Map<String, Integer> index, List<Label> labels,
                                   BitSet[] tauClosure, BitSet[][] weak) {
        this.system = system;
        this.states = List.copyOf(states);
        this.index = Map.copyOf(index);
        this.labels = List.copyOf(labels);
        this.tauClosure = tauClosure;
        this.weak = weak;
    }

    public static WeakTransitionRelation of(TransitionSystem system) {
        Objects.requireNonNull(system, "system");
        List<String> states = system.states();
        int n = states.size();
        Map<String, Integer> index = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            index.put(states.get(i), i);
        }

        // 1. tau closure, one depth-first sweep per state.
        List<List<Integer>> tauSuccessors = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tauSuccessors.add(new ArrayList<>());
        }
        for (Transition t : system.transitions()) {
            if (t.label().isTau()) {
                tauSuccessors.get(index.get(t.source())).add(index.get(t.target()));
            }
        }
        BitSet[] closure = new BitSet[n];
        for (int i = 0; i < n; i++) {
            BitSet reached = new BitSet(n);
            reached.set(i);
            Deque<Integer> stack = new ArrayDeque<>();
            stack.push(i);
            while (!stack.isEmpty()) {
                for (int next : tauSuccessors.get(stack.pop())) {
                    if (!reached.get(next)) {
                        reached.set(next);
                        stack.push(next);
                    }
                }
            }
            closure[i] = reached;
        }

        // 2. non-tau labels, in a deterministic order.
        List<Label> labels = new ArrayList<>(new TreeSet<>(
                system.alphabet().stream().filter(l -> !l.isTau()).toList()));

        // 3. weak steps: tau* l tau*.
        Map<Label, List<int[]>> byLabel = new HashMap<>();
        for (Transition t : system.transitions()) {
            if (!t.label().isTau()) {
                byLabel.computeIfAbsent(t.label(), k -> new ArrayList<>())
                        .add(new int[] {index.get(t.source()), index.get(t.target())});
            }
        }
        BitSet[][] weak = new BitSet[labels.size()][n];
        for (int l = 0; l < labels.size(); l++) {
            List<int[]> edges = byLabel.getOrDefault(labels.get(l), List.of());
            // target set of a single edge, tau-closed, cached per source state
            BitSet[] afterEdge = new BitSet[n];
            for (int[] edge : edges) {
                BitSet acc = afterEdge[edge[0]];
                if (acc == null) {
                    acc = new BitSet(n);
                    afterEdge[edge[0]] = acc;
                }
                acc.or(closure[edge[1]]);
            }
            for (int s = 0; s < n; s++) {
                BitSet result = new BitSet(n);
                for (int x = closure[s].nextSetBit(0); x >= 0; x = closure[s].nextSetBit(x + 1)) {
                    if (afterEdge[x] != null) {
                        result.or(afterEdge[x]);
                    }
                }
                weak[l][s] = result;
            }
        }
        return new WeakTransitionRelation(system, states, index, labels, closure, weak);
    }

    public TransitionSystem system() {
        return system;
    }

    public List<String> states() {
        return states;
    }

    public int stateCount() {
        return states.size();
    }

    public int indexOf(String state) {
        Integer i = index.get(state);
        if (i == null) {
            throw new IllegalArgumentException("unknown state: " + state);
        }
        return i;
    }

    public String stateAt(int i) {
        return states.get(i);
    }

    /** The non-tau labels, in refinement order. */
    public List<Label> labels() {
        return labels;
    }

    /** {@code tau*}: states reachable from {@code s} by silent steps, including {@code s}. */
    public BitSet tauClosure(int state) {
        return tauClosure[state];
    }

    /** {@code tau* l tau*} targets of {@code s} for {@code labels().get(labelIndex)}. */
    public BitSet weakTargets(int labelIndex, int state) {
        return weak[labelIndex][state];
    }

    /** Total number of weak (state, label, target) triples; reported as a statistic. */
    public long weakTransitionCount() {
        long total = 0;
        for (BitSet[] perLabel : weak) {
            for (BitSet targets : perLabel) {
                total += targets.cardinality();
            }
        }
        return total;
    }
}
