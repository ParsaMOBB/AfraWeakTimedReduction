package ir.ut.ce.awtr.weak;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A partition of a state set into equivalence classes.
 *
 * <p>Blocks are numbered by the sorted state id of their smallest member, so the
 * same input always yields the same block numbering regardless of the order the
 * refinement happened to discover the blocks. Every downstream artefact — the
 * quotient's state names, the partition report, the DOT file — inherits that
 * stability.
 */
public final class Partition {

    private final List<SortedSet<String>> blocks;
    private final Map<String, Integer> blockOf;

    private Partition(List<SortedSet<String>> blocks, Map<String, Integer> blockOf) {
        this.blocks = List.copyOf(blocks);
        this.blockOf = Map.copyOf(blockOf);
    }

    /** Builds a canonically numbered partition from arbitrary groups of states. */
    public static Partition of(Iterable<? extends Iterable<String>> groups) {
        List<SortedSet<String>> sorted = new ArrayList<>();
        for (Iterable<String> group : groups) {
            SortedSet<String> block = new TreeSet<>();
            group.forEach(block::add);
            if (!block.isEmpty()) {
                sorted.add(block);
            }
        }
        sorted.sort((a, b) -> a.first().compareTo(b.first()));
        Map<String, Integer> owner = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            for (String state : sorted.get(i)) {
                Integer previous = owner.put(state, i);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "state '" + state + "' occurs in more than one block");
                }
            }
        }
        return new Partition(sorted, owner);
    }

    public int blockCount() {
        return blocks.size();
    }

    /** Members of block {@code i}, sorted. */
    public SortedSet<String> block(int i) {
        return blocks.get(i);
    }

    public List<SortedSet<String>> blocks() {
        return blocks;
    }

    /** @throws IllegalArgumentException if the state is not in this partition */
    public int blockOf(String state) {
        Integer block = blockOf.get(Objects.requireNonNull(state, "state"));
        if (block == null) {
            throw new IllegalArgumentException("state '" + state + "' is not in this partition");
        }
        return block;
    }

    public boolean contains(String state) {
        return blockOf.containsKey(state);
    }

    public boolean sameBlock(String a, String b) {
        return blockOf(a) == blockOf(b);
    }

    /** Every partitioned state, sorted. */
    public SortedSet<String> states() {
        return new TreeSet<>(blockOf.keySet());
    }

    /**
     * The partition restricted to {@code keep}. Blocks that become empty are
     * dropped and the remaining blocks are renumbered canonically.
     */
    public Partition restrictedTo(java.util.Set<String> keep) {
        List<List<String>> kept = new ArrayList<>();
        for (SortedSet<String> block : blocks) {
            List<String> survivors = block.stream().filter(keep::contains).toList();
            if (!survivors.isEmpty()) {
                kept.add(survivors);
            }
        }
        return of(kept);
    }

    @Override
    public String toString() {
        return "Partition[" + blocks.size() + " blocks over " + blockOf.size() + " states]";
    }
}
