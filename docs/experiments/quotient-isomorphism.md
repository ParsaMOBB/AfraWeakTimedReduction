# Experiment: decide equivalence by reducing each model and comparing the reductions

Branch: `experiment/quotient-isomorphism`.

## The proposal

`awtr equivalent` currently answers "are these two models weak timed bisimilar?"
by putting both models in one disjoint union, refining that union once, and
asking whether the two initial states landed in the same class.

The proposal under test replaces that with:

1. reduce model A on its own;
2. reduce model B on its own;
3. answer yes exactly when the two reduced models are **isomorphic** — the same
   graph up to a renaming of states.

The proposal rests on an assumption, stated by the project owner as:

> the minimal equivalent TTS under weak timed bisimulation is unique, and our
> algorithm computes the minimal one

and it came with a second hope attached: that this also removes the need to
split delay transitions into unit delays, so `--time-semantics strict` would be
enough.

## Verdict

| claim | verdict |
| --- | --- |
| reduce-then-compare is a correct decision procedure | **true, for a canonical reduced form** — proved below, implemented as `--method reduced-iso` |
| the quotient `awtr reduce` writes is such a form | **false** — it keeps whichever silent steps the input spelled out; counterexample below, and the defect shows up in randomised testing as systems grow |
| isomorphism needs brute force / backtracking | **not for these inputs** — a reduced model is rigid, so colour refinement fixes the bijection without ever branching; backtracking is kept as the safety net |
| strict time removes the need to split delays | **false** — strict time is a *different, finer* equivalence, not a cheaper way to compute the same one. It gives the wrong answer on the project's own Case II oracle |

The first row is the useful one: reduce-then-compare is sound, and a reduction
can be computed once and reused for many comparisons. The last row is the one to
be careful with: it is a change of question, not an optimisation.

## Definitions

Fix the transition system the refiner actually sees: a finite set of states `S`,
an initial state `s₀`, and labelled edges over `Act ∪ {τ} ∪ {delay(d)}`. Write

* `s ⇒ s'` for a (possibly empty) sequence of `τ` edges — the tau closure;
* `s =l=> s'` for `⇒ · —l→ · ⇒`, for every non-`τ` label `l`.

Under `--time-semantics unit` the system is first unit-refined, so the only delay
label left is `delay(1)` and `=d=>` is the `d`-fold composition of `=1=>`; under
`--time-semantics strict` the edges are used as written. Everything below holds
for whichever of the two relations the refiner is given.

**Weak timed bisimulation.** A symmetric `R ⊆ S × S` such that whenever `s R t`:

* `s ⇒ s'` implies `t ⇒ t'` for some `t'` with `s' R t'`;
* `s =l=> s'` implies `t =l=> t'` for some `t'` with `s' R t'`, for every `l`.

`≈` is the union of all of them; it is an equivalence, and it is what
`WeakTimedPartitionRefiner` computes as the coarsest stable partition — the
signature it refines on is exactly "which blocks can I reach by `⇒`, and by
`=l=>` for each `l`".

**Saturated quotient `Sat(A)`.** One state per `≈`-class of `A`, and

* `[s] —l→ [s']` whenever `s =l=> s'`;
* `[s] —τ→ [s']` whenever `s ⇒ s'` and `[s] ≠ [s']`.

This is `SaturatedQuotient`. Note what it is *not*: it is not built from the
edges the model is written with. It is built from what the states can weakly do.

**Spliced quotient `Spl(A)`.** What `awtr reduce` writes, and what
`QuotientBuilder` builds: one state per class, an edge `[s] —l→ [s']` for every
*original* edge `s —l→ s'`, `τ` self-loops dropped, and maximal chains of
classes that exist only to carry time collapsed into one summed delay edge.

## Theorem

> Let `A` and `B` be transition systems restricted to the states reachable from
> their initial states. Then `s₀ᴬ ≈ s₀ᴮ` if and only if `Sat(A) ≅ Sat(B)`, where
> `≅` is an isomorphism of labelled graphs mapping initial state to initial
> state.

**Lemma 0.** `≈` computed over the disjoint union of `A` and `B`, restricted to
the states of `A`, is `≈` computed over `A` alone.
*Proof.* The union adds no edges between the two halves, so a weak bisimulation
on `A` is one on the union, and the restriction of one on the union is one on
`A`. ∎

