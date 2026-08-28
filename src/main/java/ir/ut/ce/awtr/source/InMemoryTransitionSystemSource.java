package ir.ut.ce.awtr.source;

import java.util.Objects;

/**
 * Serves an already-built model. Exists so tests — and any future adapter that
 * constructs the model programmatically rather than by parsing a file — can
 * exercise the identical downstream pipeline.
 */
public final class InMemoryTransitionSystemSource implements TransitionSystemSource {

    private final RawTransitionSystem model;

    public InMemoryTransitionSystemSource(RawTransitionSystem model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public RawTransitionSystem load() {
        return model;
    }
}
