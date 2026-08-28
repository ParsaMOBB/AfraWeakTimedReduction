package ir.ut.ce.awtr.weak;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import ir.ut.ce.awtr.tts.InvalidModelException;
import ir.ut.ce.awtr.tts.Label;
import ir.ut.ce.awtr.tts.Transition;
import ir.ut.ce.awtr.tts.TransitionSystem;

/**
 * Makes time additivity explicit by replacing every delay edge labelled
 * {@code d > 1} with {@code d} unit-delay edges through fresh intermediate
 * states.
 *
 * <p>Doing this once, up front, buys two things. The weak delay relation
 * {@code =d=>} becomes the {@code d}-fold composition of {@code =1=>}, so a
 * relation that respects the single label {@code delay(1)} automatically
 * respects every duration — the refinement label set stays finite without any
 * bound on {@code d}. And the closure operators downstream never have to reason
 * about splitting an edge, because there is nothing left to split.
 *
 * <p>Intermediate states are named {@code <source>#<label>#<target>@<k>} and are
 * marked so the reported partition can be restricted to the states the input
 * actually declared.
 */
public final class UnitDelayRefinement {

    /** Guards against a pathological delay constant exploding the state space. */
    public static final int DEFAULT_MAX_INTERMEDIATE_STATES = 5_000_000;

    private final TransitionSystem refined;
    private final Map<String, String> intermediateOwner;

    private UnitDelayRefinement(TransitionSystem refined, Map<String, String> intermediateOwner) {
        this.refined = refined;
        this.intermediateOwner = Map.copyOf(intermediateOwner);
    }

    public static UnitDelayRefinement of(TransitionSystem system) {
        return of(system, DEFAULT_MAX_INTERMEDIATE_STATES);
    }

    public static UnitDelayRefinement of(TransitionSystem system, int maxIntermediateStates) {
        Objects.requireNonNull(system, "system");
        long extra = 0;
        for (Transition t : system.transitions()) {
            if (t.label().isDelay()) {
                extra += t.label().delayUnits() - 1L;
            }
        }
        if (extra > maxIntermediateStates) {
            throw new InvalidModelException("unit-delay refinement of '" + system.id()
                    + "' would add " + extra + " intermediate states, above the limit of "
                    + maxIntermediateStates
                    + "; rerun with --time-semantics strict or raise --max-intermediate-states");
        }

        TransitionSystem.Builder builder = TransitionSystem.builder(system.id())
                .initialState(system.initialState());
        system.states().forEach(builder::stateIfAbsent);
        Map<String, String> owners = new LinkedHashMap<>();

        for (Transition t : system.transitions()) {
            if (!t.label().isDelay() || t.label().delayUnits() == 1) {
                builder.transition(t);
                continue;
            }
            int units = t.label().delayUnits();
            String previous = t.source();
            for (int step = 1; step < units; step++) {
                String intermediate =
                        intermediateName(t.source(), units, t.target(), step);
                builder.stateIfAbsent(intermediate);
                owners.put(intermediate, t.source());
                builder.transition(previous, Label.delay(1), intermediate);
                previous = intermediate;
            }
            builder.transition(previous, Label.delay(1), t.target());
        }
        return new UnitDelayRefinement(builder.build(), owners);
    }

    /**
     * The name given to the instant {@code step} units into a {@code units}-long
     * delay from {@code source} to {@code target}.
     *
     * <p>Public because the quotient builder has to name the same instants when
     * it re-expands a spliced chain, and the two namings have to agree for the
     * verifier's relation to line up.
     */
    public static String intermediateName(String source, int units, String target, int step) {
        return source + "#" + units + "#" + target + "@" + step;
    }

    /** The refined system; every delay label in it is exactly {@code delay(1)}. */
    public TransitionSystem refined() {
        return refined;
    }

    /** True for a state this refinement introduced rather than one the input declared. */
    public boolean isIntermediate(String state) {
        return intermediateOwner.containsKey(state);
    }

    /** How many states the refinement added. */
    public int intermediateCount() {
        return intermediateOwner.size();
    }
}
