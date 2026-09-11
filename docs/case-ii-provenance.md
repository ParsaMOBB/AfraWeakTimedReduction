# Case II transcription provenance

The project owner supplied three diagrams and declared the systems they draw to
be pairwise weak timed bisimilar. This document records how each diagram became
a fixture, so the transcription can be reviewed against the images.

## Sources

| image | pixels | fixture | states | transitions |
| --- | --- | --- | --- | --- |
| `Examples/case II/SmartHome.png` | 794 × 1236 | `src/test/resources/caseii/smarthome.tts` | 16 | 18 |
| `Examples/case II/SmartHome-notify.png` | 2259 × 5017 | `src/test/resources/caseii/smarthome-notify.tts` | 42 | 52 |
| `Examples/case II/SmartHome-tc2step.png` | 2438 × 6410 | `src/test/resources/caseii/smarthome-tc2step.tts` | 25 | 28 |

`Examples/case II/Smart-both.pdf` was inspected and found to contain no text
layer (it is a raster image), so it contributed nothing beyond the PNGs.

## Method

The diagrams are Graphviz renderings, so the structure was recovered
geometrically rather than by eye, and then checked against a visual reading.

1. **Node boxes.** A Graphviz node ellipse is the only *closed* curve in the
   drawing, so its interior is a white region the outer background cannot reach.
   Flood filling the background from the image border isolates every node; the
   enclosed regions were filtered by area and by the fill ratio of an ellipse
   (`π/4 ≈ 0.785`) to discard letter counters and the regions that crossing
   edges happen to enclose.
2. **Edges.** Subtracting the node outlines leaves one ink component per edge
   spline. Each component's extremities were matched to the nearest node, and
   the arrowhead — the densest 9×9 neighbourhood on the component, since an
   arrowhead is a filled triangle and a spline is a two-pixel line — gave the
   direction.
3. **Labels** were read visually from tiled crops at 150 %.
4. **Merged components** — where two splines cross and become one ink component
   — were resolved by hand from the crops; there were four in total.

Two rendering details made this reliable, and both are confirmed by Afra's own
source (`docs/afra-input-contract.md`):

* `SmartHome-notify.png` draws edges in blue-grey (`#546E7A`) and node outlines
  in black, so the two separate by hue directly;
* `SmartHome-tc2step.png` draws `time += d` edges in **red**, which is exactly
  what `TimedRebecaStateSpaceGraphviz` does:
  `label += "\", style=\"bold\", color=\"red"` for a `<time>` transition. Delay
  edges are therefore identified by colour, not by reading the label.

The analysis scripts were one-off tools; they are not part of the application,
and image parsing is deliberately not a product feature.

## Label normalisation

The observation rules applied during transcription were:

| in the diagram | in the fixture | rule |
| --- | --- | --- |
| `controller.getsense[20].[]`, `controller.GETSENSE @0` | `msg controller.getSense` | capitalisation differences introduced by diagram rendering map to the owner's canonical spelling — **during transcription only** |
| `hc_unit.activateh[].[]`, `hc_unit.ACTIVATEH @0` | `msg hc_unit.activateh` | as above |
| `hc_unit.switchoff[].[]`, `hc_unit.SWITCHOFF @10` | `msg hc_unit.switchoff` | as above |
| `room.tempchange[20].[]` and `room.tempchange[21].[]` | both `msg room.tempchange` | parameters are not additional observable message names |
| `@10 -> shift(+10)` | dropped | timestamps and shift annotations are not observable message names |
| `time +=10` | `time 10` | an exact discrete delay transition labelled `d` |

Production Afra identifiers keep their actual case-sensitive identity; this
normalisation applies to the transcription and nowhere else. The end-to-end
suite pins that down: comparing a canonically-spelled fixture against the
first-party export that spells the same message servers in upper case returns
"not equivalent", as it must.

Observable set for every Case II assertion: `getSense`, `activateh`,
`switchoff`. Every other label is hidden.

## Initial states

| fixture | initial state | evidence |
| --- | --- | --- |
| `smarthome.tts` | `S0` | drawn as a double circle |
| `smarthome-notify.tts` | `S0` | drawn as a double circle |
| `smarthome-tc2step.tts` | `S1_0` | Afra's own numbering: `stateID` 1, and the first state RMC writes |

## Cross-checks performed

* **Liveness.** Every state in all three fixtures has at least one outgoing
  transition, matching the diagrams, in which no node is a sink
  (`CaseIIAcceptanceTest.noDeadlocks`).
* **Counts.** State and transition counts are asserted against the numbers in
  the table above (`CaseIIAcceptanceTest.transcriptionShape`), so an edit to a
  fixture that changes its size fails the build.
* **The owner's red annotations.** `SmartHome-notify.png` is annotated in red
  with the `SmartHome.png` state each of its states corresponds to. Those
  annotations were *not* used to build the fixture; they are an independent
  statement of the intended correspondence, and the computed equivalence agrees
  with them.
* **Independent agreement.** All three models reduce to the same 10-state,
  12-transition quotient with 28 classes (`evaluation/results.csv`). Three
  independently transcribed diagrams landing on the same quotient is evidence
  the transcriptions are right, not just that the checker says yes.

## Reviewable edge tables

The fixtures themselves are the edge tables — one transition per line, in
`source -> target : label` form, with the diagram they came from named in the
header comment. They are intended to be read side by side with the images.

## The semantic consequence

`SmartHome.png` lets ten units pass in one step. `SmartHome-tc2step.png` lets
seven pass, takes an internal `room.CARRIER_CHANGE_OF_TEMP` step, then lets
three more pass. `SmartHome-notify.png` splits the same period as 3 + an
internal `notifyer.send_signal` + 7.

The equivalence of all three systems therefore uses accumulated weak delays: a
delay may be observed part way through and internal steps do not reset its
accumulated duration. See `docs/semantics.md`; the contract is explicit and has
a test asserting that the diagnostic `strict` mode does not satisfy it.
