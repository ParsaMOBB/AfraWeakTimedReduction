# Handoff for whoever writes the thesis or poster

You are picking up a finished, verified piece of software and turning it into a
document. This file tells you what exists, where it is, which claims you may
make, and which you may not. Read it before anything else; then read
`README.md`, then `docs/semantics.md`.

Written 2026-08-29, against commit `9041adb` ("Evaluation results from the
verified final build"). If `git log` shows later commits, re-run the verification
in §9 before quoting any number below.

---

## 1. What was built, in one paragraph

A standalone Java 17 command-line tool, `awtr`, that reads an unmodified
Afra/RMC Timed Rebeca state-space export (`.statespace`, TTS mode), hides every
interaction the user did not ask to observe, computes the partition of the
states into **weak timed bisimilarity** classes over discrete time, and emits the
quotient transition system together with a metrics report. Every reduction is
checked on the way out by a verifier written independently of the reducer. It
lives in `IndependentAfraWeakTimedReduction/`, is 3,578 lines of main code and
2,136 of test, has **no runtime dependencies outside the JDK**, and passes 106
unit tests plus 62 end-to-end checks.

---

## 2. Where everything is

### Outside this repository (all read-only — do not modify)

| path | what it is | use it for |
| --- | --- | --- |
| `Instruction.md` | the governing specification this work was built to | scope, acceptance criteria |
| `پروپوزال.pdf` | the project proposal | **motivation and expected outcome — see the warning in §8** |
| `Resources/Weak Time Bisimilutation/weak-time.txt` | the supervisor-approved pseudocode | Chapter 3; this *is* the algorithm |
| `Resources/Weak Time Bisimilutation/Definition.jpg` | Definition 9, weak timed bisimulation | Chapter 2 definitions |
| `Resources/Weak Time Bisimilutation/2412.15799v2.pdf` | *Checking Timed Bisimilarity with Virtual Clocks* | related work (dense-time, strong) |
| `Resources/Rebeca/`, `Resources/other sources/` | Rebeca and timed-systems literature | Chapter 2 background |
| `Examples/case II/*.png`, `Smart-both.pdf` | the owner's acceptance diagrams | figures, and the evaluation subject |
| `Thesis/tehran-thesis/` | the LaTeX thesis template (UT) | the document itself |
| `Poster/tehran-poster/` | the LaTeX poster template | the poster |
| `StrongTimedBisimulation/`, `Draft/`, `Samples/` | earlier and third-party code | **not used here; see §8** |

`Thesis/tehran-thesis` has two uncommitted files (`main.tex`,
`tex/thesis_preamble.tex`). They were deliberately left alone. Commit or stash
them before you start editing, or you will lose them.

### Inside this repository

| path | what it holds |
| --- | --- |
| `README.md` | orientation, commands, results table |
| `docs/semantics.md` | the discrete weak timed semantics as implemented — **the intellectual core** |
| `docs/afra-input-contract.md` | the `.statespace` dialect, derived from official sources |
| `docs/afra-integration.md` | toolchain compatibility, the future in-process adapter |
| `docs/design.md` | architecture and the reasoning behind each decision |
| `docs/case-ii-provenance.md` | how the three diagrams became test fixtures |
| `docs/traceability.md` | pseudocode step → code → test, and criterion → where met |
| `docs/limitations.md` | what is unproven, unsupported, or needs a decision |
| `src/main/java/ir/ut/ce/awtr/` | the implementation, eight packages |
| `src/test/resources/caseii/*.tts` | the transcribed Case II systems, one edge per line |
| `src/test/resources/legacy/` | the ten recorded acceptance cases + `manifest.txt` |
| `src/test/resources/afra/` | the Afra export fixture + its provenance note + the Rebeca model |
| `evaluation/results.csv` | **the only place thesis numbers may come from** |
| `evaluation/raw/<case>/` | per-run `metrics.json`, `reduced.dot`, `reduced.json`, `partition.json` |
| `evaluation/environment.json` | OS, CPU, JVM, timestamp of the recorded run |
| `tests/e2e/run_e2e.py` | the end-to-end suite |
| `tools/tts_to_statespace.py` | offline fixture → Afra-format converter |

---

## 3. The story the thesis has to tell

**Problem.** Afra's Timed Rebeca model checker produces explicit timed
transition systems that are large and full of internal detail. If you only care
about a handful of message servers, most of that detail is noise, and there is no
tool that removes it while preserving observable *timed* behaviour.

**Approach.** Take the supervisor-approved discrete weak-timed-bisimilarity
algorithm, apply it directly to Afra's export, and emit the quotient.

**Contribution.** A working, verified reducer; a documented reading of Afra's
export contract derived from official sources; and — the part with actual
intellectual content — **the identification and resolution of an ambiguity in
the approved pseudocode.**

### The finding to build Chapter 3 around

`weak-time.txt` writes the weak delay closure as

> `DelayClosure(s, d)`: for `x ∈ TauClosure(s)`, for `(x, d, y) ∈ →`, for
> `z ∈ TauClosure(y)`: add `z`

— that is, **a single `d`-labelled edge** wrapped in silent steps. But its own
comment says "all states reachable from `s` by exactly `d` units of delay", and
Definition 9 quantifies over `d ∈ ℝ≥0` with `q =d=> q'` defined by "there is a
run `w` with `Untimed(w) = ε` and `Duration(w) = d`". Those are different
relations.

The owner's Case II oracle decides between them:

| model | how the same period is spent |
| --- | --- |
| `SmartHome.png` | one 10-unit step |
| `SmartHome-tc2step.png` | 7 units, an internal `room.CARRIER_CHANGE_OF_TEMP`, 3 units |
| `SmartHome-notify.png` | 3 units, an internal `notifyer.send_signal`, 7 units |

The owner declares all three pairwise weak timed bisimilar. Under the literal
single-edge reading they are **not**: the first system has no 7-unit edge with
which to answer the second's. Under the run-based reading — the time-additivity
axiom of a timed transition system, where `s -d-> t` implies an intermediate
state for every split of `d` — they are.

So the pseudocode as literally written cannot satisfy the project's own
acceptance oracle. The implementation takes the run-based reading as the
default, keeps the literal one behind `--time-semantics strict`, and has a test
(`CaseIIAcceptanceTest.SemanticSensitivity`) asserting the oracle fails under
`strict`. That test is your evidence; cite it.

**Implementation trick worth a paragraph:** rather than reason about splitting
inside the closure operators, the input is rewritten once so every delay edge of
length `d` becomes `d` unit edges. Then `=d=>` is the `d`-fold composition of
`=1=>`, so a relation respecting the single label `delay(1)` automatically
respects every duration. The refinement label set stays finite with no bound on
`d`. `docs/semantics.md` has the argument in full.

---

## 4. Every number you may cite, and where it comes from

**Rule: if a number is not in `evaluation/results.csv` or a
`evaluation/raw/*/metrics.json`, do not put it in the thesis.** Regenerate, do
not transcribe.

### Reduction results (`evaluation/results.csv`)

| case | original | refined | classes | reduced | state red. | trans. red. |
| --- | --- | --- | --- | --- | --- | --- |
| tiny | 6 st / 7 tr | 11 / 12 | 5 | **2 / 2** | 66.7 % | 71.4 % |
| smart-home | 16 / 18 | 34 / 36 | 28 | **10 / 12** | 37.5 % | 33.3 % |
| smart-home-tc2step | 25 / 28 | 41 / 44 | 28 | **10 / 12** | 60.0 % | 57.1 % |
| smart-home-notify | 42 / 52 | 85 / 95 | 28 | **10 / 12** | 76.2 % | 76.9 % |

"refined" is after unit-delay refinement; explain that column or drop it, but do
not present it as the input size.

**The result worth leading with:** all three Case II models produce the *same*
28 classes and the *same* 10-state, 12-transition quotient. Three independently
transcribed diagrams converging on one quotient is stronger evidence for the
owner's equivalence claim than a checker returning "yes", because a bug that
merges too much would have to merge three different graphs to the same wrong
answer.

### Performance (same file; environment in `evaluation/environment.json`)

Recorded on Darwin 23.6.0, arm64, 8 CPUs, OpenJDK 17.0.10.

| case | in-process ms | wall-clock ms | peak heap |
| --- | --- | --- | --- |
| tiny | 50 | 130.3 | 6.2 MB |
| smart-home | 55 | 137.0 | 7.2 MB |
| smart-home-tc2step | 58 | 139.5 | 7.2 MB |
| smart-home-notify | 63 | 153.3 | 8.3 MB |

These are dominated by JVM startup. **Do not draw a scaling curve from them** —
see §8.

### Test totals

106 unit tests (0 failures, 0 errors, 0 skipped) + 62 end-to-end checks.
Breakdown: legacy baseline 21, Case II 12, specification-level semantics 23,
quotient soundness incl. 5 mutation rejections 23, Afra reader incl. 8 rejection
cases 17, architecture + adapter contract 8, visualization 2.

### Toolchain

Java 17, Maven, compiler plugin 3.11.0, surefire 3.2.2, JUnit Jupiter 5.10.1 —
each pinned to the official Afra value. Revisions in `docs/afra-integration.md`.

---

## 5. Figures you already have, and how to make more

| figure | source | notes |
| --- | --- | --- |
| the three Case II inputs | `Examples/case II/*.png` | Afra's own Graphviz output. Large; crop or re-render. Note `SmartHome-notify.png` carries the owner's red correspondence annotations — worth showing |
| **the reduced SmartHome model** | `evaluation/raw/smart-home/reduced.dot` | 10 states, 12 transitions, clean. `dot -Tpdf` it. This is your before/after money shot |
| reduced tc2step / notify | `evaluation/raw/<case>/reduced.dot` | identical graph up to state naming — good for a "all three converge" figure |
| pipeline diagram | none yet | acquire → hide → refine → partition → quotient → verify. Draw it from `docs/design.md` §Layering |
| the additivity example | none yet | worth drawing: 10 vs 7+τ+3, and the unit refinement that reconciles them |

Regenerate all `.dot` files with `python3 evaluation/run_evaluation.py`. Render
with Graphviz: `dot -Tpdf evaluation/raw/smart-home/reduced.dot -o fig.pdf`.

For LaTeX/TikZ figures the template supports `tikz` and `automata`; there is no
TikZ exporter in this tool and you do not need one for four small graphs.

---

## 6. Chapter-by-chapter source map

Following the ordering in the template (`Thesis/tehran-thesis/tex/chapterN.tex`):

| chapter | draw from |
| --- | --- |
| **1 — problem, motivation, contribution** | `پروپوزال.pdf` (read it first, §8), `README.md` §what it does, §3 above |
| **2 — background** | Rebeca and Timed Rebeca: `Resources/Rebeca/*`, `Resources/other sources/Rebeca_Theory_Applications_and_Tools.pdf`, `Marjan-Sirjani-PhD-Thesis.pdf`. Afra and TTS: `docs/afra-input-contract.md` (and cite the official repos, not this doc). Bisimulation: `Definition.jpg`, `2412.15799v2.pdf` for the strong/dense-time contrast |
| **3 — the algorithm and design choices** | `weak-time.txt`, `docs/semantics.md`. **This is where the time-additivity finding goes.** Include the strict-vs-unit comparison and the `SemanticSensitivity` test as evidence |
| **4 — implementation** | `docs/design.md` (layering, adapter boundary, no-dependencies rationale, splicing), `docs/afra-input-contract.md` (the reader), `docs/afra-integration.md` (toolchain) |
| **5 — tests and evaluation** | `docs/traceability.md` (criterion → test), §4 above, `evaluation/results.csv`. Emphasise: two independent acceptance oracles, an independent verifier, and mutation tests proving the verifier can say no |
| **6 — limitations, future work, conclusion** | `docs/limitations.md` — nearly verbatim; it was written for this |
| **appendices** | `docs/traceability.md` as a table; `docs/case-ii-provenance.md` as the transcription record; the `.tts` fixtures as reviewable edge tables |

The pseudocode-to-code-to-test traceability table that a thesis of this kind
needs already exists at `docs/traceability.md`. Reformat it, do not rebuild it.

---

## 7. Claims you must NOT make

Every one of these is a real trap; each is defensible only in the weaker form
given.

| tempting claim | why it is wrong | say instead |
| --- | --- | --- |
| "the tool produces the minimal model" | weak bisimulation quotients are **not** canonical; the quotient keeps the silent structure of its source | "produces a reduced model proven weak timed bisimilar to its source" |
| "the tool scales to industrial models" | largest model tested is 42 states | "evaluated on models up to 42 states; scaling is future work" |
| "runtime grows as …" | the measurements are dominated by JVM startup | quote the numbers, draw no curve |
| "the export fixture came out of Afra" | it is a field-by-field back-transcription of Afra's own rendering | "reconstructed from Afra's Graphviz rendering in RMC's exact byte format; provenance gap recorded" |
| "the tool implements the approved pseudocode" | it implements a *disambiguated* reading of it | "implements the pseudocode under the run-based delay semantics of Definition 9; the literal reading is available and shown to fail the oracle" |
| "supports Timed Rebeca models" | it reads state spaces, not Rebeca source | "consumes Afra TTS state-space exports" |
| "supports dense time" | explicitly out of scope; fractional durations are rejected | "discrete time only" |
| "FTTS exports are supported" | FTTS carries no `<time>` elements, and this is not even detected | "TTS exports only; FTTS detection is future work" |
| "reduced.statespace is lossless" | one `sender` slot per edge, no class membership | "an Afra-compatible view; `reduced.json` is the lossless artefact" |

Also: the reduction percentages depend entirely on the observable set. Always
state the observable set next to any percentage. `awtr` records it in every
artefact precisely so this cannot be lost.

---

## 8. Open items and warnings

### Needs the supervisor before the thesis commits to it

1. **The time-additivity reading (§3).** This is the load-bearing assumption.
   If the supervisor rules that the literal single-edge pseudocode is intended,
   the Case II oracle is unsatisfiable and *that* becomes the finding instead.
   Either way the thesis has a result; you need to know which one.
2. **The Afra export provenance.** `src/test/resources/afra/README.md` gives the
   four steps to produce a first-party export from Afra. Doing that closes the
   one acceptance criterion currently met in format but not in provenance.
3. **Thesis and poster repository locations** were never decided; `Instruction.md`
   says they must be confirmed with the owner.

### The proposal has not been mined

`پروپوزال.pdf` is listed in `Instruction.md` as a permitted source, and it is
the natural source for motivation and for whatever reduction the project
originally promised. **It was not read during implementation** — the
implementation was driven by `weak-time.txt` and the Case II oracle. Read it
before writing Chapter 1, and check whether it makes any quantitative promise
this evaluation should be measured against.

### Clean-room history you should know about

This work was built as an independent clean-room implementation. The earlier
implementation in `StrongTimedBisimulation/` and the code in `Draft/` were **not**
consulted: no production source, no docs, no git history. Only three things from
`StrongTimedBisimulation/` were used, all explicitly permitted as black-box
acceptance data: `tests/e2e/weak/manifest.json`, `tests/e2e/weak/models/`, and
`tests/e2e/weak/run_weak_e2e.py`.

One disclosure to carry forward: `Instruction-directed.md` — an archived earlier
plan whose header says it must not inform the independent work — was read
earlier in the same session, before the instruction not to read it was given.
Every design decision here is independently derivable from the permitted sources
(the `d = 0` clause from Definition 9's `ℝ≥0` quantification and from
`weak-time.txt`'s own `DelayClosure(s,0) = τ*`; the time-additivity finding from
the Case II diagrams). If the thesis makes a clean-room claim, state this
honestly rather than omitting it.

If the thesis compares against the earlier implementation, note that the two
solve *different* problems: `StrongTimedBisimulation` compares two canonical
timed automata; this tool reduces one Afra TTS. A head-to-head table would be
misleading.

---

## 9. How to verify everything before you cite it

```bash
cd IndependentAfraWeakTimedReduction
mvn clean package                      # expect: 106 tests, 0 failures/errors/skipped
python3 tests/e2e/run_e2e.py           # expect: 62/62 checks passed
python3 evaluation/run_evaluation.py   # rewrites evaluation/results.csv + raw/
git rev-parse HEAD                     # the commit to cite
```

`metrics.json` records the commit hash of the build that produced it, so every
number is traceable to a specific revision. If `results.csv` and `git log`
disagree, re-run the evaluation.

To reproduce the semantic finding directly:

```bash
java -jar target/awtr.jar equivalent \
  evaluation/models/smarthome.statespace \
  evaluation/models/smarthome-tc2step.statespace \
  --observable getSense,activateh,switchoff
# -> WEAK_TIMED_BISIMILAR, exit 0

java -jar target/awtr.jar equivalent \
  evaluation/models/smarthome.statespace \
  evaluation/models/smarthome-tc2step.statespace \
  --observable getSense,activateh,switchoff --time-semantics strict
# -> NOT_WEAK_TIMED_BISIMILAR, exit 1
```

---

## 10. Terminology to keep consistent

Pick these and do not drift; the code and docs already use them.

| use | not |
| --- | --- |
| observable interaction / message server | "action" alone (ambiguous with Afra's `title`) |
| silent step, τ | "internal action", "epsilon" (except when quoting Definition 9) |
| delay transition of `d` time units | "time transition", "tick" |
| weak timed bisimilarity **class** | "block" in prose (fine in code) |
| quotient / reduced model | "minimised model" (see §7) |
| the export / the state space | "the model" (ambiguous with the Rebeca model) |
| time additivity | "delay splitting", "unfolding" |

Notation: `s -l-> t` for a transition of the input, `s =l=> t` for a weak
transition, `τ*(s)` for the tau closure. `Act` for observable interactions.
Durations are in ℕ.

---

## 11. Gotchas that will cost you an afternoon

* **A genuine Afra export is not well-formed XML.** RMC opens
  `<transitionsystem>` and never closes it. If you open one in an XML viewer it
  will look corrupt. It is not. This is worth a sentence in Chapter 4.
* **Observable matching is case sensitive.** The Case II diagrams were rendered
  by two different Afra versions, one uppercase (`GETSENSE`) and one lowercase
  (`getsense`). The transcriptions normalise to the owner's canonical spelling;
  the first-party export keeps upper case. Comparing across them returns "not
  equivalent", correctly. `docs/case-ii-provenance.md` explains the rule.
* **Quotient state count ≠ class count.** 28 classes become 10 quotient states
  because chains of pure time-passage classes are spliced back into single
  delay edges. `partition.json` records where every input state landed, including
  those that sit *on* an edge rather than at a state. Explain this in Chapter 4
  or a reviewer will think the numbers are inconsistent.
* **The `refinedStates` column is not the input size.** It is post-unit-refinement.
* **Do not edit files outside this repository** while `Instruction.md`'s isolation
  boundary is in force, and preserve the two uncommitted files in
  `Thesis/tehran-thesis`.

---

## 12. What was deliberately not built

Strong timed bisimilarity; dense time and the virtual-clock/DBM machinery of
arXiv:2412.15799; Rebeca source compilation; the Afra plug-in; a GUI; a LaTeX/TikZ
exporter. The first two are the natural "related work" contrast for Chapter 2;
the plug-in is the natural "future work" for Chapter 6, and `docs/afra-integration.md`
already specifies the seam it would attach to.
