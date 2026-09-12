package ir.ut.ce.awtr.app;

import java.util.Locale;

/**
 * How the question "are these two models weak timed bisimilar?" is decided.
 *
 * <p>The two families answer the same question in opposite ways. {@link #UNION}
 * relates the two state spaces directly; the reduced-form methods never relate
 * them at all and compare the two reductions as graphs instead. The second
 * family is only a decision procedure when the reduced form is canonical, which
 * is exactly what {@code docs/experiments/quotient-isomorphism.md} examines -
 * and why the unsound-in-general variant is kept, named, and testable rather
 * than quietly dropped.
 */
public enum ComparisonMethod {

    /**
     * Refine the disjoint union of the two models once and ask whether the two
     * initial states landed in the same class. Established, and the reference
     * answer for every experiment here.
     */
    UNION("union"),

    /**
     * Reduce each model separately to its saturated quotient - one state per
     * class, edges given by the weak transition relation - and test the two for
     * isomorphism. The saturated quotient is determined by the classes alone, so
     * this agrees with {@link #UNION}.
     */
    REDUCED_ISOMORPHISM("reduced-iso"),

    /**
     * The same, but comparing the quotient the {@code reduce} command actually
     * writes out: built from the model's own edges and with time-only class
     * chains spliced back into single delay edges. Structurally smaller and
     * far more readable, but <em>not</em> canonical - a silent step that the
     * weak closure already implies survives into this quotient and can make two
     * equivalent models come out as different graphs.
     */
    REDUCED_ISOMORPHISM_SPLICED("reduced-iso-spliced");

    private final String token;

    ComparisonMethod(String token) {
        this.token = token;
    }

    public static ComparisonMethod parse(String token) {
        if (token == null) {
            return UNION;
        }
        String normalised = token.trim().toLowerCase(Locale.ROOT);
        for (ComparisonMethod method : values()) {
            if (method.token.equals(normalised)) {
                return method;
            }
        }
        throw new IllegalArgumentException("unknown comparison method '" + token
                + "'; expected 'union', 'reduced-iso' or 'reduced-iso-spliced'");
    }

    public String token() {
        return token;
    }

    /** True when this method reduces each model on its own. */
    public boolean reducesSeparately() {
        return this != UNION;
    }
}
