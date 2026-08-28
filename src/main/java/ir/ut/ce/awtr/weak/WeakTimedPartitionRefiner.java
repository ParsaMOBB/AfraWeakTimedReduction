package ir.ut.ce.awtr.weak;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import ir.ut.ce.awtr.tts.TransitionSystem;

/**
 * Computes the weak timed bisimilarity classes of a system by stable partition
 * refinement over its weak transition relation.
 *
 * <p>Start with every state in one block; repeatedly split a block whenever two
 * of its members reach different sets of blocks under some label; stop when
 * nothing splits. The signature of a state is the set of blocks it can weakly
 * reach, per label, plus the blocks its tau closure covers — the {@code d = 0}
 * case of the weak delay relation.
 *
 * <p>The result is the coarsest stable partition, i.e. weak timed bisimilarity,
 * for the semantics baked into the supplied {@link WeakTransitionRelation}.
 */
public final class WeakTimedPartitionRefiner {

    private final WeakTransitionRelation relation;
    private int rounds;
    private int splits;

    private WeakTimedPartitionRefiner(WeakTransitionRelation relation) {
        this.relation = relation;
    }

    /** Refines {@code system} as given; delays are taken at face value. */
    public static Result refine(TransitionSystem system) {
        return new WeakTimedPartitionRefiner(WeakTransitionRelation.of(system)).run();
    }

    public static Result refine(WeakTransitionRelation relation) {
        return new WeakTimedPartitionRefiner(Objects.requireNonNull(relation, "relation")).run();
    }

    private Result run() {
        int n = relation.stateCount();
        int labelCount = relation.labels().size();
        int[] block = new int[n];
        int blockCount = 1;

        boolean changed = true;
        while (changed) {
            rounds++;
            changed = false;

            // Signature: for each label the sorted set of reachable blocks, then
            // the blocks covered by tau* itself.
            Map<Signature, Integer> newIds = new LinkedHashMap<>();
            int[] next = new int[n];
            for (int s = 0; s < n; s++) {
                int[][] perLabel = new int[labelCount + 1][];
                for (int l = 0; l < labelCount; l++) {
                    perLabel[l] = blocksOf(relation.weakTargets(l, s), block);
                }
                perLabel[labelCount] = blocksOf(relation.tauClosure(s), block);
                Signature signature = new Signature(block[s], perLabel);
                Integer id = newIds.get(signature);
                if (id == null) {
                    id = newIds.size();
                    newIds.put(signature, id);
                }
                next[s] = id;
            }
            if (newIds.size() != blockCount) {
                splits += newIds.size() - blockCount;
                blockCount = newIds.size();
                changed = true;
            }
            block = next;
        }

        List<List<String>> groups = new ArrayList<>();
        for (int i = 0; i < blockCount; i++) {
            groups.add(new ArrayList<>());
        }
        for (int s = 0; s < n; s++) {
            groups.get(block[s]).add(relation.stateAt(s));
        }
        return new Result(Partition.of(groups), relation, rounds, splits);
    }

    /** The distinct block ids covered by a target set, ascending. */
    private static int[] blocksOf(BitSet targets, int[] block) {
        TreeSet<Integer> ids = new TreeSet<>();
        for (int t = targets.nextSetBit(0); t >= 0; t = targets.nextSetBit(t + 1)) {
            ids.add(block[t]);
        }
        int[] result = new int[ids.size()];
        int i = 0;
        for (int id : ids) {
            result[i++] = id;
        }
        return result;
    }

    /**
     * A state's current block together with what it can reach. Keeping the
     * current block in the key means a round only ever splits blocks, never
     * merges two that were already separated.
     */
    private record Signature(int currentBlock, int[][] reachable) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Signature other
                    && currentBlock == other.currentBlock
                    && Arrays.deepEquals(reachable, other.reachable);
        }

        @Override
        public int hashCode() {
            return 31 * currentBlock + Arrays.deepHashCode(reachable);
        }
    }

    /** The computed partition plus the statistics the metrics report needs. */
    public record Result(Partition partition,
                         WeakTransitionRelation relation,
                         int refinementRounds,
                         int blockSplits) {

        public TransitionSystem system() {
            return relation.system();
        }

        public boolean sameClass(String a, String b) {
            return partition.sameBlock(a, b);
        }
    }
}
