package ir.ut.ce.awtr.app;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import ir.ut.ce.awtr.quotient.Quotient;
import ir.ut.ce.awtr.quotient.QuotientBuilder;
import ir.ut.ce.awtr.source.Hiding;
import ir.ut.ce.awtr.source.RawTransitionSystem;
import ir.ut.ce.awtr.source.TransitionSystemSource;
import ir.ut.ce.awtr.tts.TransitionSystem;
import ir.ut.ce.awtr.verify.WeakTimedRelationVerifier;
import ir.ut.ce.awtr.weak.DisjointUnion;
import ir.ut.ce.awtr.weak.Partition;
import ir.ut.ce.awtr.weak.TimeSemantics;
import ir.ut.ce.awtr.weak.UnitDelayRefinement;
import ir.ut.ce.awtr.weak.WeakTimedPartitionRefiner;
import ir.ut.ce.awtr.weak.WeakTransitionRelation;

/**
 * The headless entry point. Everything the command line can do goes through
 * here, so the whole workflow is automatable without a process boundary.
 */
public final class ReductionService {

    private ReductionService() {
    }

    /** Acquires, hides, refines, partitions, quotients, and optionally verifies. */
    public static ReductionResult reduce(TransitionSystemSource source, ReductionRequest request) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(request, "request");
        long started = System.nanoTime();

        RawTransitionSystem acquired = source.load();
        List<String> unmatched = request.observables().tokensNotMatching(acquired);
        TransitionSystem hidden = Hiding.apply(acquired, request.observables()).reachableFragment();

        Refinement refinement = applyTimeSemantics(hidden, request);
        WeakTimedPartitionRefiner.Result refined =
                WeakTimedPartitionRefiner.refine(WeakTransitionRelation.of(refinement.system()));

        Quotient quotient = QuotientBuilder.build(refinement.system(), refined.partition(),
                hidden.states(), refinement.isSynthetic());

        Set<String> declared = new LinkedHashSet<>(hidden.states());
        Partition inputPartition = refined.partition().restrictedTo(declared);

        WeakTimedRelationVerifier.Report verification = request.verify()
                ? verifyQuotient(refinement.system(), quotient, request)
                : null;

        return new ReductionResult(request, acquired, hidden, refinement.system(), refined.partition(),
                inputPartition, quotient, verification, unmatched,
                refined.refinementRounds(), refined.relation().weakTransitionCount(),
                System.nanoTime() - started);
    }

    /**
     * Checks the delivered quotient, not an intermediate form of it: the
     * quotient is expanded back to unit delays and the relation carried by
     * {@link Quotient#expandedStateOfClass()} is tested against the definition.
     */
    private static WeakTimedRelationVerifier.Report verifyQuotient(
            TransitionSystem refinedSystem, Quotient quotient, ReductionRequest request) {
        TransitionSystem expanded = request.timeSemantics() == TimeSemantics.UNIT_ADDITIVE
                ? UnitDelayRefinement.of(quotient.system(), request.maxIntermediateStates()).refined()
                : quotient.system();
        Map<String, String> relation = new LinkedHashMap<>();
        for (String state : refinedSystem.states()) {
            relation.put(state, quotient.expandedStateOfClass().get(state));
        }
        return WeakTimedRelationVerifier.verify(refinedSystem, expanded, relation);
    }

    /**
     * Are the initial states of two acquired models weak timed bisimilar?
     *
     * <p>Answered with the same partition refinement, over the disjoint union of
     * the two systems, so the comparison cannot drift away from the reduction.
     */
    public static ComparisonResult compare(TransitionSystemSource left,
                                           TransitionSystemSource right,
                                           ReductionRequest request) {
        long started = System.nanoTime();
        TransitionSystem a = Hiding.apply(left.load(), request.observables()).reachableFragment();
        TransitionSystem b = Hiding.apply(right.load(), request.observables()).reachableFragment();
        return compareSystems(a, b, request, started);
    }

    /** Same as {@link #compare}, for systems that are already hidden. */
    public static ComparisonResult compareSystems(TransitionSystem a, TransitionSystem b,
                                                  ReductionRequest request) {
        return compareSystems(a, b, request, System.nanoTime());
    }

    private static ComparisonResult compareSystems(TransitionSystem a, TransitionSystem b,
                                                   ReductionRequest request, long started) {
        DisjointUnion union = DisjointUnion.of(a, b);
        Refinement refinement = applyTimeSemantics(union.combined(), request);
        WeakTimedPartitionRefiner.Result refined =
                WeakTimedPartitionRefiner.refine(WeakTransitionRelation.of(refinement.system()));
        boolean bisimilar = refined.partition()
                .sameBlock(union.leftInitial(), union.rightInitial());
        return new ComparisonResult(a, b, bisimilar, refined.partition(),
                refined.refinementRounds(), System.nanoTime() - started);
    }

    private static Refinement applyTimeSemantics(TransitionSystem system,
                                                 ReductionRequest request) {
        if (request.timeSemantics() == TimeSemantics.STRICT_EDGE) {
            return new Refinement(system, state -> false);
        }
        UnitDelayRefinement unit =
                UnitDelayRefinement.of(system, request.maxIntermediateStates());
        return new Refinement(unit.refined(), unit::isIntermediate);
    }

    private record Refinement(TransitionSystem system, Predicate<String> isSynthetic) {
    }

    /**
     * @param bisimilar whether the two initial states landed in the same class
     */
    public record ComparisonResult(TransitionSystem left,
                                   TransitionSystem right,
                                   boolean bisimilar,
                                   Partition partition,
                                   int refinementRounds,
                                   long elapsedNanos) {
    }
}
