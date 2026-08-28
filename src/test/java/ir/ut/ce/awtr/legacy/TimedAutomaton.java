package ir.ut.ce.awtr.legacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only model of the legacy acceptance fixtures.
 *
 * <p>The legacy suite states its cases as pairs of discrete-time timed
 * automata. The production application does not read timed automata — its input
 * is an explicit Afra TTS — so this type and {@link TimedAutomatonUnfolder}
 * live entirely in the test source tree. They exist to reproduce the recorded
 * boolean results, not to add a supported input format.
 */
final class TimedAutomaton {

    record Constraint(String clock, String operator, int constant) {
        boolean holds(Map<String, Integer> valuation) {
            int value = valuation.getOrDefault(clock, 0);
            return switch (operator) {
                case ">=" -> value >= constant;
                case ">" -> value > constant;
                case "<=" -> value <= constant;
                case "<" -> value < constant;
                case "==" -> value == constant;
                default -> throw new IllegalArgumentException("operator " + operator);
            };
        }
    }

    record Edge(String source, String target, String action,
                List<Constraint> guard, List<String> resets) {
    }

    private final String id;
    private final List<String> clocks = new ArrayList<>();
    private final List<String> locations = new ArrayList<>();
    private final Map<String, List<Constraint>> invariants = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private String initialLocation;

    TimedAutomaton(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    List<String> clocks() {
        return clocks;
    }

    List<String> locations() {
        return locations;
    }

    List<Edge> edges() {
        return edges;
    }

    String initialLocation() {
        return initialLocation;
    }

    List<Constraint> invariant(String location) {
        return invariants.getOrDefault(location, List.of());
    }

    void addClock(String clock) {
        clocks.add(clock);
    }

    void addLocation(String location, List<Constraint> invariant) {
        locations.add(location);
        invariants.put(location, invariant);
    }

    void setInitialLocation(String location) {
        this.initialLocation = location;
    }

    void addEdge(Edge edge) {
        edges.add(edge);
    }

    /**
     * The largest constant any guard or invariant compares against. Clocks are
     * capped one above it: past that point no constraint can tell two values
     * apart, so the unfolding stays finite without losing a distinction.
     */
    int maximumConstant() {
        int max = 0;
        for (List<Constraint> invariant : invariants.values()) {
            for (Constraint c : invariant) {
                max = Math.max(max, c.constant());
            }
        }
        for (Edge edge : edges) {
            for (Constraint c : edge.guard()) {
                max = Math.max(max, c.constant());
            }
        }
        return max;
    }
}
