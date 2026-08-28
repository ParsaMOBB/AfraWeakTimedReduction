package ir.ut.ce.awtr.weak;

import java.util.Objects;

import ir.ut.ce.awtr.tts.Transition;
import ir.ut.ce.awtr.tts.TransitionSystem;

/**
 * Places two systems side by side in one system with disjoint state names.
 *
 * <p>This is how "are these two models weak timed bisimilar?" is answered
 * without a second algorithm: refine the union once, then ask whether the two
 * initial states landed in the same block. Because the union adds no
 * transitions between the two halves, a block of the union restricted to either
 * half is exactly a block of that half — so the comparison and the reduction
 * agree by construction.
 */
public final class DisjointUnion {

    private final TransitionSystem combined;
    private final String leftInitial;
    private final String rightInitial;
    private final String leftPrefix;
    private final String rightPrefix;

    private DisjointUnion(TransitionSystem combined, String leftInitial, String rightInitial,
                          String leftPrefix, String rightPrefix) {
        this.combined = combined;
        this.leftInitial = leftInitial;
        this.rightInitial = rightInitial;
        this.leftPrefix = leftPrefix;
        this.rightPrefix = rightPrefix;
    }

    public static DisjointUnion of(TransitionSystem left, TransitionSystem right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        String leftPrefix = "L:";
        String rightPrefix = "R:";

        // The union needs an initial state but the question is about both
        // halves; the left initial state is used and the right one is carried
        // separately, so nothing about the right half is treated as unreachable.
        TransitionSystem.Builder builder =
                TransitionSystem.builder(left.id() + "|" + right.id())
                        .initialState(leftPrefix + left.initialState());
        copy(left, leftPrefix, builder);
        copy(right, rightPrefix, builder);
        return new DisjointUnion(builder.build(),
                leftPrefix + left.initialState(), rightPrefix + right.initialState(),
                leftPrefix, rightPrefix);
    }

    private static void copy(TransitionSystem system, String prefix,
                             TransitionSystem.Builder builder) {
        system.states().forEach(state -> builder.stateIfAbsent(prefix + state));
        for (Transition t : system.transitions()) {
            builder.transition(prefix + t.source(), t.label(), prefix + t.target());
        }
    }

    public TransitionSystem combined() {
        return combined;
    }

    public String leftInitial() {
        return leftInitial;
    }

    public String rightInitial() {
        return rightInitial;
    }

    public String leftName(String state) {
        return leftPrefix + state;
    }

    public String rightName(String state) {
        return rightPrefix + state;
    }
}
