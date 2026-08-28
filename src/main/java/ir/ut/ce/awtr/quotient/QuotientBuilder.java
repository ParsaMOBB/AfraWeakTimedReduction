package ir.ut.ce.awtr.quotient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;

import ir.ut.ce.awtr.tts.Label;
import ir.ut.ce.awtr.tts.Transition;
import ir.ut.ce.awtr.tts.TransitionSystem;
import ir.ut.ce.awtr.weak.Partition;
import ir.ut.ce.awtr.weak.UnitDelayRefinement;

/**
 * Builds the quotient transition system: one state per equivalence class, with
 * an edge {@code B --l--> B'} whenever some member of {@code B} has an
 * {@code l}-edge into {@code B'}.
 *
 * <p>After a unit-delay refinement the raw quotient speaks only in single time
 * units, which is faithful but unreadable — a ten-unit wait becomes nine
 * intermediate classes, and the "reduced" model comes out larger than the one
 * it reduced. A second pass splices back any maximal chain of classes that
 * exist purely to carry time, replacing it with one edge whose duration is the
 * sum. A class qualifies only when the sole way in and the sole way out are
 * delay edges, so no choice and no observable step is ever merged away.
 *
 * <p>Splicing records where each removed class sat in its chain. Re-expanding
 * the spliced quotient therefore reproduces the raw one exactly, and that
 * correspondence ({@link Quotient#expandedStateOfClass()}) is what lets the
 * independent verifier check the delivered artefact rather than an intermediate
 * form of it.
 */
public final class QuotientBuilder {

    private static final String STATE_PREFIX = "q";

    private QuotientBuilder() {
    }

    /**
     * @param refined     the system that was refined (possibly unit-delay split)
     * @param partition   its weak timed bisimilarity classes
     * @param inputStates the states the user's model declared, in input order
     * @param isSynthetic true for states the unit-delay refinement introduced
     */
    public static Quotient build(TransitionSystem refined,
                                 Partition partition,
                                 List<String> inputStates,
                                 Predicate<String> isSynthetic) {
        Objects.requireNonNull(refined, "refined");
        Objects.requireNonNull(partition, "partition");

        int blockCount = partition.blockCount();
        List<Set<Edge>> outgoing = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            outgoing.add(new LinkedHashSet<>());
        }
        for (Transition t : refined.transitions()) {
            int from = partition.blockOf(t.source());
            int to = partition.blockOf(t.target());
            // A silent step inside a class is invisible by construction: the tau
            // closure of a state already contains that state, so a tau self-loop
            // adds nothing to any weak move. Dropping it keeps the reduced model
            // readable without changing what it means.
            if (t.label().isTau() && from == to) {
                continue;
            }
            outgoing.get(from).add(new Edge(t.label(), to));
        }

        // A class may be removed when it is nothing but an instant part way
        // through a wait: time in, time out, and no choice at either end. That
        // is a structural property of the class, deliberately not a property of
        // which states happen to sit in it — if it depended on whether the input
        // declared a state mid-wait, two bisimilar models would reduce to
        // different-sized quotients purely because one of them names an instant
        // the other passes through silently.
        int initialBlock = partition.blockOf(refined.initialState());
        Splicer splicer = new Splicer(outgoing, initialBlock);
        splicer.run();

        Map<Integer, String> name = new LinkedHashMap<>();
        for (int i = 0; i < blockCount; i++) {
            if (splicer.alive[i]) {
                name.put(i, STATE_PREFIX + name.size());
            }
        }

        TransitionSystem.Builder builder = TransitionSystem.builder(refined.id() + "/weak-timed")
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

        // Where every class - spliced or not - lives once the delivered quotient
        // is expanded back to unit delays.
        Map<Integer, String> expanded = new LinkedHashMap<>();
        name.forEach(expanded::put);
        splicer.chains.forEach(chain -> {
            String from = name.get(chain.start());
            String to = name.get(chain.end());
            for (int i = 0; i < chain.members().size(); i++) {
                expanded.put(chain.members().get(i),
                        UnitDelayRefinement.intermediateName(
                                from, chain.totalUnits(), to, chain.offsets().get(i)));
            }
        });

