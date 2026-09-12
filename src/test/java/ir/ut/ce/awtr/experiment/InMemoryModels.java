package ir.ut.ce.awtr.experiment;

import ir.ut.ce.awtr.source.ActionIdentity;
import ir.ut.ce.awtr.source.RawTransition;
import ir.ut.ce.awtr.source.RawTransitionSystem;
import ir.ut.ce.awtr.tts.Transition;
import ir.ut.ce.awtr.tts.TransitionSystem;

/**
 * Test-only bridge from an already-labelled system back to the acquired shape a
 * {@code TransitionSystemSource} produces.
 *
 * <p>It exists so the experiment can push a generated system through the exact
 * pipeline the {@code reduce} command uses, rather than a private variant of
 * it, and show that the two agree.
 */
final class InMemoryModels {

    private InMemoryModels() {
    }

    /**
     * @param system a system whose labels are already tau, observable, or delay
     */
    static RawTransitionSystem raw(TransitionSystem system) {
        RawTransitionSystem.Builder builder = RawTransitionSystem.builder(system.id());
        system.states().forEach(state -> builder.state(state, ""));
        builder.initialState(system.initialState());
        for (Transition t : system.transitions()) {
            if (t.label().isTau()) {
                builder.transition(RawTransition.silent(t.source(), t.target(), 0, 0));
            } else if (t.label().isDelay()) {
                builder.transition(
                        RawTransition.time(t.source(), t.target(), t.label().delayUnits(), 0, 0));
            } else {
                builder.transition(RawTransition.message(t.source(), t.target(),
                        new ActionIdentity("", "actor", t.label().observableName()), 0, 0));
            }
        }
        return builder.build();
    }
}
