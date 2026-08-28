package ir.ut.ce.awtr.source;

import java.util.Objects;
import java.util.Optional;

/**
 * One transition exactly as the source reported it, before any interaction is
 * hidden.
 *
 * <p>Afra attaches {@code executionTime} and {@code shift} to every transition.
 * Neither contributes to the transition-system semantics — the elapsed time of a
 * delay step is carried by {@code <time value>} alone, and {@code shift} only
 * records how far absolute timestamps were renormalised to keep the state space
 * finite — so both are preserved here purely as provenance.
 */
public record RawTransition(String source,
                            String target,
                            Optional<ActionIdentity> action,
                            Optional<Integer> delay,
                            Integer executionTime,
                            Integer shift) {

    public RawTransition {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(delay, "delay");
        if (action.isPresent() && delay.isPresent()) {
            throw new IllegalArgumentException(
                    "a transition carries either a messageserver or a time element, never both: "
                            + source + " -> " + target);
        }
        if (delay.isPresent() && delay.get() < 0) {
            throw new IllegalArgumentException(
                    "negative time progress " + delay.get() + " on " + source + " -> " + target);
        }
    }

    public static RawTransition message(String source, String target, ActionIdentity action,
                                        Integer executionTime, Integer shift) {
        return new RawTransition(source, target, Optional.of(action), Optional.empty(),
                executionTime, shift);
    }

    public static RawTransition time(String source, String target, int units,
                                     Integer executionTime, Integer shift) {
        return new RawTransition(source, target, Optional.empty(), Optional.of(units),
                executionTime, shift);
    }

    /**
     * A transition with neither child element. The schema permits this
     * ({@code xs:choice minOccurs="0"}); semantically it is an unlabelled
     * internal step.
     */
    public static RawTransition silent(String source, String target,
                                       Integer executionTime, Integer shift) {
        return new RawTransition(source, target, Optional.empty(), Optional.empty(),
                executionTime, shift);
    }

    public boolean isDelay() {
        return delay.isPresent();
    }

    public boolean isMessage() {
        return action.isPresent();
    }
}
