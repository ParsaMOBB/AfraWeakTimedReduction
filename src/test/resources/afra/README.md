# Afra integration fixture

## `smarthome-tc2step.statespace`

**Provenance.** This file is a back-transcription of a genuine Afra/RMC TTS
export. The export itself was not available to this project as a file; what was
available is `Examples/case II/SmartHome-tc2step.png`, which is Afra's own
Graphviz rendering of that export, produced by
`ConvertStateSpaceToGraphvizHandler` → `StateSpaceTransformer` →
`TimedRebecaStateSpaceGraphviz`.

That renderer's source (see `docs/afra-input-contract.md` for the exact
revisions) writes, for every transition:

```java
label = owner + "." + title;                       // messageserver
label = "time +=" + value;                         // time
label += " \n @" + executionTime
       + (shift == 0 ? "" : " -> shift(+" + shift + ")");
```

and for every state `"S" + id + ":" + atomicpropositions`. Every one of those
fields is legible in the diagram, so each was read off and written back into the
serialisation the RMC emitter produces. The recovered fields are:

| field | recovered from |
| --- | --- |
| `state/@id` | node label `S<id>:` — e.g. `S1_0:` gives `id="1_0"` |
| `transition/@source`, `@destination` | edge direction (arrowhead) |
| `transition/@executionTime` | the `@N` part of the edge label |
| `transition/@shift` | the `-> shift(+N)` part, `0` when absent |
| `messageserver/@owner`, `@title` | the `owner.title` part of the edge label |
| `time/@value` | the `time +=N` part of the edge label |

**Two fields are not recoverable and are recorded as empty**, because the
Graphviz renderer does not print them:

* `messageserver/@sender` — the renderer uses only `owner` and `title`;
* `state/@atomicpropositions` — empty in the rendering, and the model declares
  no properties.

Neither affects this tool's semantics: the reducer's action identity is
`title` (optionally qualified by `owner`), which is also what the official
`StateSpaceAnalysis` loader uses, and atomic propositions are carried as
provenance only.

**Byte format.** This reconstructed fixture intentionally has no closing root
tag, preserving the historical/interrupted stream shape used when it was
created. RMC 2.14 does write `</transitionsystem>` after a normal run. The parser
accepts both, so this fixture must remain unterminated to retain that coverage.

## Replacing this with a first-party export

To regenerate this fixture from Afra directly:

1. open the Timed Rebeca model in Afra;
2. in the project properties, set the model-checking engine to **TTS**
   (Project → Properties → Rebeca → Timed Rebeca, transition system `TTS`);
3. run *Model Check*; RMC generates and compiles the C++ model checker and
   writes `statespace.xml` next to the report, or to the path given by `-x`;
4. copy that file here and delete this note's "back-transcription" caveat.

The seven fixtures in `src/test/resources/rebeca-generated/` now satisfy the
general first-party-export criterion. This particular Case II file remains a
back-transcription until the original SmartHome source can be regenerated.

## Originating model

`smarthome-tc2step.rebeca` records the Timed Rebeca model this state space
belongs to, reconstructed from the actor names, message-server names and timing
visible in the export. It is documentation rather than the source that generated
this particular fixture.
