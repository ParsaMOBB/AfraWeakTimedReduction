package ir.ut.ce.awtr.app;

import java.util.List;
import java.util.Objects;

import ir.ut.ce.awtr.quotient.Quotient;
import ir.ut.ce.awtr.source.RawTransitionSystem;
import ir.ut.ce.awtr.tts.TransitionSystem;
import ir.ut.ce.awtr.verify.WeakTimedRelationVerifier;
import ir.ut.ce.awtr.weak.Partition;

/**
 * The outcome of one reduction, before anything is written to disk.
 *
 * @param acquired        the model exactly as the source produced it
 * @param hidden          the same model with unobserved interactions turned into tau
 * @param refined         {@code hidden} after the chosen time semantics was applied
 * @param partition       the weak timed bisimilarity classes over {@code refined}
 * @param inputPartition  the same classes restricted to the states the input declared
 * @param quotient        the reduced model
 * @param verification    present when verification was requested
 * @param unmatchedObservables observable tokens that matched nothing in the model
 * @param elapsedNanos    wall-clock time for acquisition through quotient construction
 */
public record ReductionResult(ReductionRequest request,
                              RawTransitionSystem acquired,
                              TransitionSystem hidden,
                              TransitionSystem refined,
                              Partition partition,
                              Partition inputPartition,
                              Quotient quotient,
                              WeakTimedRelationVerifier.Report verification,
                              List<String> unmatchedObservables,
                              int refinementRounds,
                              long weakTransitionCount,
                              long elapsedNanos) {

    public ReductionResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(acquired, "acquired");
        Objects.requireNonNull(hidden, "hidden");
        Objects.requireNonNull(refined, "refined");
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(inputPartition, "inputPartition");
        Objects.requireNonNull(quotient, "quotient");
        unmatchedObservables = List.copyOf(unmatchedObservables);
    }

    /** The observable tokens exactly as supplied, recorded in every artefact. */
    public List<String> observables() {
        return request.observables().tokens();
    }

    public String timeSemanticsToken() {
        return request.timeSemantics().token();
    }

    public int originalStateCount() {
        return hidden.stateCount();
    }

    public int originalTransitionCount() {
        return hidden.transitionCount();
    }

    public int reducedStateCount() {
        return quotient.stateCount();
    }

    public int reducedTransitionCount() {
        return quotient.transitionCount();
    }

    /** Fraction of states removed; 0 when nothing merged. */
    public double stateReductionRatio() {
        return originalStateCount() == 0
                ? 0.0
                : 1.0 - (double) reducedStateCount() / originalStateCount();
    }

    public double transitionReductionRatio() {
        return originalTransitionCount() == 0
                ? 0.0
                : 1.0 - (double) reducedTransitionCount() / originalTransitionCount();
    }

    public boolean verified() {
        return verification == null || verification.valid();
    }
}
