package ir.ut.ce.awtr.app;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import ir.ut.ce.awtr.iso.TransitionSystemIsomorphism;
import ir.ut.ce.awtr.quotient.Quotient;
import ir.ut.ce.awtr.quotient.QuotientBuilder;
import ir.ut.ce.awtr.quotient.SaturatedQuotient;
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
     * Are the two models equivalent, decided by reducing each one on its own and
     * comparing the two reduced models as graphs?
     *
     * <p>The disjoint-union answer above computes one relation across both state
     * spaces. This one never relates the two models at all: it reduces each
     * separately and asks whether the results are the same system up to a
     * renaming of states. That is only a decision procedure if the reduced form
     * is canonical - see {@code docs/experiments/quotient-isomorphism.md}, which
     * is what these methods exist to test.
     */
    public static ReducedComparisonResult compareByReducedForm(TransitionSystemSource left,
                                                               TransitionSystemSource right,
                                                               ReductionRequest request,
                                                               ComparisonMethod method) {
        long started = System.nanoTime();
        TransitionSystem a = Hiding.apply(left.load(), request.observables()).reachableFragment();
        TransitionSystem b = Hiding.apply(right.load(), request.observables()).reachableFragment();
        return compareSystemsByReducedForm(a, b, request, method, started);
    }

    /** Same as {@link #compareByReducedForm}, for systems that are already hidden. */
    public static ReducedComparisonResult compareSystemsByReducedForm(TransitionSystem a,
                                                                      TransitionSystem b,
                                                                      ReductionRequest request,
                                                                      ComparisonMethod method) {
        return compareSystemsByReducedForm(a, b, request, method, System.nanoTime());
    }

    private static ReducedComparisonResult compareSystemsByReducedForm(TransitionSystem a,
                                                                       TransitionSystem b,
                                                                       ReductionRequest request,
                                                                       ComparisonMethod method,
                                                                       long started) {
        if (method == ComparisonMethod.UNION) {
            throw new IllegalArgumentException(
                    "the union method does not reduce the two models separately");
        }
        TransitionSystem leftReduced = reducedForm(a, request, method);
        TransitionSystem rightReduced = reducedForm(b, request, method);
        TransitionSystemIsomorphism.Result isomorphism =
                TransitionSystemIsomorphism.check(leftReduced, rightReduced);
        return new ReducedComparisonResult(a, b, leftReduced, rightReduced, method,
                isomorphism, System.nanoTime() - started);
    }

    /**
     * One model's reduced form under the requested time semantics.
     *
     * <p>{@link ComparisonMethod#REDUCED_ISOMORPHISM_SPLICED} deliberately walks
     * the same path as {@link #reduce}, so the experiment measures the artefact
     * the tool actually delivers rather than a private variant of it; a test
     * pins the two against each other.
     *
     * @param hidden a system whose unobserved interactions are already tau
     */
    public static TransitionSystem reducedForm(TransitionSystem hidden,
                                               ReductionRequest request,
                                               ComparisonMethod method) {
        Refinement refinement = applyTimeSemantics(hidden, request);
        WeakTransitionRelation relation = WeakTransitionRelation.of(refinement.system());
        WeakTimedPartitionRefiner.Result refined = WeakTimedPartitionRefiner.refine(relation);
        return switch (method) {
            case REDUCED_ISOMORPHISM -> SaturatedQuotient.build(relation, refined.partition());
            case REDUCED_ISOMORPHISM_SPLICED -> QuotientBuilder.build(refinement.system(),
                    refined.partition(), hidden.states(), refinement.isSynthetic()).system();
            case UNION -> throw new IllegalArgumentException(
                    "the union method has no single-model reduced form");
        };
    }

    /**
     * The outcome of a reduce-each-side-then-compare-graphs check.
     *
     * @param leftReduced  the left model's reduced form
     * @param rightReduced the right model's reduced form
     * @param isomorphism  the structural comparison of the two, with its witness
     */
    public record ReducedComparisonResult(TransitionSystem left,
                                          TransitionSystem right,
                                          TransitionSystem leftReduced,
                                          TransitionSystem rightReduced,
                                          ComparisonMethod method,
                                          TransitionSystemIsomorphism.Result isomorphism,
                                          long elapsedNanos) {

        public boolean equivalent() {
            return isomorphism.isomorphic();
        }

        public boolean conclusive() {
            return isomorphism.conclusive();
        }
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
