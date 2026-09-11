package ir.ut.ce.awtr.weak;

/**
 * How a delay-labelled edge is allowed to be decomposed when matching weak
 * timed moves.
 *
 * <p>The two readings differ on one question: if a system can let 10 units pass
 * in a single step, can it also be observed after 7 of them?
 *
 * @see <a href="file:../../../../../../../docs/semantics.md">docs/semantics.md</a>
 */
public enum TimeSemantics {

    /**
     * A delay edge labelled {@code d} is atomic. {@code s =d=> t} holds only via
     * a single {@code d}-labelled edge wrapped in tau steps, exactly as the
     * project pseudocode's {@code DelayClosure} is written.
     *
     * <p>Under this reading two systems that let the same total time pass in a
     * different number of steps are <em>not</em> equivalent.
     */
    STRICT_EDGE,

    /**
     * Time is divisible and durations are accumulated across internal steps: a
     * delay edge labelled {@code d} is the {@code d}-fold composition of unit
     * delays, so every intermediate instant is a state of the system.
     *
     * <p>This is the run-based reading under which {@code =d=>} is defined by
     * "there is a run whose visible content is empty and whose duration is
     * {@code d}". It is the default: the same behaviour may be
     * expressed as a single 10-unit step, as 3 units then an internal step then
     * 7 units, or as 2 units then an internal step then 8 units.
     */
    UNIT_ADDITIVE;

    public static TimeSemantics parse(String token) {
        if (token == null) {
            return UNIT_ADDITIVE;
        }
        return switch (token.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "strict", "strict-edge", "edge" -> STRICT_EDGE;
            case "unit", "additive", "unit-additive" -> UNIT_ADDITIVE;
            default -> throw new IllegalArgumentException(
                    "unknown time semantics '" + token + "'; expected 'unit' or 'strict'");
        };
    }

    public String token() {
        return this == STRICT_EDGE ? "strict" : "unit";
    }
}
