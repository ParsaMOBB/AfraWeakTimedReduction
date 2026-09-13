#!/usr/bin/env python3
"""The reduce-then-compare experiment, run against the real Afra exports.

For every pair of models - and a deliberately mutated copy of each, so both
answers occur - this asks the packaged application the same question three ways:

    union                one partition refinement over the disjoint union
    reduced-iso          reduce each side to its saturated quotient, then test
                         the two quotients for isomorphism
    reduced-iso-spliced  the same, comparing the quotient `reduce` writes

under both time semantics, and records every verdict. The union answer under the
same time semantics is the reference: `reduced-iso` disagreeing with it is a
failure of the claim under test and makes this script exit non-zero.

    mvn -q -DskipTests package
    python3 evaluation/run_method_experiment.py

Writes evaluation/raw/method-experiment.json and evaluation/method-experiment.csv.
Set AWTR_CMD to run something other than `java -jar target/awtr.jar`.
"""
from __future__ import annotations

import csv
import itertools
import json
import os
import platform
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent
MODELS = HERE / "models"
RAW = HERE / "raw" / "method-experiment.json"
RESULTS = HERE / "method-experiment.csv"

command = shlex.split(os.environ.get("AWTR_CMD", "")) or [
    "java", "-jar", str(ROOT / "target" / "awtr.jar")
]

METHODS = ["union", "reduced-iso", "reduced-iso-spliced"]
SEMANTICS = ["unit", "strict"]
REPEATS = 3

# Models are only comparable when they speak about the same message servers, so
# each group carries its own observable set.
GROUPS = {
    "case-ii": (
        "getSense,activateh,switchoff",
        ["smarthome.statespace", "smarthome-tc2step.statespace",
         "smarthome-notify.statespace"],
    ),
    "tiny": ("poll", ["tiny.statespace"]),
}

FIELDS = ["group", "left", "right", "observable", "method", "timeSemantics",
          "verdict", "exitCode", "leftReducedStates", "rightReducedStates",
          "bestWallClockMillis"]


def mutate(model: Path, into: Path) -> Path:
    """A copy whose first time value is one unit longer - a real behaviour change."""
    text = model.read_text()
    start = text.index('<time value="')
    end = text.index('"', start + len('<time value="'))
    units = int(text[start + len('<time value="'):end])
    mutant = into / (model.stem + "-mutant.statespace")
    mutant.write_text(text[:start] + f'<time value="{units + 1}' + text[end:])
    return mutant


def run(args: list[str]) -> tuple[subprocess.CompletedProcess, float]:
    best = None
    result = None
    for _ in range(REPEATS):
        started = time.perf_counter()
        result = subprocess.run(command + args, cwd=ROOT, text=True,
                                capture_output=True, timeout=600, check=False)
        elapsed = (time.perf_counter() - started) * 1000
        best = elapsed if best is None else min(best, elapsed)
    return result, best


def reduced_sizes(stdout: str) -> tuple[str, str]:
    left = right = ""
    for line in stdout.splitlines():
        if "reduced states" not in line:
            continue
        value = line.split("->")[1].strip().split(" ")[0]
        if line.startswith("left"):
            left = value
        elif line.startswith("right"):
            right = value
    return left, right


def compare(group: str, left: Path, right: Path, observable: str) -> list[dict]:
    rows = []
    for method, semantics in itertools.product(METHODS, SEMANTICS):
        result, millis = run(["equivalent", str(left), str(right),
                              "--observable", observable,
                              "--method", method,
                              "--time-semantics", semantics])
        verdict = "ERROR"
        for line in result.stdout.splitlines():
            if line.startswith("WEAK_TIMED_BISIMILAR"):
                verdict = "yes"
            elif line.startswith("NOT_WEAK_TIMED_BISIMILAR"):
                verdict = "no"
        left_reduced, right_reduced = reduced_sizes(result.stdout)
        rows.append({
            "group": group,
            "left": left.name,
            "right": right.name,
            "observable": observable,
            "method": method,
            "timeSemantics": semantics,
            "verdict": verdict,
            "exitCode": result.returncode,
            "leftReducedStates": left_reduced,
            "rightReducedStates": right_reduced,
            "bestWallClockMillis": round(millis, 1),
        })
    return rows


def main() -> int:
    jar = ROOT / "target" / "awtr.jar"
    if not os.environ.get("AWTR_CMD") and not jar.is_file():
        print(f"missing {jar}; run `mvn -q -DskipTests package` first", file=sys.stderr)
        return 2

    workspace = Path(tempfile.mkdtemp(prefix="awtr-method-experiment-"))
    rows: list[dict] = []
    try:
        for group, (observable, names) in GROUPS.items():
            models = [MODELS / name for name in names]
            mutants = [mutate(model, workspace) for model in models]
            pairs = list(itertools.combinations(models, 2))
            pairs += [(model, mutant) for model, mutant in zip(models, mutants)]
            pairs += [(models[0], models[0])]
            for left, right in pairs:
                print(f"  {group}: {left.name} vs {right.name}")
                rows.extend(compare(group, left, right, observable))
    finally:
        shutil.rmtree(workspace, ignore_errors=True)

    disagreements = []
    for row in rows:
        if row["method"] == "union":
            continue
        reference = next(r for r in rows
                         if r["method"] == "union"
                         and r["left"] == row["left"] and r["right"] == row["right"]
                         and r["timeSemantics"] == row["timeSemantics"])
        if row["verdict"] != reference["verdict"]:
            disagreements.append(
                f"{row['left']} vs {row['right']} [{row['timeSemantics']}]: "
                f"{row['method']}={row['verdict']} but union={reference['verdict']}")

    RAW.parent.mkdir(parents=True, exist_ok=True)
    RAW.write_text(json.dumps({
        "environment": {
            "python": platform.python_version(),
            "platform": platform.platform(),
            "command": command,
            "repeats": REPEATS,
        },
        "rows": rows,
        "disagreementsWithUnion": disagreements,
    }, indent=2) + "\n")

    with RESULTS.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)

    print(f"\n{len(rows)} comparisons written to {RESULTS} and {RAW}")
    if disagreements:
        print("\ndisagreements with the union reference:")
        for line in disagreements:
            print(f"  - {line}")
    else:
        print("every method agreed with the union reference under its own time semantics")

    fatal = [line for line in disagreements if "reduced-iso=" in line]
    return 1 if fatal else 0


if __name__ == "__main__":
    raise SystemExit(main())
