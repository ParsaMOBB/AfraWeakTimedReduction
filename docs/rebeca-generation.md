# Generating TTS state spaces from Timed Rebeca

## Result

The Afra GUI is not required. The command-line entry point in
`org.rebecalang.rmc` performs Rebeca compilation and emits a C++ model checker.
Compiling and running that generated program with TTS state-space export enabled
produces the `.statespace` input consumed by `awtr`.

The local Afra 3.0 distribution bundles RMC 2.14 and Rebeca compiler 2.30 in its
IDE plug-in. `tools/rebeca_to_statespace.py` extracts that bundled dependency in
a temporary directory and performs the complete pipeline without modifying or
starting Afra:

```text
.rebeca
  -> Rebeca compiler + RMC --tts
  -> generated C++ model checker
  -> C++11 compiler
  -> native model checker --exportStatespace
  -> .statespace
  -> awtr
```

In other words, RMC is the repository that provides state-space generation.
Afra is the IDE/orchestrator that invokes RMC, compiles its output, runs it, and
then displays the resulting state space.

Official repositories:

- <https://github.com/rebeca-lang/org.rebecalang.rmc>
- <https://github.com/rebeca-lang/org.rebecalang.afra>

## Reproduce the example exports

From the `AfraWeakTimedReduction` directory:

```bash
python3 tools/rebeca_to_statespace.py \
  src/test/resources/afra/rebeca/*.rebeca \
  --output-dir src/test/resources/rebeca-generated
```

The seven Rebeca sources are committed under
`src/test/resources/afra/rebeca/`; the command above regenerates their committed
golden exports in place.

Without command-line overrides, the script defaults to:

- Afra archive: `../Afra/Afra-macosx.cocoa.aarch64.tar.gz`
- output directory: `../Examples/models/statespace/`
- Timed Rebeca with Core Rebeca 2.3 syntax
- TTS semantics (`--tts`), not FTTS
- `java` and `c++` from `PATH`

The script overwrites the output matching each input stem. Intermediate C++ and
native files live in a temporary directory and are removed after a successful
run. Use `--afra-archive`, `--output-dir`, `--java`, or `--compiler` to override
the defaults.

## Generated models and observed results

The unmodified generated files are also committed under
`src/test/resources/rebeca-generated/` as golden integration fixtures.
`GeneratedRebecaModelsTest` reads them through the production parser and checks
these results under the `unit` time semantics:

| family | generated state spaces | observable set | result |
| --- | --- | --- | --- |
| mood | `mood1` (10 states, 12 transitions), `mood2` (6, 8) | `mood.LAUGH`, `mood.CRY` | equivalent |
| teacher | `teacher1` (19, 19), `teacher2` (7, 7), `teacher3` (19, 19) | `teacher.TEACH` | all three pairwise equivalent |
| chain | `chain1` (23, 25), `chain2` (10, 12) | `worker.DECIDE`, `controller.APPROVED`, `controller.REJECTED` | equivalent |

For example:

```bash
java -jar target/awtr.jar equivalent \
  ../Examples/models/statespace/mood1.statespace \
  ../Examples/models/statespace/mood2.statespace \
  --observable mood.LAUGH,mood.CRY
```

The observable comments in the source models are documentation for this
workflow; RMC does not copy them into the state-space XML. They must still be
passed explicitly to `awtr`. `teacher3.rebeca` has no observable comment, so its
family's `teacher.TEACH` set is inferred from `teacher1.rebeca` and
`teacher2.rebeca` for the pairwise comparison.

## Render with Afra's state-space transformer

`tools/afra_visualize.py` loads the official `StateSpaceTransformer` bundled in
the same Afra distribution and uses it to generate DOT. Graphviz then converts
that DOT to SVG; it does not reinterpret the state space.

The wide `mood` graphs are easier to read from left to right:

```bash
python3 tools/afra_visualize.py --rankdir LR \
  src/test/resources/rebeca-generated/mood1.statespace \
  src/test/resources/rebeca-generated/mood2.statespace
```

The larger `teacher` and `chain` graphs are more legible from top to bottom:

```bash
python3 tools/afra_visualize.py --rankdir TB \
  src/test/resources/rebeca-generated/teacher1.statespace \
  src/test/resources/rebeca-generated/teacher2.statespace \
  src/test/resources/rebeca-generated/teacher3.statespace \
  src/test/resources/rebeca-generated/chain1.statespace \
  src/test/resources/rebeca-generated/chain2.statespace
```

Both the official DOT and rendered SVG are written to
`docs/images/rebeca-generated/`. See
[the generated-model gallery](rebeca-visualizations.md) for all seven diagrams.

## Normal and interrupted XML output

RMC 2.14 writes `</transitionsystem>` when model checking finishes normally.
An interrupted run can leave the streaming export without that final tag, and
older fixtures may have the same shape. `AfraStateSpaceSource` deliberately
accepts both complete and unterminated files.
