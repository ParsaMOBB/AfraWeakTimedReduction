# Design

## What shape the application takes, and why

**A command line, not a GUI or a service.** The deliverable has to be driven by
an automated suite from a clean checkout, and every capability has to be
reachable without a display. `ReductionService` is the actual entry point —
`Cli` only parses arguments and picks an exit code — so the whole workflow is
callable in-process as well.

Three commands: `reduce` (the primary workflow, one model in, a quotient out),
`equivalent` (are two models weak timed bisimilar?), and `inspect` (what does
this export contain?). `equivalent` exists because both acceptance oracles are
stated as pairwise claims, and because it costs nothing: it is the same
refinement over the disjoint union of the two systems, so a comparison can never
drift away from the reduction.

Exit codes are part of the contract: `0` success, `1` a semantic negative,
`2` invalid input, `3` a violated invariant. This split is forced by the
problem — a caller has to distinguish "the answer is no" from "I could not
read your file" — not chosen for style.

## Layering

```
afra ──┐                    (the only package that knows XML)
       ├──> source ──> tts ──> weak ──> quotient ──> verify
app ───┘                                    │
report <────────────────────────────────────┘
```

`source` defines the adapter boundary and the acquired model; `tts` is the
validated domain; `weak` is the algorithm; `quotient` builds the reduced system;
`verify` checks it; `report` serialises; `app` wires it together.

`tts`, `weak`, `quotient` and `verify` import nothing from `java.io`,
`java.nio.file`, `javax.xml`, Eclipse, Afra, or each other's I/O concerns.
`BoundaryTest` fails the build if that stops being true, because it is the
property that makes an in-process Afra adapter a small change rather than a
rewrite.

Single Maven artefact. Package boundaries carry the design; splitting them into
modules would add build machinery without changing a single responsibility, and
the whole thing is under three thousand lines.

## The adapter boundary

```java
@FunctionalInterface
public interface TransitionSystemSource {
    RawTransitionSystem load();
}
```

That is the entire surface. `AfraStateSpaceSource` implements it over a file;
`InMemoryTransitionSystemSource` implements it over an already-built model.
`AdapterContractTest` shows the two produce identical domain models and identical
reductions, and shows a source written by hand in four lines driving the full
pipeline.

`RawTransitionSystem` keeps interactions *named* — `sender`, `owner`, `title`,
plus `executionTime` and `shift` as provenance. Hiding is a separate, explicit
step driven by the runtime observable set, so the same acquired model can be
reduced under different observation choices without re-reading the source. A
future in-process Afra adapter that walks the model checker's own state objects
implements one method and reuses everything downstream.

## No runtime dependencies

Nothing outside the Java SE platform. StAX parses the export; a ~150-line writer
emits JSON.

The reason is the integration target. This jar is meant to end up on an
Eclipse/Spring classpath inside Afra, which already carries its own XML and
binding stacks. A jar with no dependencies cannot collide with them. Adding
Jackson to save a small writer would trade a real integration risk for a small
convenience. `BoundaryTest.noThirdPartyRuntimeDependencies` keeps it that way.

## Toolchain

Java 17, Maven, JUnit Jupiter 5.10.1, compiler plugin 3.11.0, surefire 3.2.2 —
each pinned to the value the official Afra toolchain uses. See
`docs/afra-integration.md` for the revisions those were read from.

## Data structures

`WeakTransitionRelation` indexes states once and stores target sets as
`BitSet`s, so the refinement inner loop is bit-parallel and allocation free.
The refinement itself is signature-based: each round computes, per state, the
set of blocks it can reach under each label, and groups by that signature. It is
O(states × labels) per round and at most `states` rounds.

A more sophisticated algorithm (Paige–Tarjan style) would improve the asymptotic
bound. It is not warranted here: the largest available model is 42 states, the
refinement finishes in single-digit milliseconds, and a signature-based
implementation maps one to one onto the pseudocode's Step 3, which matters more
for a write-up that has to be traceable than the constant factor does.

## Output formats

| artefact | role |
| --- | --- |
| `reduced.json` | **primary.** The only artefact carrying labels, class membership and the observable set together, so it is the only lossless one |
| `partition.json` | which class each input state landed in, and where it sits in the reduced model |
| `reduced.statespace` | Afra's own dialect, so the result feeds straight back into `StateSpaceTransformer` — but **lossy**, see below |
| `reduced.dot` | Graphviz, labelled the way Afra labels it |
| `metrics.json` | every number a table in the write-up may cite |

The separate `visualize` command renders the complete acquired `.statespace`
model as deterministic DOT before hiding or reduction. `StateSpaceDotWriter`
uses generated Graphviz node identifiers, so arbitrary Afra state IDs cannot
break the graph, while node and edge labels preserve the source identifiers,
atomic propositions, message-server identities, execution times and shifts.
The command can write a file or stdout and has no dependency on the Afra GUI.

The `.statespace` view is deliberately not the primary result. Afra's schema has
one `sender` slot per transition, and a quotient edge may stand for several
original message-server transitions with different senders; it also has nowhere
to record class membership. Emitting it is worth it — it makes the reduced model
viewable in Afra's existing tooling at no cost — but calling it lossless would
be wrong.

## Splicing, and why the quotient needs it

Quotienting a unit-refined system gives a reduced model that speaks only in
single time units: a ten-unit wait becomes nine classes, and the "reduced" model
comes out *larger* than its input. On the base Case II model that was 16 states
in and 28 out.

So a second pass collapses each maximal chain of classes that exist purely to
carry time. A class qualifies only when the sole way in and the sole way out are
delay edges — no choice, no observable step, not the initial class. Tau
self-loops are dropped first, since a state's tau closure always contains that
state and a tau self-loop cannot contribute to any weak move.

Two consequences worth stating plainly:

* **Quotient state count is not the class count.** Every class is either a
  quotient state or an instant on a quotient delay edge; `partition.json` records
  which, so every input state stays traceable. `Quotient.classesAccountedFor()`
  asserts the two cases cover the partition exactly.
* **The test is structural, not about which states sit in a class.** Making it
  depend on whether the input happens to declare a state mid-wait would mean two
  bisimilar models reduce to different-sized quotients purely because one of them
  names an instant the other passes through silently.

Splicing records where each removed class sat in its chain, so re-expanding the
delivered quotient reproduces the unspliced one exactly. That correspondence is
what lets the verifier check the artefact that ships rather than an intermediate
form of it.

## Verification

`WeakTimedRelationVerifier` takes a relation someone else produced and tests the
definition against it: for every related pair and every weak move of either
side, the other side must answer with a move landing in a related state.

Its closure machinery is written from scratch with plain sets rather than
reusing the reducer's bit-set implementation. If both used the same code, a
defect in it would hide behind an identical defect in the check. Five mutation
tests — remove an edge, relabel one, change a duration, redirect one, add one —
show the verifier is capable of saying no.
