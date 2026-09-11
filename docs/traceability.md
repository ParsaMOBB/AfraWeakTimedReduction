# Traceability

Each step of the project pseudocode in
`Resources/Weak Time Bisimilutation/weak-time.txt`, the code that implements it,
and the tests that exercise it directly.

## Algorithm

| pseudocode | implementation | direct tests |
| --- | --- | --- |
| **Input** `TTS = (S, Act ∪ {τ}, →)`, `→ ⊆ S × (Act ∪ {τ} ∪ ℕ) × S` | `tts/TransitionSystem`, `tts/Label` (`TAU` / observable / `delay(d)`) | `WeakTimedSemanticsTest.Validation` |
| **Step 1** `TauClosure(s)` — states reachable by τ, including `s` | `weak/WeakTransitionRelation`, closure sweep in `of(...)` | `WeakTimedSemanticsTest.TauClosure` (empty, chain, cycle, prefix, suffix, branch) |
| **Step 1** `WeakAction(s, a)` — `τ* a τ*` | `weak/WeakTransitionRelation.weakTargets` | `WeakTimedSemanticsTest.Observables` |
| **Step 1** `DelayClosure(s, d)` — exactly `d` units, τ either side | `weak/WeakTransitionRelation.weakTargets` over delay labels, after `weak/UnitDelayRefinement` | `WeakTimedSemanticsTest.Delays`, `CaseIIAcceptanceTest.SemanticSensitivity` |
| — the `d = 0` case, i.e. `τ*` itself, per Definition 9's `ℝ≥0` | refined on as its own label in `WeakTimedPartitionRefiner.run()` | `WeakTimedSemanticsTest.TauClosure.branchToDeadEnd` |
| **Step 2** build `WeakTrans` | `weak/WeakTransitionRelation.of(...)` | `WeakTimedSemanticsTest`, `weakTransitionCount` in `metrics.json` |
| **Step 3** partition refinement by target blocks per label | `weak/WeakTimedPartitionRefiner` | `WeakTimedSemanticsTest.Stability`, `LegacyBaselineTest` |
| **Step 4** return the partition | `weak/Partition` (canonical block numbering) | `QuotientVerificationTest.Structure` |
| *beyond the pseudocode:* the quotient | `quotient/QuotientBuilder`, `quotient/Quotient` | `QuotientVerificationTest` |
| *beyond the pseudocode:* checking the quotient | `verify/WeakTimedRelationVerifier` | `QuotientVerificationTest.Rejects` (five mutants) |

`Definition.jpg` (Definition 9) supplies the terminology and the `d = 0` case;
`docs/semantics.md` records where it and the pseudocode differ, and which
reading is implemented.

## Afra input contract

| contract rule | implementation | test |
| --- | --- | --- |
| root `<transitionsystem>`, never closed | `afra/AfraStateSpaceSource.openTolerantly` | `acceptsUnterminatedRoot`, `acceptsClosedRoot` |
| initial state is the first `<state>` | `source/RawTransitionSystem.Builder.state` | `CommittedExport.initialStateIsTheFirstOne` |
| `--initial-state` override | `AfraStateSpaceSource(path, override)` | `initialStateOverride` |
| action identity is `title`, optionally `owner.title` | `source/ObservableSet.observableNameOf` | `CommittedExport.actionsMatchTheExport`, E2E case 5 |
| `<time value="d"/>` is the elapsed duration | `AfraStateSpaceSource.timeValue` | `CommittedExport.delaysMatchTheExport` |
| `executionTime` / `shift` are provenance | `source/RawTransition` | `CommittedExport.shiftIsProvenanceOnly` |
| nested per-actor detail is skipped | `AfraStateSpaceSource.read` | `ignoresNestedActorDetail` |
| malformed input is rejected with a location | `AfraStateSpaceSource`, `tts/InvalidModelException` | `Rejections` (8 cases) |
| `#` is reserved for generated state ids | `tts/StateIdentifiers`, both transition-system builders | `StateIdentifiersTest` |
| no DTD, no external entities | `AfraStateSpaceSource.secureFactory` | `externalEntitiesAreDisabled` |

## Acceptance criteria from `Instruction.md`

| criterion | where it is met |
| --- | --- |
| all 10 legacy case ids and booleans unchanged, each named individually | `LegacyBaselineTest` (parameterised over `legacy/manifest.txt`); a failure or skip fails the build |
| Case II: three models pairwise weak timed bisimilar | `CaseIIAcceptanceTest.Positive` |
| Case II: initial states in corresponding classes | `CaseIIAcceptanceTest.Positive.initialStatesShareAClass` |
| Case II: negative mutants rejected | `CaseIIAcceptanceTest.Mutants` (delay, label, observable set, edge, empty observable set) |
| Case II reported separately from the legacy baseline | separate test classes, separate suites in the run report |
| each reachable state in exactly one block | `QuotientVerificationTest.Structure.everyStateHasExactlyOneClass` |
| quotient states account for the partition | `QuotientVerificationTest.Structure.classesAreAccountedFor` (see `docs/design.md` on splicing) |
| all quotient endpoints exist | `QuotientVerificationTest.Structure.noDanglingQuotientEdges` |
| initial states satisfy the transfer conditions both ways | `QuotientVerificationTest.Accepts` |
| verifier separate from the refiner | `verify/WeakTimedRelationVerifier` has its own closure implementation |
| mutation tests the verifier must reject | `QuotientVerificationTest.Rejects` |
| in-memory and file adapters agree | `AdapterContractTest` |
| core independent of XML/CLI/Afra | `BoundaryTest` |
| deterministic output | `AdapterContractTest.deterministicOutput`, E2E case 7 |
| complete `.statespace` visualization without Afra | `StateSpaceDotWriter`, `awtr visualize`; `StateSpaceDotWriterTest`, E2E case 2 |
| E2E from a packaged jar | `tests/e2e/run_e2e.py`, 62 checks |
| E2E: reducible positive with exact counts | E2E case 1 |
| E2E: observable set changes the partition | E2E case 3 |
| E2E: invalid input | E2E case 4 |
| E2E: semantic mutation detected | E2E case 5 |
| evaluation on 3+ models with raw results | `evaluation/run_evaluation.py` → `results.csv`, `raw/*/metrics.json` |

## Requirement to source

| requirement | source |
| --- | --- |
| discrete time, delays in ℕ | `weak-time.txt` input line |
| observable set as an explicit runtime argument | `Instruction.md`; `--observable`, recorded in every artefact |
| every unselected interaction becomes τ | `Instruction.md`; `source/Hiding` |
| Java release, build tool, dependency versions | official poms, see `docs/afra-integration.md` |
| the `.statespace` dialect | official emitter and two official readers, see `docs/afra-input-contract.md` |
| Case II observable names and label rules | project owner, recorded in `docs/case-ii-provenance.md` |
