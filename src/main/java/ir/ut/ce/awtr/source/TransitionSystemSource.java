package ir.ut.ce.awtr.source;

/**
 * The adapter boundary for acquiring a model.
 *
 * <p>Everything downstream of this interface — hiding, closure, refinement,
 * quotient construction, verification — is written against
 * {@link RawTransitionSystem} and never learns where the model came from. The
 * file-backed {@code AfraStateSpaceSource} is the implementation shipped today;
 * a future in-process Afra adapter that walks the model checker's own state
 * objects only has to implement this one method to reuse the whole pipeline
 * unchanged.
 *
 * @see InMemoryTransitionSystemSource
 */
@FunctionalInterface
public interface TransitionSystemSource {

    /**
     * @throws ir.ut.ce.awtr.tts.InvalidModelException if the underlying model is
     *         malformed or outside the supported fragment
     */
    RawTransitionSystem load();
}
