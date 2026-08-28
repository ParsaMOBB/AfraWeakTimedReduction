#!/usr/bin/env python3
"""Reproducible evaluation of the reducer.

Runs the packaged application over every model in `evaluation/models`, keeps the
raw `metrics.json` each run produced, and summarises them into `results.csv`.
Every number that appears in the write-up has to come from a file this script
wrote; nothing is transcribed by hand.

    mvn -q -DskipTests package
    python3 evaluation/run_evaluation.py

Set AWTR_CMD to run something other than `java -jar target/awtr.jar`.
"""
from __future__ import annotations

import csv
import json
import os
import platform
import shlex
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent
MODELS = HERE / "models"
RAW = HERE / "raw"
RESULTS = HERE / "results.csv"
ENVIRONMENT = HERE / "environment.json"

command = shlex.split(os.environ.get("AWTR_CMD", "")) or [
    "java", "-jar", str(ROOT / "target" / "awtr.jar")
]

# model file -> (label, observable set, what the case is meant to show)
CASES = {
    "tiny.statespace": (
        "tiny",
        "poll",
        "hand-checkable: two branches of equal total duration must collapse",
    ),
    "smarthome.statespace": (
        "smart-home",
        "getSense,activateh,switchoff",
        "the base Case II model",
    ),
    "smarthome-tc2step.statespace": (
        "smart-home-tc2step",
        "getSense,activateh,switchoff",
        "the same behaviour with the ten-unit wait split in two",
    ),
    "smarthome-notify.statespace": (
        "smart-home-notify",
        "getSense,activateh,switchoff",
        "largest available model: adds a notifier actor, 42 states",
    ),
}

FIELDS = [
    "model", "case", "observableActions", "timeSemantics",
    "originalStates", "originalTransitions",
    "refinedStates", "refinedTransitions",
    "classCount", "reducedStates", "reducedTransitions",
    "stateReductionRatio", "transitionReductionRatio",
    "refinementRounds", "weakTransitions", "splicedChains",
    "elapsedMillis", "wallClockMillis", "peakHeapBytes",
    "verified", "inputSha256", "commit", "toolVersion",
]


def environment() -> dict:
    java = subprocess.run(["java", "-version"], capture_output=True, text=True, check=False)
    return {
        "os": f"{platform.system()} {platform.release()}",
        "machine": platform.machine(),
        "cpuCount": os.cpu_count(),
        "java": (java.stderr or java.stdout).strip().splitlines()[0] if java.stderr or java.stdout else "unknown",
        "command": " ".join(command),
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }


def run_case(model: Path, label: str, observable: str) -> dict:
    out = RAW / label
    out.mkdir(parents=True, exist_ok=True)
    started = time.perf_counter()
    result = subprocess.run(
        command + ["reduce", str(model), "--observable", observable,
                   "--output-dir", str(out)],
        cwd=ROOT, text=True, capture_output=True, timeout=600, check=False)
    wall = (time.perf_counter() - started) * 1000
    if result.returncode != 0:
        print(f"  {label}: FAILED (exit {result.returncode})", file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        return {}
    metrics = json.loads((out / "metrics.json").read_text())
    metrics["wallClockMillis"] = round(wall, 1)
    return metrics


def main() -> int:
    jar = ROOT / "target" / "awtr.jar"
    if not os.environ.get("AWTR_CMD") and not jar.is_file():
        print(f"missing {jar}; run `mvn -q -DskipTests package` first", file=sys.stderr)
        return 2

    RAW.mkdir(parents=True, exist_ok=True)
    ENVIRONMENT.write_text(json.dumps(environment(), indent=2) + "\n")

    rows = []
    for filename, (label, observable, description) in CASES.items():
        model = MODELS / filename
        if not model.is_file():
            print(f"  {label}: SKIPPED, no {model}", file=sys.stderr)
            continue
        print(f"  {label}: {description}")
        metrics = run_case(model, label, observable)
        if not metrics:
            return 1
        rows.append({
            "model": filename,
            "case": label,
            "observableActions": " ".join(metrics["observableActions"]),
            "timeSemantics": metrics["timeSemantics"],
            "originalStates": metrics["originalStates"],
            "originalTransitions": metrics["originalTransitions"],
            "refinedStates": metrics["refinedStates"],
            "refinedTransitions": metrics["refinedTransitions"],
            "classCount": metrics["classCount"],
            "reducedStates": metrics["reducedStates"],
            "reducedTransitions": metrics["reducedTransitions"],
            "stateReductionRatio": metrics["stateReductionRatio"],
            "transitionReductionRatio": metrics["transitionReductionRatio"],
            "refinementRounds": metrics["refinementRounds"],
            "weakTransitions": metrics["weakTransitions"],
            "splicedChains": metrics["splicedChains"],
            "elapsedMillis": metrics["elapsedMillis"],
            "wallClockMillis": metrics["wallClockMillis"],
            "peakHeapBytes": metrics.get("peakHeapBytes", -1),
            "verified": metrics["verification"]["valid"],
            "inputSha256": metrics["inputSha256"],
            "commit": metrics["commit"],
            "toolVersion": metrics["toolVersion"],
        })

    with RESULTS.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)

    print(f"\nwrote {RESULTS} ({len(rows)} rows)")
    print(f"raw per-run metrics under {RAW}")
    width = max(len(row["case"]) for row in rows) if rows else 10
    print(f"\n{'case'.ljust(width)}  states      transitions  verified")
    for row in rows:
        print(f"{row['case'].ljust(width)}  "
              f"{row['originalStates']:>3} -> {row['reducedStates']:<3}  "
              f"{row['originalTransitions']:>3} -> {row['reducedTransitions']:<3}      "
              f"{row['verified']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
