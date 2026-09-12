# Limitations, risks, and open questions

## Time semantics

### 1. Elapsed time is accumulated across internal steps

The project pseudocode writes the weak delay closure as a single `d`-labelled
edge wrapped in tau steps. Definition 9 and the pseudocode's own comment
describe something more general. The Case II oracle is only satisfiable under
the more general reading, so that is the default.

Between two observable actions, only the total elapsed time matters, and the
position of internal steps does not. Consequently, `3 + tau + 7`,
`2 + tau + 8`, and `10 + tau` represent the same ten-unit observable delay.
The `unit` mode implements this semantics. The `strict` mode is retained only
as a diagnostic comparison with the literal single-edge pseudocode.

`strict` is therefore not a cheaper way to compute the `unit` answer, whatever
comparison method is used: it decides a strictly finer relation, and it rejects
the Case II oracle. `delay(1)` self-loop against `delay(2)` self-loop is the
smallest witness. See
[experiments/quotient-isomorphism.md](experiments/quotient-isomorphism.md).

## Remaining provenance questions

### 2. The Case II SmartHome export is still a back-transcription

`src/test/resources/afra/smarthome-tc2step.statespace` reproduces a genuine
Afra/RMC export field by field, recovered from Afra's own Graphviz rendering of
it, in the byte format the RMC emitter produces. The original SmartHome Rebeca
source was not available, so this specific Case II export could not be
regenerated.

Two fields Afra's renderer does not print — `messageserver/@sender` and
`state/@atomicpropositions` — are recorded as empty. Neither affects this tool's
semantics. `src/test/resources/afra/README.md` gives the exact steps to replace
the file with a first-party export.

The broader provenance gap is closed: seven real, unmodified TTS exports from
RMC 2.14 are committed under `src/test/resources/rebeca-generated/`, loaded by
the production reader, and compared by `GeneratedRebecaModelsTest`. They do not,
however, establish the provenance of the separate SmartHome diagram.

### 3. The originating Rebeca model is reconstructed

`src/test/resources/afra/smarthome-tc2step.rebeca` records actor and
message-server names verbatim from the export and infers the statement bodies
from the timing. Nothing compiles or reads it; it is documentation. If the real
model is available it should replace this file.

## Known limitations

### The quotient `reduce` writes is sound, not canonical

The quotient keeps the silent structure of the system it came from, so two
weakly bisimilar systems can quotient to non-isomorphic results. What is
guaranteed — and checked by an independent verifier on every run — is that the
quotient is weak timed bisimilar to its source.

In practice the three Case II models do all reduce to the same 10-state
quotient, but that is a fact about those models, not a theorem this tool
provides about its output.

A canonical form does exist and is now implemented, as the comparison key behind
`equivalent --method reduced-iso`: the saturated quotient, whose edges come from
the weak transition relation rather than from the model's own edges, is
determined by the equivalence classes alone. It is canonical and minimal, and
correspondingly denser and bigger — 28 states against 10 on the Case II models,
because it is built over the unit-refined system. `reduce` therefore still
writes the readable form. The proof, the counterexample, and the measured
frequency of the defect are in
[experiments/quotient-isomorphism.md](experiments/quotient-isomorphism.md).

Open: a form that is canonical *and* small. Any deterministic function of the
saturated quotient is still canonical, so applying the delay-chain splicing pass
to the saturated quotient rather than to the raw one would produce one. That was
not needed to settle the question and is not implemented.

### Unit refinement costs states

Under `--time-semantics unit`, refinement adds `Σ(d − 1)` states, so a model with
a delay constant of 1000 gains 999 states for that one edge. On the evaluation
models this is negligible (16 → 34 states at worst). On a model with large
delay constants it would dominate.

`--max-intermediate-states` caps it and fails with an actionable message rather
than thrashing. A bounded-label-set formulation that avoids the blow-up is
possible but was not needed for any available model, and would be harder to
trace back to the pseudocode.

### Scale is unproven beyond 42 states

The largest model available without processing the workspace's excluded large
files has 42 states. The refinement is O(states × labels) per round with at most
`states` rounds, and finishes in single-digit milliseconds on everything here,
but **no claim is made about industrial-scale state spaces** because none was
available to test against. The evaluation reports what was actually run.

### Afra dialect coverage

* Only **TTS** exports are supported. An FTTS export contains no `<time>`
  elements at all — time progress is folded into message transitions — so it
  carries no delay steps to reduce. It is not detected and rejected specifically;
  it would simply parse as a system with no delays. **Adding an explicit FTTS
  detection and rejection would be a worthwhile small change.**
* Non-simplified exports nest per-actor detail inside `<state>`. That content is
  skipped rather than preserved, so a round trip through this tool loses it.
* `now` appears as an attribute in the TTS emitter and as an element in Afra's
  analysis loader. Neither is used here, so the discrepancy is unresolved.

### The `.statespace` output is a lossy view

A quotient edge can stand for several original message-server transitions with
different senders, and Afra's schema has one `sender` slot. Class membership has
nowhere to live in that schema either. `reduced.json` is the lossless artefact;
`reduced.statespace` exists so the result can be viewed in Afra's tooling.

### Observable matching

Matching is case sensitive and exact. There is no aliasing, so two exports that
spell the same message server differently (`GETSENSE` vs `getSense`) cannot be
compared without editing one of them. This is a deliberate consequence of
keeping Afra identifiers intact; if comparing across renderings becomes common,
an explicit `--alias canonical=variant` option would be the way to add it.

### Continuation steps

A message-server title prefixed `tau=>` — Afra's marker for resuming a partially
executed message server — is never matched by a bare observable name and is
therefore hidden. This follows Afra's own reading of those steps, but it was not
exercised against a real export containing them, because none was available.

## Not attempted

Strong timed bisimilarity, dense time, and the Afra plug-in. Rebeca source
compilation exists only as the offline `tools/rebeca_to_statespace.py` workflow;
it is not linked into the `awtr` runtime.
