package ir.ut.ce.awtr.tts;

import java.util.Objects;

/**
 * A transition label of a discrete-time timed transition system.
 *
 * <p>Exactly three shapes exist, matching the input alphabet of the project
 * algorithm: a silent step, an observable interaction, and the passage of a
 * strictly positive whole number of time units.
 *
 * <p>A delay of zero is not representable. Zero-duration time progress carries
 * no information in a TTS and is normalised to {@link #TAU} when a source
 * produces it, so that the closure operators never have to special-case it.
 */
public abstract sealed class Label implements Comparable<Label> {

    /** The silent label. Every hidden interaction is mapped onto this instance. */
    public static final Label TAU = new Tau();

    private Label() {
    }

    public static Label observable(String name) {
        return new Observable(name);
    }

    /**
     * @throws IllegalArgumentException if {@code units} is not strictly positive
     */
    public static Label delay(int units) {
        if (units <= 0) {
            throw new IllegalArgumentException(
                    "delay must be strictly positive, got " + units
                            + " (zero-duration progress must be normalised to tau)");
        }
        return new Delay(units);
    }

    public boolean isTau() {
        return this instanceof Tau;
    }

    public boolean isObservable() {
        return this instanceof Observable;
    }

    public boolean isDelay() {
        return this instanceof Delay;
    }

    /** @throws IllegalStateException if this label is not a delay */
    public int delayUnits() {
        if (this instanceof Delay d) {
            return d.units;
        }
        throw new IllegalStateException("not a delay label: " + this);
    }

    /** @throws IllegalStateException if this label is not observable */
    public String observableName() {
        if (this instanceof Observable o) {
            return o.name;
        }
        throw new IllegalStateException("not an observable label: " + this);
    }

    /** Sort key: tau first, then observables by name, then delays by duration. */
    private int rank() {
        if (this instanceof Tau) {
            return 0;
        }
        return this instanceof Observable ? 1 : 2;
    }

    @Override
    public int compareTo(Label other) {
        int byRank = Integer.compare(rank(), other.rank());
        if (byRank != 0) {
            return byRank;
        }
        if (this instanceof Observable a && other instanceof Observable b) {
            return a.name.compareTo(b.name);
        }
        if (this instanceof Delay a && other instanceof Delay b) {
            return Integer.compare(a.units, b.units);
        }
        return 0;
    }

    private static final class Tau extends Label {
        @Override
        public boolean equals(Object o) {
            return o instanceof Tau;
        }

        @Override
        public int hashCode() {
            return 17;
        }

        @Override
        public String toString() {
            return "tau";
        }
    }

    private static final class Observable extends Label {
        private final String name;

        Observable(String name) {
            this.name = Objects.requireNonNull(name, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("observable name must not be empty");
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Observable other && name.equals(other.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final class Delay extends Label {
        private final int units;

        Delay(int units) {
            this.units = units;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Delay other && units == other.units;
        }

        @Override
        public int hashCode() {
            return 31 * units + 7;
        }

        @Override
        public String toString() {
            return "delay(" + units + ")";
        }
    }
}
