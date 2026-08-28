Transcriptions of the ten legacy weak-timed acceptance cases.

These are black-box regression data: the case ids and the expected booleans are
reproduced verbatim from the legacy manifest recorded on 2026-08-29. Only the
notation changed - each timed automaton is written in the small `.ta` format
read by TimedAutomatonFixture, and TimedAutomatonUnfolder turns it into the
explicit discrete TTS the approved algorithm consumes.

The transcription is mechanical: `clocks`, `locations`+`invariant`,
`initialLocationId`, and `edges`+`action`/`guard`/`resets` map one for one onto
`clock`, `loc`/`inv`, `initial`, and `edge`/`guard`/`reset`. No expected result
was changed.
