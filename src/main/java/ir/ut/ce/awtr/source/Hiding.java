package ir.ut.ce.awtr.source;

import ir.ut.ce.awtr.tts.Label;
import ir.ut.ce.awtr.tts.TransitionSystem;

/**
 * Turns an acquired model into the labelled system the algorithm consumes, by
 * replacing every unobserved interaction with tau.
 *
 * <p>Three normalisations happen here, and nowhere else:
 * <ul>
 *   <li>a message-server transition becomes an observable label when the runtime
 *       observable set matches it, and tau otherwise;</li>
 *   <li>a {@code <time value="0"/>} transition becomes tau — zero-duration time
 *       progress is observationally an internal step, and keeping it as a delay
 *       would force every closure operator to special-case a label that carries
 *       no timing information;</li>
 *   <li>a transition with neither child element becomes tau, which is the only
 *       reading the schema's optional choice leaves open.</li>
 * </ul>
 */
public final class Hiding {

    private Hiding() {
    }

    public static TransitionSystem apply(RawTransitionSystem model, ObservableSet observables) {
        TransitionSystem.Builder builder = TransitionSystem.builder(model.id())
                .initialState(model.initialState());
        model.states().forEach(builder::stateIfAbsent);

        for (RawTransition t : model.transitions()) {
            builder.transition(t.source(), labelOf(t, observables), t.target());
        }
        return builder.build();
    }

    private static Label labelOf(RawTransition transition, ObservableSet observables) {
        if (transition.delay().isPresent()) {
            int units = transition.delay().get();
            return units == 0 ? Label.TAU : Label.delay(units);
        }
        if (transition.action().isPresent()) {
            return observables.observableNameOf(transition.action().get())
                    .map(Label::observable)
                    .orElse(Label.TAU);
        }
        return Label.TAU;
    }
}
