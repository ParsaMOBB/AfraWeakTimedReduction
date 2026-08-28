# awtr — Afra weak timed reduction

Reduces an Afra/RMC Timed Rebeca state-space export modulo **weak timed
bisimilarity** over discrete time, and writes out the quotient transition system.

Given a `.statespace` file and the set of message servers you care about, it
hides everything else as internal, merges the states that are indistinguishable
to an observer who can see only those interactions and the passage of time, and
produces a smaller transition system with the same observable timed behaviour.

```
$ awtr reduce smarthome.statespace --observable getSense,activateh,switchoff --output-dir out
model              smarthome.statespace
observable actions getSense,activateh,switchoff
time semantics     unit
original           16 states, 18 transitions
reduced            10 states, 12 transitions
reduction          37.5% states, 33.3% transitions
verification       relation is a weak timed bisimulation (34 pairs, 172 transfer checks)
```

## Build and test

Java 17 and Maven 3.9+ are required. Both are pinned to what the official Afra
toolchain uses; see [docs/afra-integration.md](docs/afra-integration.md).

```bash
mvn clean package                      # compiles, runs 104 unit tests, builds target/awtr.jar
python3 tests/e2e/run_e2e.py           # 52 end-to-end checks against the packaged jar
python3 evaluation/run_evaluation.py   # regenerates evaluation/results.csv
```

`mvn test` alone runs the unit suite, which includes both acceptance oracles:
the ten recorded legacy weak-timed cases and the owner-provided Case II
diagrams.

## Commands

```
awtr reduce MODEL.statespace --observable NAME[,NAME...] [options]
awtr equivalent A.statespace B.statespace --observable NAME[,NAME...] [options]
awtr inspect MODEL.statespace
```

| option | meaning |
| --- | --- |
| `--observable NAME[,NAME...]` | message servers to keep visible; everything else becomes internal. A bare name matches any owner; `owner.name` matches one owner. Required — pass an empty value to hide everything. |
| `--output-dir DIR` | where the artefacts go (default `awtr-output`) |
| `--initial-state ID` | override the document-order default |
| `--time-semantics unit\|strict` | whether a `d`-unit delay may be observed part way through (default `unit`) |
| `--max-intermediate-states N` | cap on states added by unit refinement |
| `--no-verify` | skip the independent quotient check |

Exit codes: `0` success, `1` not equivalent or verification failed, `2` invalid
input, `3` internal error.

`inspect` is the quickest way to find out what an export actually contains
before choosing an observable set:

```
$ awtr inspect smarthome.statespace
states             16
transitions        18
initial state      S0
delay durations    [10]
message servers
  room.tempchange  (sender=)
  sensor.gettemp   (sender=)
  controller.getSense  (sender=)
  ...
```

## Output

| file | what it is |
| --- | --- |
| `reduced.json` | **the primary result.** The reduced system, losslessly: labels, class membership and the observable set together |
| `partition.json` | which class each input state landed in, and where it sits in the reduced model |
| `reduced.statespace` | the same system in Afra's own dialect, so it feeds back into `StateSpaceTransformer`. A lossy view — see below |
| `reduced.dot` | Graphviz, labelled the way Afra labels it |
| `metrics.json` | counts, ratios, runtime, peak heap, input hash, tool version, commit |

`reduced.statespace` is deliberately not the primary artefact: Afra's schema has
one `sender` slot per transition, but a quotient edge can stand for several
original transitions with different senders, and there is nowhere to record
class membership.

## Headless use

`Cli` only parses arguments. The whole workflow is one call:

```java
var request = ReductionRequest.of(ObservableSet.of(List.of("getSense", "activateh")));
var result  = ReductionService.reduce(new AfraStateSpaceSource(path), request);

result.reducedStateCount();      // the numbers
result.quotient().system();      // the reduced model
result.verified();               // did the independent check pass
new ArtifactWriter(outDir).writeAll(result, path.toString(), hash, version, commit);
```

## Input

An unmodified Afra/RMC export produced with **TTS** semantics. FTTS exports
carry no explicit time steps and so have nothing to reduce.

Two properties of a genuine export drive the reader, both established from the
official RMC emitter:

* the root element is opened and **never closed**, so a real export is not
  well-formed XML — the reader appends the end tag itself;
* the initial state is the first `<state>` written, which is also how Afra's own
  analysis loader identifies it.

[docs/afra-input-contract.md](docs/afra-input-contract.md) has the full contract
with the exact upstream revisions it was read from.

## One assumption worth knowing about

The approved pseudocode writes the weak delay closure as a *single* `d`-labelled
edge wrapped in internal steps. Definition 9 — and the pseudocode's own comment —
describe reachability by a run of total duration `d`.

The difference decides the acceptance oracle. One of the owner's Case II models
lets ten time units pass in one step; another lets seven pass, takes an internal
step, then lets three more pass. They are equivalent only if a delay may be
observed part way through, which is the time-additivity axiom of a timed
transition system. **That is the default**, and `--time-semantics strict`
implements the literal single-edge reading, with a test asserting the oracle
fails under it.

See [docs/semantics.md](docs/semantics.md).

## Results

From `evaluation/results.csv`, regenerated by `evaluation/run_evaluation.py`:

| model | states | transitions | reduction | verified |
| --- | --- | --- | --- | --- |
| tiny | 6 → 2 | 7 → 2 | 66.7 % | yes |
| smart-home | 16 → 10 | 18 → 12 | 37.5 % | yes |
| smart-home-tc2step | 25 → 10 | 28 → 12 | 60.0 % | yes |
| smart-home-notify | 42 → 10 | 52 → 12 | 76.2 % | yes |

The three smart-home models are the Case II diagrams. They reduce to the *same*
10-state, 12-transition quotient, which is independent evidence for the owner's
claim that they are equivalent — three separately transcribed diagrams landing
on one quotient is a stronger statement than the checker returning "yes".

## Documentation

| | |
| --- | --- |
| [afra-input-contract.md](docs/afra-input-contract.md) | the `.statespace` dialect, from official sources |
| [afra-integration.md](docs/afra-integration.md) | toolchain compatibility and the future in-process adapter |
| [semantics.md](docs/semantics.md) | the discrete weak timed semantics as implemented |
| [design.md](docs/design.md) | architecture and the decisions behind it |
| [case-ii-provenance.md](docs/case-ii-provenance.md) | how the diagrams became fixtures |
| [traceability.md](docs/traceability.md) | pseudocode step → code → test |
| [limitations.md](docs/limitations.md) | what is unproven, unsupported, or needs a decision |

## Scope

Weak timed bisimilarity over **discrete** time, for one finite transition system
at a time. Not: strong timed bisimilarity, dense time, the virtual-clock/DBM
machinery that goes with it, Rebeca source compilation, or an Afra plug-in.
