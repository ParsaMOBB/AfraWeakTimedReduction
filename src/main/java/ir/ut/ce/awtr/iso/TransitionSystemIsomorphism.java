package ir.ut.ce.awtr.iso;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import ir.ut.ce.awtr.tts.Label;
import ir.ut.ce.awtr.tts.Transition;
import ir.ut.ce.awtr.tts.TransitionSystem;

/**
 * Decides whether two transition systems are the same graph under a renaming of
 * states: a bijection that preserves the initial state and the labelled edge
 * relation in both directions.
 *
 * <p>This is a structural question, not a behavioural one. It exists because of
 * the reduce-then-compare experiment: if the reduced form of a model is
 * canonical, then two models are weak timed bisimilar exactly when their
 * reduced forms are isomorphic, and no relation between the two state spaces
 * has to be computed. See {@code docs/experiments/quotient-isomorphism.md}.
 *
 * <p>Graph isomorphism has no known polynomial algorithm, so the decision here
 * is a search, made cheap by a sound filter:
 *
 * <ol>
 *   <li><b>Invariants.</b> State count, transition count and the multiset of
 *       labels must agree, or nothing else needs to run.</li>
 *   <li><b>Colour refinement</b> (the one-dimensional Weisfeiler-Leman
 *       algorithm) over the disjoint union of the two systems. A state's colour
 *       starts as "is it the initial state", and is repeatedly replaced by the
 *       colour together with the multiset of {@code (label, colour)} pairs on
 *       its outgoing and incoming edges, until the number of colours stops
 *       growing. Any isomorphism must preserve these colours, because the
 *       recursion only ever looks at structure. So if some colour holds a
 *       different number of states on each side, the answer is no.
 *       <p>This is the sound version of the hashing idea that works on trees
 *       and DAGs: a cyclic graph has no bottom to start a hash from, but the
 *       fixed point of the colour recursion is still well defined — it just is
 *       not a complete invariant, so it can only ever prove "no".</li>
 *   <li><b>Backtracking</b> over the surviving candidates, most constrained
 *       state first, rejecting a partial map as soon as one edge among the
 *       states mapped so far fails to correspond.</li>
 * </ol>
 *
 * <p>The mapping the search returns is checked once more, independently, by
 * {@link #violations(TransitionSystem, TransitionSystem, Map)}, which knows
 * nothing about colours or search order: a positive answer is only reported
 * when a full edge-by-edge comparison of the delivered bijection passes.
 *
 * <p>On the inputs this was written for the search never branches. Colour
 * refinement on a labelled graph converges to a partition at least as fine as
 * strong bisimilarity, and a reduced model has no two strongly bisimilar
 * states, so every colour class is a single state and the bijection is forced.
 * The search is the safety net that keeps the answer correct when that
 * reasoning does not apply.
 */
public final class TransitionSystemIsomorphism {

    /** Candidate assignments the search may try before giving up. */
    public static final long DEFAULT_SEARCH_BUDGET = 20_000_000L;

    public enum Outcome {
        ISOMORPHIC,
        NOT_ISOMORPHIC,
        /** The search budget ran out; neither answer was established. */
        UNDETERMINED
    }

    /**
     * @param mapping       left state to right state; empty unless isomorphic
     * @param reason        why the answer is negative or undetermined; empty otherwise
     * @param colourRounds  refinement rounds until the colouring was stable
     * @param colourClasses colours in the stable colouring, over both systems
     * @param searchNodes   candidate assignments the backtracking search tried
     */
    public record Result(Outcome outcome,
                         Map<String, String> mapping,
                         String reason,
                         int colourRounds,
                         int colourClasses,
                         long searchNodes) {

        public Result {
            Objects.requireNonNull(outcome, "outcome");
            mapping = mapping == null ? Map.of() : Map.copyOf(mapping);
            reason = reason == null ? "" : reason;
        }

        public boolean isomorphic() {
            return outcome == Outcome.ISOMORPHIC;
        }

        public boolean conclusive() {
            return outcome != Outcome.UNDETERMINED;
        }

        /** True when the search fixed every state without ever undoing a choice. */
        public boolean forced() {
            return searchNodes <= mapping.size();
        }
    }

