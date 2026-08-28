package ir.ut.ce.awtr.tts;

import java.util.Objects;

/** One labelled edge of a {@link TransitionSystem}. */
public record Transition(String source, Label label, String target)
        implements Comparable<Transition> {

    public Transition {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(target, "target");
    }

    @Override
    public int compareTo(Transition other) {
        int bySource = source.compareTo(other.source);
        if (bySource != 0) {
            return bySource;
        }
        int byLabel = label.compareTo(other.label);
        return byLabel != 0 ? byLabel : target.compareTo(other.target);
    }

    @Override
    public String toString() {
        return source + " --" + label + "--> " + target;
    }
}
