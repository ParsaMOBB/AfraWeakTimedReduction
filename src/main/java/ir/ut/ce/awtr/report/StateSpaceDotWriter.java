package ir.ut.ce.awtr.report;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import ir.ut.ce.awtr.source.RawTransition;
import ir.ut.ce.awtr.source.RawTransitionSystem;

/** Renders an acquired Afra state space in the style of Afra's Graphviz exporter. */
public final class StateSpaceDotWriter {

    /** Returns deterministic UTF-8 Graphviz DOT for the complete, unreduced model. */
    public String render(RawTransitionSystem model) {
        Objects.requireNonNull(model, "model");

        Map<String, String> nodeIds = new LinkedHashMap<>();
        for (int i = 0; i < model.states().size(); i++) {
            nodeIds.put(model.states().get(i), "n" + i);
        }

        StringBuilder dot = new StringBuilder("digraph statespace {\n");
        dot.append("  rankdir=TB;\n");
        for (String state : model.states()) {
            dot.append("  ").append(nodeIds.get(state)).append(" [label=\"")
                    .append(escape(stateLabel(model, state))).append('"');
            if (state.equals(model.initialState())) {
                dot.append(", shape=doublecircle");
            }
            dot.append("];\n");
        }

        for (RawTransition transition : model.transitions()) {
            dot.append("  ").append(nodeIds.get(transition.source()))
                    .append(" -> ").append(nodeIds.get(transition.target()))
                    .append(" [label=\"").append(escape(transitionLabel(transition))).append('"');
            if (transition.isDelay()) {
                dot.append(", style=bold, color=red");
            }
            dot.append("];\n");
        }
        return dot.append("}\n").toString();
    }

    private static String stateLabel(RawTransitionSystem model, String state) {
        String label = !state.isEmpty() && Character.isDigit(state.charAt(0)) ? "S" + state : state;
        String propositions = model.atomicPropositions().getOrDefault(state, "").trim();
        if (!propositions.isEmpty()) {
            label += ":\n" + propositions.replace(",", "\n");
        }
        return label;
    }

    private static String transitionLabel(RawTransition transition) {
        StringBuilder label = new StringBuilder();
        if (transition.isMessage()) {
            label.append(transition.action().orElseThrow().qualifiedName());
        } else if (transition.isDelay()) {
            label.append("time +=").append(transition.delay().orElseThrow());
        } else {
            label.append("tau");
        }

        if (transition.executionTime() != null) {
            label.append("\n @").append(transition.executionTime());
        }
        if (transition.shift() != null && transition.shift() != 0) {
            label.append(" -> shift(");
            if (transition.shift() > 0) {
                label.append('+');
            }
            label.append(transition.shift()).append(')');
        }
        return label.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }
}
