# Discrete weak timed semantics

## What the tool computes

Given one finite transition system whose labels are observable interactions,
silent steps, and non-negative whole-number delays, it computes the partition of
the states into weak timed bisimilarity classes, and the quotient system that
partition induces.

The governing specification is the supervisor-approved pseudocode in
`Resources/Weak Time Bisimilutation/weak-time.txt`. The terminology follows
Definition 9 in `Resources/Weak Time Bisimilutation/Definition.jpg`.

## Definitions as implemented

Write `s -l-> t` for a transition of the input, `τ` for a silent step, and `d`
for a whole number of time units.

* **Tau closure.** `τ*(s)` is the set of states reachable from `s` by silent
  steps, including `s` itself.
* **Weak action.** For an observable `a`, `s =a=> t` iff `t ∈ τ*(y)` for some
  `x ∈ τ*(s)` with `x -a-> y`.
* **Weak delay.** For `d ≥ 1`, `s =d=> t` iff there is a run from `s` to `t`
  whose visible content is empty and whose total duration is exactly `d`.
* **Empty move.** `s =ε=> t` iff `t ∈ τ*(s)`. This is the `d = 0` case of the
  weak delay relation, and it is refined on like any other label.
* **Weak timed bisimilarity** is the largest relation `R` such that whenever
  `s R t`: for every label `ℓ ∈ Act ∪ {ε} ∪ ℕ⁺` and every `s =ℓ=> s'`, there is
  `t =ℓ=> t'` with `s' R t'`, and symmetrically.

The partition is the coarsest one that is stable under all of those relations,
computed by repeatedly splitting a block whenever two of its members reach
different sets of blocks under some label.

## The one semantic decision: time additivity

The pseudocode writes `DelayClosure(s, d)` as "τ*, then a single `d`-labelled
edge, then τ*". Its comment, and Definition 9, say something more general:
"all states reachable from `s` by exactly `d` units of delay". Those two
readings are not the same, and the project's acceptance oracle distinguishes
them.

`Examples/case II` contains three systems the project owner declares pairwise
weak timed bisimilar. One of them lets ten units pass in a single step. Another
lets seven pass, takes an internal step, then lets three more pass. Under the
literal single-edge reading these cannot match: the first system has no
seven-unit edge to answer the second's. Under the reading where a delay may be
observed part way through — the time-additivity axiom of a timed transition
system, where `s -d-> t` implies an intermediate state for every split of `d` —
they match exactly.

**The owner's claim is only true under time additivity.** That is therefore the
default, and it is what `--time-semantics unit` selects.

### How it is implemented

Rather than reason about splitting inside the closure operators, the input is
rewritten once, up front: every delay edge labelled `d > 1` becomes `d`
unit-delay edges through fresh intermediate states. Two things follow.

* `=d=>` becomes the `d`-fold composition of `=1=>`, so a relation that respects
  the single label `delay(1)` automatically respects every duration. The
  refinement label set stays finite with no bound on `d` and no reasoning about
  which durations are reachable.
* The intermediate instants become first-class states. That is not an artefact:
  in the Case II models, an instant seven units into one system's wait has to be
  matched against a state the other system genuinely declares.

The cost is state count: refinement adds `Σ(d − 1)` states. `--max-intermediate-states`
caps it and fails loudly rather than thrashing.

### The other reading is kept

`--time-semantics strict` implements the literal single-edge pseudocode. It
exists so the assumption stays falsifiable, and there is a test asserting that
under it the Case II models are *not* equivalent
(`CaseIIAcceptanceTest.SemanticSensitivity`). If a supervisor decides the strict
reading is intended, the oracle has to be revisited, and this flag is where that
conversation starts.

## Normalisations

| input | becomes | why |
| --- | --- | --- |
| an unobserved message server | `τ` | that is what hiding means |
| `<time value="0"/>` | `τ` | zero-duration progress carries no timing information, and a `delay(0)` label would force every closure operator to special-case it |
| a transition with no label element | `τ` | the only reading the schema's optional choice leaves open |
| a `tau=>`-prefixed message-server title | `τ` (unless named in full) | Afra marks these as resumptions of an already running message server |

`Label` makes `delay(0)` unrepresentable, so the normalisation cannot be
forgotten downstream.

## Determinism

States keep their input order; every derived collection is sorted or
insertion-ordered; blocks are numbered by the sorted id of their smallest
member. Two runs over the same input therefore produce byte-identical artefacts,
which the end-to-end suite checks directly.

## What is out of scope

* **Dense time.** Delays are whole numbers. A fractional `<time value>` is
  rejected rather than rounded.
* **Strong timed bisimilarity**, and the virtual-clock/DBM machinery that goes
  with dense-time checking.
* **Minimality of the quotient.** Weak bisimulation quotients are not canonical:
  two weakly bisimilar systems can quotient to non-isomorphic results, because
  the quotient keeps the silent structure of the system it came from. What is
  guaranteed, and verified, is that the quotient is weak timed bisimilar to its
  source. See `docs/limitations.md`.
