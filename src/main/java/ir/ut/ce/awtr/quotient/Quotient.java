package ir.ut.ce.awtr.quotient;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;

import ir.ut.ce.awtr.tts.TransitionSystem;
import ir.ut.ce.awtr.weak.Partition;

/**
 * The reduced transition system together with the maps that produced it.
 *
 * @param system              the quotient itself, the delivered reduced model
 * @param partition           the classes it was built from, over the refined state set
 * @param stateOfInput        every state the input declared, to its quotient state
 * @param members             quotient state to the input states it stands for, sorted
 * @param expandedStateOfRefined every refined state, to the state it corresponds to once
 *                            the quotient is expanded back to unit delays; this is what
 *                            the independent verifier checks the transfer conditions against
 * @param splicedChains       how many time-only class chains were collapsed
 */
public record Quotient(TransitionSystem system,
                       Partition partition,
                       Map<String, String> stateOfInput,
                       Map<String, SortedSet<String>> members,
                       Map<String, String> expandedStateOfRefined,
                       Map<Integer, String> quotientStateOfClass,
                       int splicedChains,
                       int syntheticClasses) {

    public Quotient {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(partition, "partition");
        stateOfInput = Map.copyOf(stateOfInput);
        members = Map.copyOf(members);
        expandedStateOfRefined = Map.copyOf(expandedStateOfRefined);
        quotientStateOfClass = Map.copyOf(quotientStateOfClass);
    }

    /**
     * Every class either became a quotient state or was spliced onto a quotient
     * delay edge. Both cases together must cover the partition exactly.
     */
    public boolean classesAccountedFor() {
        return quotientStateOfClass.size() + splicedChainMembers() == partition.blockCount();
    }

    private int splicedChainMembers() {
        return partition.blockCount() - quotientStateOfClass.size();
    }

    public int stateCount() {
        return system.stateCount();
    }

    public int transitionCount() {
        return system.transitionCount();
    }

    public List<String> states() {
        return system.states();
    }

    /** Alias kept for readability at call sites that only care about the map. */
    public Map<String, String> expandedStateOfClass() {
        return expandedStateOfRefined;
    }

    /** @throws IllegalArgumentException if the state was not part of the input */
    public String stateFor(String inputState) {
        String quotientState = stateOfInput.get(inputState);
        if (quotientState == null) {
            throw new IllegalArgumentException(
                    "state '" + inputState + "' was not part of the reduced input");
        }
        return quotientState;
    }
}