**Lemma 1 (moves are a property of the class, not of the state).** If `s ≈ t`
then for every label `l`, `{ [s'] : s =l=> s' } = { [t'] : t =l=> t' }`, and
likewise for `⇒`.
*Proof.* `⊆` is the transfer property of `≈` applied to `s ≈ t`; `⊇` is the same
by symmetry. ∎

Lemma 1 is what makes `Sat` well defined from a single representative per class,
and it is checked directly, not assumed: `SaturatedQuotient.stabilityViolations`
recomputes the move set of *every* member and the tests assert it is empty.

**Lemma 2.** `R_A = { (s, [s]) : s ∈ A }` is a weak timed bisimulation between
`A` and `Sat(A)`.
*Proof.* Forwards: `s ⇒ s'` gives either `[s] = [s']` (the empty move) or the
edge `[s] —τ→ [s']`, and `s =l=> s'` gives the edge `[s] —l→ [s']`; a single
edge is a weak move. Backwards: a `τ`-path `[s] = C₀ → C₁ → … → C_k` in `Sat(A)`
can be walked in `A` — by Lemma 1 every member of `Cᵢ` has a `⇒` into `Cᵢ₊₁`, so
pick one at each step and compose — and a weak `l`-move in `Sat(A)` decomposes
into such a path, one `l`-edge, and another such path, which compose in `A`
because `⇒ · =l=> · ⇒ = =l=>`. ∎

**Proof of the theorem.**

