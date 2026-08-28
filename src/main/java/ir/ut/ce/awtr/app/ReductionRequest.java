package ir.ut.ce.awtr.app;

import java.util.Objects;

import ir.ut.ce.awtr.source.ObservableSet;
import ir.ut.ce.awtr.weak.TimeSemantics;
import ir.ut.ce.awtr.weak.UnitDelayRefinement;

/**
 * Everything the headless entry point needs, with no reference to the command
 * line. Tests and any future Afra plug-in build one of these directly.
 */
public record ReductionRequest(ObservableSet observables,
                               TimeSemantics timeSemantics,
                               int maxIntermediateStates,
                               boolean verify) {

    public ReductionRequest {
        Objects.requireNonNull(observables, "observables");
        Objects.requireNonNull(timeSemantics, "timeSemantics");
        if (maxIntermediateStates < 0) {
            throw new IllegalArgumentException(
                    "maxIntermediateStates must not be negative: " + maxIntermediateStates);
        }
    }

    public static ReductionRequest of(ObservableSet observables) {
        return new ReductionRequest(observables, TimeSemantics.UNIT_ADDITIVE,
                UnitDelayRefinement.DEFAULT_MAX_INTERMEDIATE_STATES, true);
    }

    public ReductionRequest withTimeSemantics(TimeSemantics semantics) {
        return new ReductionRequest(observables, semantics, maxIntermediateStates, verify);
    }

    public ReductionRequest withVerification(boolean enabled) {
        return new ReductionRequest(observables, timeSemantics, maxIntermediateStates, enabled);
    }
}
