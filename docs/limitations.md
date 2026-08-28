# Limitations, risks, and open questions

## Needs a supervisor decision

### 1. Time additivity is an assumption, not a citation

The approved pseudocode writes the weak delay closure as a single `d`-labelled
edge wrapped in tau steps. Definition 9 and the pseudocode's own comment
describe something more general. The owner's Case II oracle is only satisfiable
under the more general reading, so that is the default.

This is the single most consequential decision in the project. It is
implemented as a switch (`--time-semantics unit|strict`) with a test asserting
that the oracle fails under `strict`, so it can be revisited without archaeology.
**It should be confirmed explicitly before the write-up commits to it.**

### 2. The committed Afra export is a back-transcription

`src/test/resources/afra/smarthome-tc2step.statespace` reproduces a genuine
Afra/RMC export field by field, recovered from Afra's own Graphviz rendering of
it, in the byte format the RMC emitter produces. It was not copied out of a
running Afra installation, because no `.statespace` file was available to this
project and the Afra installer in the workspace was out of scope.

Two fields Afra's renderer does not print — `messageserver/@sender` and
`state/@atomicpropositions` — are recorded as empty. Neither affects this tool's
semantics. `src/test/resources/afra/README.md` gives the exact steps to replace
the file with a first-party export; until that is done, the acceptance criterion
"one small, real, unmodified TTS export produced by an official Afra/RMC
toolchain" is met in format and content but not in provenance.

### 3. The originating Rebeca model is reconstructed

`src/test/resources/afra/smarthome-tc2step.rebeca` records actor and
message-server names verbatim from the export and infers the statement bodies
from the timing. Nothing compiles or reads it; it is documentation. If the real
model is available it should replace this file.

## Known limitations

### The quotient is sound, not minimal

Weak bisimulation quotients are not canonical. Two weakly bisimilar systems can
quotient to non-isomorphic results, because the quotient keeps the silent
structure of the system it came from. What is guaranteed — and checked by an
independent verifier on every run — is that the quotient is weak timed bisimilar
to its source.

In practice the three Case II models do all reduce to the same 10-state
quotient, but that is a fact about those models, not a theorem this tool
provides. Producing a canonical minimal form would need saturation followed by
redundant-tau elimination, which trades state count for a much denser transition
relation.

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

Strong timed bisimilarity, dense time, the Afra plug-in, and any Rebeca source
compilation. All are outside this phase by `Instruction.md`.
