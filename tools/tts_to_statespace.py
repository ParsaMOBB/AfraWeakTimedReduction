#!/usr/bin/env python3
"""Serialise a `.tts` fixture into Afra's `.statespace` dialect.

Offline helper, not part of the application. It exists so the evaluation and
end-to-end suites can be driven from the same reviewable text fixtures the unit
tests use, while still exercising the production XML reader.

The output deliberately leaves the root element open, reproducing an
interrupted streaming export and exercising the production reader's tolerant
end-tag handling. See docs/afra-input-contract.md.

Usage:
    tts_to_statespace.py INPUT.tts OUTPUT.statespace
"""
from __future__ import annotations

import sys
from pathlib import Path


def parse(path: Path):
    initial = None
    edges = []
    states = []

    def note(state: str) -> None:
        if state not in states:
            states.append(state)

    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("model "):
            continue
        if line.startswith("initial "):
            initial = line[8:].strip()
            note(initial)
        elif line.startswith("state "):
            note(line[6:].strip())
        elif line.startswith("trans "):
            body = line[6:]
            arrow = body.index("->")
            colon = body.index(":", arrow)
            source = body[:arrow].strip()
            target = body[arrow + 2:colon].strip()
            label = body[colon + 1:].strip()
            note(source)
            note(target)
            edges.append((source, target, label))
        else:
            raise SystemExit(f"{path}:{number}: unrecognised line {line!r}")

    if initial is None:
        raise SystemExit(f"{path}: no 'initial' line")
    # Afra writes the initial state first; keep that order.
    states.remove(initial)
    states.insert(0, initial)
    return states, edges


def render(states, edges) -> str:
    out = ["<transitionsystem>"]
    for state in states:
        out.append(f'<state id="{state}" atomicpropositions="" >')
        out.append("</state>")
    for source, target, label in edges:
        head = (f'<transition source="{source}" destination="{target}" '
                f'executionTime="0" shift="0">')
        if label.startswith("time "):
            out.append(f'{head} <time value="{int(label[5:])}"/></transition>')
        elif label.startswith("msg "):
            owner, _, title = label[4:].strip().partition(".")
            out.append(f'{head} <messageserver sender="" owner="{owner}" '
                       f'title="{title}"/></transition>')
        elif label == "silent":
            out.append(f"{head}</transition>")
        else:
            raise SystemExit(f"unrecognised label {label!r}")
    # No closing tag: intentionally exercise interrupted-stream compatibility.
    return "\n".join(out) + "\n"


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    states, edges = parse(Path(sys.argv[1]))
    Path(sys.argv[2]).write_text(render(states, edges), encoding="utf-8")
    print(f"{sys.argv[2]}: {len(states)} states, {len(edges)} transitions")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