(⇒) Assume `s₀ᴬ ≈ s₀ᴮ` and take `≈` over the disjoint union (Lemma 0 says this
is consistent with each side's own classes). Let `φ([s]) = [s]`: the identity on
classes, read as a map from the states of `Sat(A)` to those of `Sat(B)`.

*`φ` is total and onto*, i.e. a class populated in `A` is populated in `B` and
conversely. Induct on the length of a path in `Sat(A)` from `[s₀ᴬ]`. Base:
`[s₀ᴬ] = [s₀ᴮ]` by assumption. Step: if `C` is populated in both and
`C —l→ C'` in `Sat(A)`, then some `u ∈ C ∩ A` has `u =l=> u' ∈ C'`; any
`v ∈ C ∩ B` has, by Lemma 1, a `v =l=> v' ∈ C'`, so `C'` is populated in `B`
too. Every state of `Sat(A)` is reachable from `[s₀ᴬ]` because every edge of `A`
is a weak move and `A` is reachable.

*`φ` preserves edges both ways*: `C —l→ C'` in `Sat(A)` iff the members of `C`
can weakly do `l` into `C'` iff (Lemma 1, which does not care which system the
member lives in) the same holds in `B` iff `C —l→ C'` in `Sat(B)`. The `τ` case
is identical. And `φ([s₀ᴬ]) = [s₀ᴮ]`.

(⇐) Assume `ψ : Sat(A) ≅ Sat(B)` with `ψ([s₀ᴬ]) = [s₀ᴮ]`. An isomorphism is in
particular a strong bisimulation, hence a weak one. With Lemma 2 on both sides
and transitivity of `≈` over the disjoint union of all four systems:
`s₀ᴬ ≈ [s₀ᴬ] ≈ ψ([s₀ᴬ]) = [s₀ᴮ] ≈ s₀ᴮ`. ∎

**Corollary (canonicity).** Weak timed bisimilar systems have isomorphic
saturated quotients. So `Sat` *is* the unique minimal form the owner's
assumption is about — the assumption is correct once "the reduced model" is read
as the saturated quotient.

**Corollary (minimality and rigidity).** No two distinct states of `Sat(A)` are
`≈` (they would pull their classes together through Lemma 2), so `Sat(A)` is
minimal, and the isomorphism in the theorem is *unique*: any isomorphism is a
bisimulation, and in a minimal system a state has only one bisimilar partner.
Practically: the isomorphism test never has a real choice to make.

## Why the quotient `reduce` writes is not canonical

`Spl(A)` keeps the silent structure of the model it came from. Take

```
A:  s0 —τ→ s1,  s1 —τ→ s2,  s0 —τ→ s2,   s0 —a→ e,  s1 —b→ e,  s2 —delay(1)→ e
B:  t0 —τ→ t1,  t1 —τ→ t2,               t0 —a→ f,  t1 —b→ f,  t2 —delay(1)→ f
```

`A` differs from `B` only by the edge `s0 —τ→ s2`, which `B` already provides
through `s1`. Adding a silent step that the tau closure already contains cannot
change any weak move, so `A ≈ B`. But `s0`, `s1` and `s2` have strictly
decreasing capabilities (`a,b,delay` — `b,delay` — `delay`), so the three sit in
three different classes, the redundant edge survives into the quotient, and
`Spl(A)` has one more transition than `Spl(B)`. They are not isomorphic:
reduce-then-compare on the spliced form reports a **false negative**.

This is pinned as `ReducedFormComparisonTest.splicedQuotientIsNotCanonical`, and
`docs/limitations.md` already recorded the underlying fact ("the quotient is
sound, not minimal"); this experiment turns it into a decided question.

The defect is real but rare, which is exactly why it cannot be dismissed by
testing (`ReducedFormSizeSweepTest`, 300 equivalent-by-construction pairs per
size, seed 99):

| generated states | equivalent pairs | spliced-form false negatives |
| --- | --- | --- |
| ≤ 5 | 300 | 0 |
| ≤ 8 | 300 | 1 |
| ≤ 12 | 300 | 1 |

The other direction is safe: `Spl(A)` is weak timed bisimilar to `A` (verified on
every run by `WeakTimedRelationVerifier`), so isomorphic spliced quotients do
imply equivalence. Comparing spliced quotients can only ever say "no" when the
answer is "yes", never the reverse.

## Testing isomorphism

`TransitionSystemIsomorphism` decides it in three stages:

1. **Invariants** — state count, transition count, multiset of labels.
2. **Colour refinement** (1-dimensional Weisfeiler-Leman) over the disjoint union
   of both graphs. Each state starts coloured by "am I the initial state", and is
   recoloured by its own colour plus the sorted multiset of `(label, colour)`
   pairs on its outgoing and incoming edges, until the number of colours stops
   growing. Any isomorphism preserves these colours, so if a colour holds a
   different number of states on each side, the answer is no.
3. **Backtracking** over the surviving candidates, most-constrained state first,
   rejecting a partial map as soon as one edge among the already-mapped states
   fails to correspond.

Whatever the search returns is then re-checked from scratch by
`TransitionSystemIsomorphism.violations`, an edge-by-edge comparison of the
delivered bijection that shares no reasoning with the search.

**On the hashing idea.** Hashing a node from the hashes of its successors is
well founded on a tree or a DAG and has no bottom to start from on a cyclic
graph. Colour refinement is the sound repair: it is the same recursion, run to a
fixed point instead of bottom-up, and the fixed point is well defined on any
graph. What it loses is completeness — equal colourings do not prove
isomorphism, which is why stage 3 exists.

**On the cost.** By the rigidity corollary, a saturated quotient has no two
bisimilar states, colour refinement is at least as fine as bisimilarity, so every
colour class ends up a singleton and the bijection is forced. Across every test
and every model in this repository the search reports "without backtracking".
Graph isomorphism is hard in general; on this input class it is not.

## The second claim: strict time is not a shortcut

Splitting delay edges into unit steps is what makes `=d=>` additive: it is how
"wait 10" and "wait 3, do something internal, wait 7" become the same behaviour.
`--time-semantics strict` does not compute that relation more cheaply — it
computes a different, finer one. The smallest witness:

```
tick-1:  s0 —delay(1)→ s0
tick-2:  t0 —delay(2)→ t0
```

Both systems do nothing but let time pass forever, so they are weak timed
bisimilar in the run-based reading; under strict time `tick-1` has a one-unit
step `tick-2` has not. No comparison method can repair that, because the
disagreement is in the semantics and not in the method
(`ReducedFormComparisonTest.strictTimeIsNotAShortcut`).

It also loses the project's own acceptance oracle. `SmartHome` and
`SmartHome-tc2step` are declared weak timed bisimilar by the project owner, and
`tc2step` is precisely the model that takes two time steps where `SmartHome`
takes one:

| method | `--time-semantics unit` | `--time-semantics strict` |
| --- | --- | --- |
| `union` | bisimilar | **not bisimilar** |
| `reduced-iso` | bisimilar | **not bisimilar** |
| `reduced-iso-spliced` | bisimilar | bisimilar |

(`ReducedFormComparisonTest.caseIIOracleNeedsTheRunBasedReading` and
`evaluation/method-experiment.csv`.)

The bottom row is worth a warning rather than a celebration. The spliced form
happens to recover the right answer here because its splicing pass collapses a
chain of time-only classes into one summed delay edge, which re-introduces
additivity *after* the fact. It only does so when the intermediate class has
exactly one delay edge in and one delay edge out; where an instant carries a
choice it does not, and the answer reverts. In the randomised run below that
combination is wrong on 3 of 400 pairs. It is an accident of the artefact, not a
semantics.

If the goal is to avoid the `Σ(d − 1)` states that unit refinement adds, the
thing to attack is the refinement, not the comparison method — a bounded or
symbolic representation of `=d=>` — and that remains open. See
`docs/limitations.md`.

## Results

### Randomised differential (`ReducedFormComparisonTest`, seed 20260913)

400 pairs of random systems on up to 5 states over `{τ, a, b, delay(1),
delay(2)}`. Every second pair is an equivalent-by-construction mutant of its
partner (renaming, a redundant silent step, a silent self-loop, or a duplicated
state), so both answers occur. The union answer under the same time semantics is
the reference.

| run | reference says yes | `reduced-iso` wrong | `reduced-iso-spliced` false no | false yes |
| --- | --- | --- | --- | --- |
| `unit` | 239 / 400 | 0 | 0 | 0 |
| `strict` | 236 / 400 | 0 | 0 | 0 |
| `reduced-iso-spliced --strict`, scored against the `unit` reference | 239 / 400 | — | 3 | 0 |

### Real Afra exports (`evaluation/method-experiment.csv`)

All pairs of the four committed `.statespace` exports, plus a mutant of each
(one delay constant lengthened by one unit) and a self-comparison: 54
comparisons. `reduced-iso` agreed with `union` under the same time semantics in
every single one. The one disagreement in the whole table is
`reduced-iso-spliced` under strict time on `smarthome` vs `smarthome-tc2step`,
discussed above.

Size of what gets compared, Case II under `unit`:

| form | states | transitions |
| --- | --- | --- |
| original `smarthome` | 16 | 18 |
| saturated quotient | 28 | 38 |
| spliced quotient (`reduce` output) | 10 | 12 |

The saturated form is bigger, and on purpose: it is a comparison key, not a
document. It is built over the unit-refined system, so every intermediate
instant is a class, and it carries every implied weak edge. Reading a reduction
is still the job of `awtr reduce`.

Runtimes on these models are between 82 ms and 126 ms for every method and are
dominated by JVM start-up; they say nothing about the algorithms. The asymptotic
statement is the useful one: `reduced-iso` costs one refinement per model plus a
forced, linear-ish isomorphism check, versus one refinement of the union — the
same order for a single comparison, and strictly better when one model is
compared against many, because each reduction is computed once.

## How to reproduce

```bash
mvn -q clean package                       # includes every test named above
python3 tests/e2e/run_e2e.py               # case [8] covers --method
python3 evaluation/run_method_experiment.py
```

The last one rewrites `evaluation/method-experiment.csv` and
`evaluation/raw/method-experiment.json`, and exits non-zero if `reduced-iso`
ever disagrees with `union`.

## Recommendation

* Keep `union` as the default: it is the established path, it needs no
  canonicity argument, and it reports the class count.
* `--method reduced-iso` is correct and is the one to use when the same model is
  compared repeatedly, or when a reduction is going to be cached or shipped.
* `--method reduced-iso-spliced` should stay experimental. It is the literal
  version of the original proposal and it is kept so the counterexample has
  something to run against; it warns on stderr.
* Do not use `--time-semantics strict` as a performance setting.

Open follow-up: a canonical form that is also small. Any deterministic function
of `Sat(A)` is still canonical, so a splicing pass applied to the *saturated*
quotient — rather than to the raw one, as `QuotientBuilder` does — would give a
readable canonical artefact. That would let `awtr reduce` emit a form that is
both the delivered document and the comparison key. It was not needed to settle
the question this branch asks, so it is not implemented.