        // A declared state whose class was spliced has no quotient state of its
        // own; it lives on a quotient delay edge instead. Its class is still in
        // the partition, and expandedStateOfRefined still names where it sits,
        // so nothing about it is lost.
        Map<String, String> stateOfInput = new LinkedHashMap<>();
        Map<String, SortedSet<String>> members = new LinkedHashMap<>();
        name.values().forEach(q -> members.put(q, new TreeSet<>()));
        for (String state : inputStates) {
            if (!partition.contains(state)) {
                continue;
            }
            String quotientState = name.get(partition.blockOf(state));
            if (quotientState != null) {
                stateOfInput.put(state, quotientState);
                members.get(quotientState).add(state);
            }
        }
        Map<String, SortedSet<String>> frozenMembers = new LinkedHashMap<>();
        members.forEach((q, m) -> frozenMembers.put(q, Collections.unmodifiableSortedSet(m)));

        Map<String, String> expandedByState = new LinkedHashMap<>();
        for (String state : refined.states()) {
            expandedByState.put(state, expanded.get(partition.blockOf(state)));
        }

        Map<Integer, String> quotientStateOfClass = new LinkedHashMap<>(name);
        return new Quotient(builder.build(), partition, stateOfInput, frozenMembers,
                expandedByState, quotientStateOfClass, splicer.chains.size(),
                countSynthetic(partition, isSynthetic));
    }

    private static int countSynthetic(Partition partition, Predicate<String> isSynthetic) {
        int count = 0;
        for (int i = 0; i < partition.blockCount(); i++) {
            if (partition.block(i).stream().allMatch(isSynthetic)) {
                count++;
            }
        }
        return count;
    }

    private record Edge(Label label, int target) {
    }

    /** One maximal run of time-only classes that was collapsed into a single edge. */
    private record Chain(int start, int end, List<Integer> members,
                         List<Integer> offsets, int totalUnits) {
    }

    private static final class Splicer {
        private final List<Set<Edge>> outgoing;
        private final int initialBlock;
        private final boolean[] alive;
        private final boolean[] removable;
        private final List<Chain> chains = new ArrayList<>();

        Splicer(List<Set<Edge>> outgoing, int initialBlock) {
            this.outgoing = outgoing;
            this.initialBlock = initialBlock;
            this.alive = new boolean[outgoing.size()];
            this.removable = new boolean[outgoing.size()];
            java.util.Arrays.fill(alive, true);
        }

        void run() {
            markRemovable();
            for (int start = 0; start < outgoing.size(); start++) {
                if (removable[start]) {
                    continue;   // start belongs to somebody else's chain
                }
                for (Edge first : new ArrayList<>(outgoing.get(start))) {
                    if (first.label().isDelay() && removable[first.target()]) {
                        collapseFrom(start, first);
                    }
                }
            }
        }

        /**
         * A class is removable when the only way in is one delay edge, the only
         * way out is one delay edge, and it is not the initial class. Anything
         * else — a choice, a visible action, a second predecessor — makes the
         * instant observable, and merging it away would change the model.
         */
        private void markRemovable() {
            int size = outgoing.size();
            int[] inDegree = new int[size];
            boolean[] allInboundAreDelays = new boolean[size];
            java.util.Arrays.fill(allInboundAreDelays, true);
            for (Set<Edge> edges : outgoing) {
                for (Edge edge : edges) {
                    inDegree[edge.target()]++;
                    if (!edge.label().isDelay()) {
                        allInboundAreDelays[edge.target()] = false;
                    }
                }
            }
            for (int block = 0; block < size; block++) {
                Set<Edge> out = outgoing.get(block);
                removable[block] = block != initialBlock
                        && inDegree[block] == 1
                        && allInboundAreDelays[block]
                        && out.size() == 1
                        && out.iterator().next().label().isDelay()
                        && out.iterator().next().target() != block;
            }
        }

        /** Walks the maximal chain of removable classes leaving {@code start}. */
        private void collapseFrom(int start, Edge first) {
            List<Integer> members = new ArrayList<>();
            List<Integer> offsets = new ArrayList<>();
            int total = first.label().delayUnits();
            int current = first.target();

            while (removable[current] && alive[current]) {
                Edge next = outgoing.get(current).iterator().next();
                members.add(current);
                offsets.add(total);
                total += next.label().delayUnits();
                current = next.target();
            }
            if (members.isEmpty()) {
                return;
            }
            members.forEach(block -> {
                alive[block] = false;
                outgoing.get(block).clear();
            });
            outgoing.get(start).remove(first);
            outgoing.get(start).add(new Edge(Label.delay(total), current));
            chains.add(new Chain(start, current, members, offsets, total));
        }
    }
}
