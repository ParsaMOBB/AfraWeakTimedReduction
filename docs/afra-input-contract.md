# The Afra TTS `.statespace` contract

Everything below was established from official Rebeca/Afra sources at the exact
revisions named here, and from one export produced by that toolchain. Nothing in
this document was copied from any other local project.

## Sources consulted

| what | repository | revision | file |
| --- | --- | --- | --- |
| the emitter | `rebeca-lang/org.rebecalang.rmc` | `cab60b1a7d321ac045b7722603642ec5674d6a51` | `src/main/resources/vtl/timedrebeca/analyzer/TTSPatchTemplate.vm`, `.../FTTSPatchTemplate.vm`, `src/main/resources/vtl/timedrebeca/analyzer/AbstractTimedRebecaAnalyzerCPPTemplate.vm`, `src/main/resources/vtl/common/AtomicPropositionsDefinitionTemplate.vm`, `src/main/resources/vtl/timedrebeca/MainPatch.vm` |
| the schema | `rebeca-lang/org.rebecalang.afra` | `ed3caf62b1aa019f718e3c0e0b1da325e87ee719` | `org.rebecalang.afra.ideplugin/src/.../counterexample/transition.xsd` |
| reader #1 (rendering) | `rebeca-lang/org.rebecalang.statespacetransformer` | `02fbef19475f81b2bad3f6dc7bc3491850eb5dfa` | `graphviz/CoreRebecaStateSpaceGraphviz.java`, `graphviz/TimedRebecaStateSpaceGraphviz.java`, `StateSpaceTransformer.java` |
| reader #2 (analysis) | `rebeca-lang/org.rebecalang.statespaceanalysis` | `b2b0e236df2f7e3cadee5303eca61096de4de4ef` | `statespace/StateSpaceLoader.java` |
| the export | `src/test/resources/afra/smarthome-tc2step.statespace` | — | see that directory's `README.md` for its provenance |

Two independent official readers exist, and they agree with the emitter. Where
this document states a rule, at least two of the three sources support it.

## Shape

```xml
<transitionsystem>
<state id="1_0" atomicpropositions="" >
</state>
<state id="2_0" atomicpropositions="deadline_missed," >
</state>
<transition source="1_0" destination="2_0" executionTime="0" shift="0"> <messageserver sender="room" owner="sensor" title="GETTEMP"/></transition>
<transition source="2_0" destination="3_0" executionTime="0" shift="0"> <time value="7"/></transition>
```

## Rules

### The root element is opened and never closed

`TTSPatchTemplate.vm` writes `<transitionsystem>` from `storeInitialState()`.
No template writes `</transitionsystem>`; `MainPatch.vm` simply opens the stream
and lets the process end. **A genuine export is therefore not well-formed XML.**

`AfraStateSpaceSource` scans the tail of the file and appends a synthetic end
tag when one is missing. This is the single most important compatibility
detail: a strict parser rejects real Afra output.

### The initial state is the first `<state>` in document order

`storeInitialState()` sets `current.state->stateID = 1` and calls `exportState`
before any transition is emitted. `StateSpaceLoader` relies on exactly that:

```java
if (statespace.getStates().isEmpty())
    statespace.setInitialState(currentState);
```

This tool uses the same rule. `--initial-state ID` overrides it, for exports
that were concatenated or trimmed.

### State identity

`exportState` writes `stateID + "_" + stateActiveBundleNumber`, so ids look like
`1_0`, `2_0`. The id is opaque to this tool — it is carried through as a string.

`atomicpropositions` is always written, as a comma-separated list with a
trailing comma, and is empty when the model declares no properties. Both
official readers call `.trim()` on it without a null check, so it is required in
practice even though the schema does not say so. This tool tolerates its
absence and keeps its value as provenance only.

Under `SIMPLIFIED_STATESPACE` a TTS export also carries `now="…"`. Without that
flag, `<state>` contains a nested element per actor. This tool skips any nested
content: it is detail for Afra's own viewers and carries no TTS semantics.

### Transition identity

Per `transition.xsd`, a `<transition>` carries `source`, `destination`, optional
`executionTime` and optional `shift`, and **at most one** child element —
`xs:choice minOccurs="0" maxOccurs="1"` — which is either `<messageserver>` or
`<time>`. A transition with no child is schema-valid; this tool reads it as an
unlabelled internal step.

### Action identity is the message-server `title`

`<messageserver>` requires `sender`, `owner` and `title`.

The two official readers disagree about presentation but not about identity:

* the Graphviz renderer *displays* `owner + "." + title` and ignores `sender`;
* the analysis loader takes the **action** to be `title` alone:
  `currentTransition.setAction(attributes.getValue("title"))`.

This tool therefore matches a bare observable name against `title`, and accepts
`owner.title` as a qualified form for when two actors share a message-server
name. `sender` is never part of the identity. Matching is case sensitive,
because Afra identifiers are.

One special case: `TTSPatchTemplate.vm` prefixes the title with `tau=>` when the
actor resumed a partially executed message server (`__pc != -1`) rather than
starting a new one. Afra's own naming says these are internal, so a bare
observable name never matches them and they are hidden.

### Time progress is `<time value="d"/>`, and only in TTS exports

`exportProgressOfTimeTransition` is defined in `TTSPatchTemplate.vm` and has no
counterpart in `FTTSPatchTemplate.vm`. **That is the TTS/FTTS difference that
matters here:** an FTTS export contains no `<time>` elements at all, because
time progress is folded into the message transitions. This tool needs the
explicit delay steps, so it needs a TTS export.

The value written is

```cpp
exportProgressOfTimeTransition(newState, newState2, (nextRebecTime - currentTime), currentTime, shift2, statespace);
```

so `value` is the **elapsed duration**, cast to `int`. The schema declares it
`xs:decimal`; a fractional value would mean a dense-time front end this tool
does not support, and is rejected.

### `executionTime` and `shift` are provenance, not semantics

* `executionTime` is the absolute time at which the step happened — for a delay
  step, the time *before* the progress.
* `shift` is how far the destination state's timestamps were renormalised
  backwards so that the state space stays finite. Afra's renderer shows it as
  `-> shift(+10)`.

Neither contributes to the transition-system semantics. The elapsed time of a
delay step is `<time value>` alone; adding `shift` to it would double count.
Both are preserved on `RawTransition` so nothing is lost, and neither is read as
a duration. `AfraStateSpaceSourceTest.shiftIsProvenanceOnly` pins this down.

## What is rejected

| input | why |
| --- | --- |
| a transition endpoint that is not a declared state | the relation would be ill-defined |
| a duplicate `state/@id` | state identity would be ambiguous |
| `<time value>` that is negative | time does not run backwards |
| `<time value>` that is fractional | outside discrete-time support |
| both a `<messageserver>` and a `<time>` on one transition | the schema forbids it |
| a `<messageserver>` missing `sender`, `owner` or `title` | the schema requires them |
| a document with no `<transitionsystem>` element | not a state-space export |
| a DTD or external entity | disabled outright; an export never needs one |

Each rejection carries the offending state id or the line and column.

## Open items

* The committed export is a back-transcription of a genuine Afra rendering
  rather than a file copied out of Afra; see
  `src/test/resources/afra/README.md` and `docs/limitations.md`.
* `now` appears as an attribute in the TTS emitter and as an element in
  `StateSpaceLoader`. This tool needs neither, so the discrepancy is recorded
  but not resolved.