    private final TransitionSystem left;
    private final TransitionSystem right;
    private final long budget;

    private int n;
    private List<String> leftStates;
    private List<String> rightStates;
    private Map<Label, Integer> labelIndex;
    private int labelCount;

    /** Combined node space: {@code 0..n-1} is the left system, {@code n..2n-1} the right. */
    private int[][] outLabel;
    private int[][] outTarget;
    private int[][] inLabel;
    private int[][] inSource;
    private Set<Long> edgeKeys;

    private int[] colour;
    private int colourClasses;
    private int rounds;

    private int[][] candidates;
    private int[] leftToRight;
    private int[] rightToLeft;
    private long searchNodes;

    private TransitionSystemIsomorphism(TransitionSystem left, TransitionSystem right, long budget) {
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
        this.budget = budget;
    }

    public static Result check(TransitionSystem left, TransitionSystem right) {
        return check(left, right, DEFAULT_SEARCH_BUDGET);
    }

    public static Result check(TransitionSystem left, TransitionSystem right, long searchBudget) {
        return new TransitionSystemIsomorphism(left, right, searchBudget).run();
    }

    private Result run() {
        if (left.stateCount() != right.stateCount()) {
            return negative("state counts differ: " + left.stateCount()
                    + " on the left, " + right.stateCount() + " on the right");
        }
        if (left.transitionCount() != right.transitionCount()) {
            return negative("transition counts differ: " + left.transitionCount()
                    + " on the left, " + right.transitionCount() + " on the right");
        }
        String labelMismatch = labelMismatch();
        if (labelMismatch != null) {
            return negative(labelMismatch);
        }

        index();
        refineColours();
        String colourMismatch = colourMismatch();
        if (colourMismatch != null) {
            return negative(colourMismatch);
        }

        collectCandidates();
        try {
            if (!search()) {
                return negative("no label-preserving bijection exists; the search"
                        + " exhausted every colour-respecting candidate");
            }
        } catch (BudgetExceeded e) {
            return new Result(Outcome.UNDETERMINED, Map.of(),
                    "search budget of " + budget + " candidate assignments ran out",
                    rounds, colourClasses, searchNodes);
        }

        Map<String, String> mapping = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            mapping.put(leftStates.get(i), rightStates.get(leftToRight[i]));
        }
        List<String> violations = violations(left, right, mapping);
        if (!violations.isEmpty()) {
            // Unreachable unless the search is wrong; reported rather than
            // trusted, because the whole point of the second check is that it
            // does not share any reasoning with the first.
            return new Result(Outcome.NOT_ISOMORPHIC, Map.of(),
                    "the search produced a mapping the independent check rejected: "
                            + String.join("; ", violations),
                    rounds, colourClasses, searchNodes);
        }
        return new Result(Outcome.ISOMORPHIC, mapping, "", rounds, colourClasses, searchNodes);
    }

    private Result negative(String reason) {
        return new Result(Outcome.NOT_ISOMORPHIC, Map.of(), reason, rounds, colourClasses, searchNodes);
    }

    private String labelMismatch() {
        Map<Label, Integer> leftHistogram = histogram(left);
        Map<Label, Integer> rightHistogram = histogram(right);
        if (leftHistogram.equals(rightHistogram)) {
            return null;
        }
        Set<Label> all = new TreeSet<>(leftHistogram.keySet());
        all.addAll(rightHistogram.keySet());
        List<String> differences = new ArrayList<>();
        for (Label label : all) {
            int a = leftHistogram.getOrDefault(label, 0);
            int b = rightHistogram.getOrDefault(label, 0);
            if (a != b) {
                differences.add(label + ": " + a + " vs " + b);
            }
        }
        return "label multisets differ (" + String.join(", ", differences) + ")";
    }

    private static Map<Label, Integer> histogram(TransitionSystem system) {
        Map<Label, Integer> counts = new TreeMap<>();
        for (Transition t : system.transitions()) {
            counts.merge(t.label(), 1, Integer::sum);
        }
        return counts;
    }

    private void index() {
        leftStates = left.states();
        rightStates = right.states();
        n = leftStates.size();

        Set<Label> alphabet = new TreeSet<>(left.alphabet());
        alphabet.addAll(right.alphabet());
        labelIndex = new HashMap<>();
        for (Label label : alphabet) {
            labelIndex.put(label, labelIndex.size());
        }
        labelCount = Math.max(1, labelIndex.size());

        Map<String, Integer> leftIndex = indexOf(leftStates);
        Map<String, Integer> rightIndex = indexOf(rightStates);

        List<List<int[]>> out = new ArrayList<>();
        List<List<int[]>> in = new ArrayList<>();
        for (int i = 0; i < 2 * n; i++) {
            out.add(new ArrayList<>());
            in.add(new ArrayList<>());
        }
        edgeKeys = new HashSet<>();
        addEdges(left, leftIndex, 0, out, in);
        addEdges(right, rightIndex, n, out, in);

        outLabel = new int[2 * n][];
        outTarget = new int[2 * n][];
        inLabel = new int[2 * n][];
        inSource = new int[2 * n][];
        for (int i = 0; i < 2 * n; i++) {
            outLabel[i] = new int[out.get(i).size()];
            outTarget[i] = new int[out.get(i).size()];
            for (int k = 0; k < out.get(i).size(); k++) {
                outLabel[i][k] = out.get(i).get(k)[0];
                outTarget[i][k] = out.get(i).get(k)[1];
            }
            inLabel[i] = new int[in.get(i).size()];
            inSource[i] = new int[in.get(i).size()];
            for (int k = 0; k < in.get(i).size(); k++) {
                inLabel[i][k] = in.get(i).get(k)[0];
                inSource[i][k] = in.get(i).get(k)[1];
            }
        }
    }

    private static Map<String, Integer> indexOf(List<String> states) {
        Map<String, Integer> index = new HashMap<>(states.size() * 2);
        for (int i = 0; i < states.size(); i++) {
            index.put(states.get(i), i);
        }
        return index;
    }

    private void addEdges(TransitionSystem system, Map<String, Integer> index, int offset,
                          List<List<int[]>> out, List<List<int[]>> in) {
        for (Transition t : system.transitions()) {
            int source = offset + index.get(t.source());
            int target = offset + index.get(t.target());
            int label = labelIndex.get(t.label());
            out.get(source).add(new int[] {label, target});
            in.get(target).add(new int[] {label, source});
            edgeKeys.add(edgeKey(source, label, target));
        }
    }

    private long edgeKey(int source, int label, int target) {
        return ((long) source * (2L * n) + target) * labelCount + label;
    }

    /**
     * Repeatedly replaces every colour by what the state's neighbourhood looks
     * like in the current colouring, until the number of colours is stable.
     * Both systems are coloured together so the colours are comparable.
     */
    private void refineColours() {
        colour = new int[2 * n];
        Arrays.fill(colour, 1);
        colour[left.states().indexOf(left.initialState())] = 0;
        colour[n + right.states().indexOf(right.initialState())] = 0;
        colourClasses = (int) Arrays.stream(colour).distinct().count();

        while (true) {
            rounds++;
            Map<Signature, Integer> ids = new LinkedHashMap<>();
            int[] next = new int[2 * n];
            for (int i = 0; i < 2 * n; i++) {
                Signature signature = new Signature(colour[i],
                        neighbourhood(outLabel[i], outTarget[i]),
                        neighbourhood(inLabel[i], inSource[i]));
                Integer id = ids.get(signature);
                if (id == null) {
                    id = ids.size();
                    ids.put(signature, id);
                }
                next[i] = id;
            }
            if (ids.size() == colourClasses) {
                return;     // stable: the classes did not change, only their names might have
            }
            colourClasses = ids.size();
            colour = next;
        }
    }

    /** The sorted multiset of {@code (label, colour)} pairs on one side of a state. */
    private long[] neighbourhood(int[] labels, int[] others) {
        long[] pairs = new long[labels.length];
        for (int k = 0; k < labels.length; k++) {
            pairs[k] = ((long) labels[k] << 32) | colour[others[k]];
        }
        Arrays.sort(pairs);
        return pairs;
    }

    private String colourMismatch() {
        int[] leftCount = new int[colourClasses];
        int[] rightCount = new int[colourClasses];
        for (int i = 0; i < n; i++) {
            leftCount[colour[i]]++;
        }
        for (int i = n; i < 2 * n; i++) {
            rightCount[colour[i]]++;
        }
        for (int c = 0; c < colourClasses; c++) {
            if (leftCount[c] != rightCount[c]) {
                return "no bijection can respect the structure: " + leftCount[c]
                        + " state(s) on the left and " + rightCount[c]
                        + " on the right have the same neighbourhood structure ("
                        + example(c) + ")";
            }
        }
        return null;
    }

    /** Names one state of a colour, from whichever side has one, for the message. */
    private String example(int colourValue) {
        for (int i = 0; i < n; i++) {
            if (colour[i] == colourValue) {
                return "for example left state '" + leftStates.get(i) + "'";
            }
        }
        for (int i = n; i < 2 * n; i++) {
            if (colour[i] == colourValue) {
                return "for example right state '" + rightStates.get(i - n) + "'";
            }
        }
        return "an empty class";
    }

    private void collectCandidates() {
        List<List<Integer>> byColour = new ArrayList<>();
        for (int c = 0; c < colourClasses; c++) {
            byColour.add(new ArrayList<>());
        }
        for (int j = 0; j < n; j++) {
            byColour.get(colour[n + j]).add(j);
        }
        candidates = new int[n][];
        for (int i = 0; i < n; i++) {
            List<Integer> possible = byColour.get(colour[i]);
            candidates[i] = possible.stream().mapToInt(Integer::intValue).toArray();
        }
        leftToRight = new int[n];
        rightToLeft = new int[n];
        Arrays.fill(leftToRight, -1);
        Arrays.fill(rightToLeft, -1);
    }

    private boolean search() {
        int u = selectNext();
        if (u < 0) {
            return true;
        }
        for (int v : candidates[u]) {
            if (rightToLeft[v] != -1) {
                continue;
            }
            if (++searchNodes > budget) {
                throw new BudgetExceeded();
            }
            if (!consistent(u, v)) {
                continue;
            }
            leftToRight[u] = v;
            rightToLeft[v] = u;
            if (search()) {
                return true;
            }
            leftToRight[u] = -1;
            rightToLeft[v] = -1;
        }
        return false;
    }

    /**
     * The unmapped left state with the fewest free candidates, preferring one
     * already adjacent to the mapped part so the partial map stays connected
     * and inconsistencies show up at the shallowest possible depth.
     */
    private int selectNext() {
        int best = -1;
        int bestFree = Integer.MAX_VALUE;
        int bestAdjacency = -1;
        for (int i = 0; i < n; i++) {
            if (leftToRight[i] != -1) {
                continue;
            }
            int free = 0;
            for (int v : candidates[i]) {
                if (rightToLeft[v] == -1) {
                    free++;
                }
            }
            int adjacency = adjacencyToMapped(i);
            if (free < bestFree || (free == bestFree && adjacency > bestAdjacency)) {
                best = i;
                bestFree = free;
                bestAdjacency = adjacency;
            }
        }
        return best;
    }

    private int adjacencyToMapped(int node) {
        int count = 0;
        for (int target : outTarget[node]) {
            if (leftToRight[target] != -1) {
                count++;
            }
        }
        for (int source : inSource[node]) {
            if (leftToRight[source] != -1) {
                count++;
            }
        }
        return count;
    }

    /**
     * Would mapping {@code u} to {@code v} contradict the edges already fixed?
     * Every edge between {@code u} and an already mapped state must have a
     * counterpart between {@code v} and that state's image, and conversely.
     */
    private boolean consistent(int u, int v) {
        int vNode = n + v;
        for (int k = 0; k < outTarget[u].length; k++) {
            int image = imageOf(outTarget[u][k], u, v);
            if (image >= 0 && !edgeKeys.contains(edgeKey(vNode, outLabel[u][k], image))) {
                return false;
            }
        }
        for (int k = 0; k < inSource[u].length; k++) {
            int image = imageOf(inSource[u][k], u, v);
            if (image >= 0 && !edgeKeys.contains(edgeKey(image, inLabel[u][k], vNode))) {
                return false;
            }
        }
        for (int k = 0; k < outTarget[vNode].length; k++) {
            int preimage = preimageOf(outTarget[vNode][k], u, v);
            if (preimage >= 0 && !edgeKeys.contains(edgeKey(u, outLabel[vNode][k], preimage))) {
                return false;
            }
        }
        for (int k = 0; k < inSource[vNode].length; k++) {
            int preimage = preimageOf(inSource[vNode][k], u, v);
            if (preimage >= 0 && !edgeKeys.contains(edgeKey(preimage, inLabel[vNode][k], u))) {
                return false;
            }
        }
        return true;
    }

    /** Where a left neighbour sits on the right, or {@code -1} if not decided yet. */
    private int imageOf(int leftNode, int u, int v) {
        if (leftNode == u) {
            return n + v;
        }
        return leftToRight[leftNode] == -1 ? -1 : n + leftToRight[leftNode];
    }

    /** Where a right neighbour sits on the left, or {@code -1} if not decided yet. */
    private int preimageOf(int rightNode, int u, int v) {
        int j = rightNode - n;
        if (j == v) {
            return u;
        }
        return rightToLeft[j] == -1 ? -1 : rightToLeft[j];
    }

    /**
     * Checks a claimed isomorphism from scratch: a bijection on states that maps
     * the initial state to the initial state and carries the edge relation both
     * ways. Written to be readable rather than fast, and deliberately
     * independent of how the mapping was found.
     *
     * @return the reasons the mapping is not an isomorphism; empty if it is one
     */
    public static List<String> violations(TransitionSystem left, TransitionSystem right,
                                          Map<String, String> mapping) {
        List<String> violations = new ArrayList<>();
        Set<String> leftStates = new TreeSet<>(left.states());
        Set<String> rightStates = new TreeSet<>(right.states());

        if (!mapping.keySet().equals(leftStates)) {
            violations.add("the mapping does not cover the left states exactly");
        }
        Set<String> images = new TreeSet<>(mapping.values());
        if (images.size() != mapping.size()) {
            violations.add("the mapping is not injective");
        }
        if (!images.equals(rightStates)) {
            violations.add("the mapping is not onto the right states");
        }
        if (!violations.isEmpty()) {
            return violations;
        }
        if (!Objects.equals(mapping.get(left.initialState()), right.initialState())) {
            violations.add("the initial state '" + left.initialState() + "' maps to '"
                    + mapping.get(left.initialState()) + "' and not to the right initial state '"
                    + right.initialState() + "'");
        }

        Set<Transition> rightEdges = new HashSet<>(right.transitions());
        for (Transition t : left.transitions()) {
            Transition image = new Transition(
                    mapping.get(t.source()), t.label(), mapping.get(t.target()));
            if (!rightEdges.contains(image)) {
                violations.add("left transition " + t + " has no counterpart " + image);
            }
        }
        Map<String, String> inverse = new HashMap<>();
        mapping.forEach((from, to) -> inverse.put(to, from));
        Set<Transition> leftEdges = new HashSet<>(left.transitions());
        for (Transition t : right.transitions()) {
            Transition preimage = new Transition(
                    inverse.get(t.source()), t.label(), inverse.get(t.target()));
            if (!leftEdges.contains(preimage)) {
                violations.add("right transition " + t + " has no counterpart " + preimage);
            }
        }
        return violations;
    }

    /** A state's colour together with what its two neighbourhoods look like. */
    private record Signature(int colour, long[] out, long[] in) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Signature other
                    && colour == other.colour
                    && Arrays.equals(out, other.out)
                    && Arrays.equals(in, other.in);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * colour + Arrays.hashCode(out)) + Arrays.hashCode(in);
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExceeded() {
            super(null, null, false, false);
        }
    }
}
