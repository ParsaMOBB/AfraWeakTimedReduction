package ir.ut.ce.awtr.quotient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import ir.ut.ce.awtr.tts.Label;
import ir.ut.ce.awtr.tts.Transition;
import ir.ut.ce.awtr.tts.TransitionSystem;
import ir.ut.ce.awtr.weak.Partition;
import ir.ut.ce.awtr.weak.WeakTransitionRelation;

/**
 * The quotient built over the <em>weak</em> transition relation rather than the
 * edges the model happens to be written with: one state per equivalence class,
 * and an edge {@code B --l--> B'} whenever the members of {@code B} can
 * <em>weakly</em> do {@code l} into {@code B'}, plus an edge
 * {@code B --tau--> B'} whenever they can silently become a member of
 * {@code B'}.
 *
 * <p>This form exists for one reason: it is canonical, and
 * {@link QuotientBuilder}'s form is not. Which edges the raw quotient carries
 * depends on which silent steps the input happened to spell out — a model may
 * contain a tau edge that its weak closure already implies, and dropping it
 * changes nothing about the behaviour but removes an edge from the quotient. A
 * saturated edge, by contrast, is a property of the classes alone: if
 * {@code s ∈ B} can weakly do {@code l} into {@code B'}, then so can every
 * other member of {@code B}, in this model and in any weak timed bisimilar one.
 * Two systems are therefore weak timed bisimilar exactly when their saturated
 * quotients are isomorphic, which is what
 * {@code docs/experiments/quotient-isomorphism.md} sets out to establish.
 *
 * <p>Only classes reachable from the initial class are kept, because that is
 * the part the initial state's behaviour can depend on.
 */
public final class SaturatedQuotient {

    private static final String STATE_PREFIX = "q";

    private SaturatedQuotient() {
    }

    /**
     * @param relation  the weak transition relation the partition was computed from
     * @param partition the weak timed bisimilarity classes over the same states
     */
    public static TransitionSystem build(WeakTransitionRelation relation, Partition partition) {
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(partition, "partition");

        int blockCount = partition.blockCount();
        List<Set<Edge>> outgoing = new ArrayList<>(blockCount);
        for (int block = 0; block < blockCount; block++) {
            outgoing.add(edgesOf(relation, partition, block));
        }

        int initialBlock = partition.blockOf(relation.system().initialState());
        List<Integer> reachable = reachableFrom(initialBlock, outgoing);

        Map<Integer, String> name = new LinkedHashMap<>();
        for (int block : reachable) {
            name.put(block, STATE_PREFIX + name.size());
        }

        TransitionSystem.Builder builder =
                TransitionSystem.builder(relation.system().id() + "/weak-timed-saturated")
                        .initialState(name.get(initialBlock));
        name.values().forEach(builder::stateIfAbsent);

        List<Transition> edges = new ArrayList<>();
        for (int block : name.keySet()) {
            for (Edge edge : outgoing.get(block)) {
                edges.add(new Transition(name.get(block), edge.label(), name.get(edge.target())));
            }
        }
        Collections.sort(edges);
        edges.forEach(builder::transition);
        return builder.build();
    }

    /**
     * The weak moves of one class, read off any one of its members.
     *
     * <p>Reading off a single representative is exactly what stability of the
     * partition licenses; {@link #stabilityViolations} checks that licence
     * directly and is used by the tests rather than trusted here.
     */
    private static Set<Edge> edgesOf(WeakTransitionRelation relation, Partition partition,
                                     int block) {
        int representative = relation.indexOf(partition.block(block).first());
        Set<Edge> edges = new LinkedHashSet<>();
        for (int l = 0; l < relation.labels().size(); l++) {
            Label label = relation.labels().get(l);
            for (int target : blocksOf(relation.weakTargets(l, representative), relation, partition)) {
                edges.add(new Edge(label, target));
            }
        }
        // tau* is the empty move. A class can always become itself, so only a
        // silent step that leaves the class carries information.
        for (int target : blocksOf(relation.tauClosure(representative), relation, partition)) {
            if (target != block) {
                edges.add(new Edge(Label.TAU, target));
            }
        }
        return edges;
    }

    private static List<Integer> blocksOf(BitSet targets, WeakTransitionRelation relation,
                                          Partition partition) {
        Set<Integer> blocks = new TreeSet<>();
        for (int t = targets.nextSetBit(0); t >= 0; t = targets.nextSetBit(t + 1)) {
            blocks.add(partition.blockOf(relation.stateAt(t)));
        }
        return new ArrayList<>(blocks);
    }

    private static List<Integer> reachableFrom(int initialBlock, List<Set<Edge>> outgoing) {
        List<Integer> order = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(initialBlock);
        seen.add(initialBlock);
        while (!queue.isEmpty()) {
            int block = queue.removeFirst();
            order.add(block);
            for (Edge edge : outgoing.get(block)) {
                if (seen.add(edge.target())) {
                    queue.addLast(edge.target());
                }
            }
        }
        Collections.sort(order);
        return order;
    }

    /**
     * Reports every class whose members disagree about which classes they can
     * weakly reach. A stable partition has none, by definition; a hit here means
     * the partition was not the one the refinement computed, or the refinement
     * is wrong.
     */
    public static List<String> stabilityViolations(WeakTransitionRelation relation,
                                                  Partition partition) {
        List<String> violations = new ArrayList<>();
        for (int block = 0; block < partition.blockCount(); block++) {
            Set<Edge> expected = edgesOf(relation, partition, block);
            for (String member : partition.block(block)) {
                Set<Edge> actual = memberEdges(relation, partition, block, relation.indexOf(member));
                if (!actual.equals(expected)) {
                    violations.add("state '" + member + "' in block " + block
                            + " has weak moves " + actual + " but the block's representative has "
                            + expected);
                }
            }
        }
        return violations;
    }

    private static Set<Edge> memberEdges(WeakTransitionRelation relation, Partition partition,
                                         int block, int state) {
        Set<Edge> edges = new LinkedHashSet<>();
        for (int l = 0; l < relation.labels().size(); l++) {
            for (int target : blocksOf(relation.weakTargets(l, state), relation, partition)) {
                edges.add(new Edge(relation.labels().get(l), target));
            }
        }
        for (int target : blocksOf(relation.tauClosure(state), relation, partition)) {
            if (target != block) {
                edges.add(new Edge(Label.TAU, target));
            }
        }
        return edges;
    }

    private record Edge(Label label, int target) {
    }
}
