#!/usr/bin/env python3
"""End-to-end acceptance suite for the packaged application.

Runs the jar as a separate process, from a real Afra `.statespace` file all the
way to the artefacts on disk, and checks the exit code, the numbers and the
contents of every file it wrote. Nothing here reaches inside the application:
if this suite passes, the delivered command works.

    mvn -q -DskipTests package
    python3 tests/e2e/run_e2e.py

Set AWTR_CMD to run something other than `java -jar target/awtr.jar`.
"""
from __future__ import annotations

import json
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODELS = Path(__file__).resolve().parent / "models"
EVALUATION = ROOT / "evaluation" / "models"

EXIT_OK = 0
EXIT_NEGATIVE = 1
EXIT_INVALID_INPUT = 2

command = shlex.split(os.environ.get("AWTR_CMD", "")) or [
    "java", "-jar", str(ROOT / "target" / "awtr.jar")
]

failures: list[str] = []
checks = 0


def check(condition: bool, description: str) -> None:
    global checks
    checks += 1
    if not condition:
        failures.append(description)
        print(f"  FAIL  {description}")
    else:
        print(f"  ok    {description}")


def run(args: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(command + args, cwd=cwd or ROOT, text=True,
                          capture_output=True, timeout=180, check=False)


def reduce_model(name: str, model: Path, observable: str,
                 extra: list[str] | None = None) -> tuple[subprocess.CompletedProcess, Path]:
    out = Path(tempfile.mkdtemp(prefix=f"awtr-e2e-{name}-"))
    result = run(["reduce", str(model), "--observable", observable,
                  "--output-dir", str(out)] + (extra or []))
    return result, out


def case_reducible_positive() -> None:
    """A model with an exactly known reduction, checked artefact by artefact."""
    print("\n[1] reducible model with exact expected counts")
    result, out = reduce_model("tiny", MODELS / "tiny.statespace", "poll")
    check(result.returncode == EXIT_OK, f"exit code 0 (got {result.returncode})")

    for artefact in ("partition.json", "reduced.json", "reduced.statespace",
                     "reduced.dot", "metrics.json"):
        check((out / artefact).is_file(), f"wrote {artefact}")

    metrics = json.loads((out / "metrics.json").read_text())
    check(metrics["originalStates"] == 6, "6 original states")
    check(metrics["originalTransitions"] == 7, "7 original transitions")
    check(metrics["reducedStates"] == 2, f"2 reduced states (got {metrics['reducedStates']})")
    check(metrics["reducedTransitions"] == 2,
          f"2 reduced transitions (got {metrics['reducedTransitions']})")
    check(metrics["observableActions"] == ["poll"], "observable set recorded in metrics")
    check(metrics["timeSemantics"] == "unit", "time semantics recorded")
    check(len(metrics["inputSha256"]) == 64, "input hash recorded")
    check(metrics["verification"]["valid"] is True, "independent verifier accepted the quotient")
    check(metrics["verification"]["transferChecks"] > 0, "verifier actually checked something")

    reduced = json.loads((out / "reduced.json").read_text())
    check(reduced["initialState"] == "q0", "initial class is q0")
    covered = sorted(s for state in reduced["states"] for s in state["represents"])
    check(len(covered) == len(set(covered)), "no input state is represented twice")
    check(set(covered).issubset({"p0", "p1", "p2", "p3", "p4", "p5"}),
          "reduced.json only represents states the input declared")
    delays = sorted(t["units"] for t in reduced["transitions"] if t["kind"] == "delay")
    check(delays == [4], f"the two 4-unit paths collapsed into one delay edge (got {delays})")

    partition = json.loads((out / "partition.json").read_text())
    check(partition["declaredStateCount"] == 6, "partition covers all six declared states")
    declared = {"p0", "p1", "p2", "p3", "p4", "p5"}
    check(set(partition["stateToClass"]) == declared, "partition maps every declared state")
    check(set(partition["stateToReducedModel"]) == declared,
          "every declared state is traceable into the reduced model")
    landings = set(partition["stateToReducedModel"].values())
    check(any("#" in landing for landing in landings),
          "a state whose class is a bare instant is placed on a delay edge")

    statespace = (out / "reduced.statespace").read_text()
    check(statespace.startswith("<transitionsystem>"), "reduced.statespace uses Afra's root")
    check('<time value="4"/>' in statespace, "reduced.statespace carries the delay")

    dot = (out / "reduced.dot").read_text()
    check(dot.startswith("digraph"), "reduced.dot is a graph")
    check("time +=4" in dot, "reduced.dot labels time the way Afra does")

    shutil.rmtree(out, ignore_errors=True)


def case_visualization() -> None:
    """The packaged CLI renders a complete Afra state space without Afra."""
    print("\n[2] headless Afra-style state-space visualization")
    model = MODELS / "tiny.statespace"
    out = Path(tempfile.mkdtemp(prefix="awtr-e2e-visualize-"))
    output = out / "tiny.dot"

    result = run(["visualize", str(model), "--output", str(output)])
    check(result.returncode == EXIT_OK,
          f"visualize exits 0 (got {result.returncode})")
    check(output.is_file(), "visualize writes the requested DOT file")

    dot = output.read_text()
    lines = dot.splitlines()
    nodes = [line for line in lines if line.startswith("  n") and " -> " not in line]
    edges = [line for line in lines if " -> " in line]
    check(dot.startswith("digraph statespace {") and dot.endswith("}\n"),
          "output is a complete Graphviz graph")
    check(len(nodes) == 6, f"all 6 states are drawn (got {len(nodes)})")
    check(len(edges) == 7, f"all 7 transitions are drawn (got {len(edges)})")
    check('label="p0", shape=doublecircle' in dot,
          "the initial state is a double circle")
    check('label="dev.poll\\n @0"' in dot,
          "message-server owner and execution time are preserved")
    check('label="time +=4\\n @0", style=bold, color=red' in dot,
          "time transitions use Afra's red bold style")

    stdout = run(["visualize", str(model), "--output", "-"])
    check(stdout.returncode == EXIT_OK, "DOT can be written to standard output")
    check(stdout.stdout == dot, "file and standard-output renderings are byte-identical")
    shutil.rmtree(out, ignore_errors=True)


def case_observable_set_changes_partition() -> None:
    """Hiding more of the model must merge more of it."""
    print("\n[3] a different observable set gives a different partition")
    model = EVALUATION / "smarthome.statespace"
    with_three, out_a = reduce_model("obs3", model, "getSense,activateh,switchoff")
    with_one, out_b = reduce_model("obs1", model, "getSense")
    none_at_all, out_c = reduce_model("obs0", model, "")

    check(with_three.returncode == EXIT_OK, "three observables: exit 0")
    check(with_one.returncode == EXIT_OK, "one observable: exit 0")
    check(none_at_all.returncode == EXIT_OK, "no observables: exit 0")

    a = json.loads((out_a / "metrics.json").read_text())
    b = json.loads((out_b / "metrics.json").read_text())
    c = json.loads((out_c / "metrics.json").read_text())

    check(a["classCount"] > b["classCount"],
          f"hiding switchoff and activateh merges classes ({a['classCount']} -> {b['classCount']})")
    check(b["classCount"] >= c["classCount"],
          f"hiding everything merges at least as much ({b['classCount']} -> {c['classCount']})")
    check(a["observableActions"] == ["getSense", "activateh", "switchoff"],
          "each run records its own observable set")
    check(all(r["verification"]["valid"] for r in (a, b, c)),
          "every partition still yields a verified quotient")

    for path in (out_a, out_b, out_c):
        shutil.rmtree(path, ignore_errors=True)


def case_invalid_input() -> None:
    """Broken input must be refused with the invalid-input code, not a stack trace."""
    print("\n[4] invalid input")
    result, out = reduce_model("broken", MODELS / "broken.statespace", "poll")
    check(result.returncode == EXIT_INVALID_INPUT,
          f"exit code 2 for a dangling transition (got {result.returncode})")
    check("404_0" in result.stderr, "the diagnostic names the offending state")
    check("Exception" not in result.stdout, "no stack trace on stdout")
    shutil.rmtree(out, ignore_errors=True)

    missing = run(["reduce", str(MODELS / "does-not-exist.statespace"), "--observable", "x"])
    check(missing.returncode == EXIT_INVALID_INPUT, "exit code 2 for a missing file")

    no_observable = run(["reduce", str(MODELS / "tiny.statespace")])
    check(no_observable.returncode == EXIT_INVALID_INPUT,
          "exit code 2 when --observable is omitted")


def case_semantic_mutation() -> None:
    """A mutated model must stop being equivalent to the original."""
    print("\n[5] semantic mutation is detected")
    original = MODELS / "tiny.statespace"
    mutant = Path(tempfile.mkdtemp(prefix="awtr-e2e-mutant-")) / "tiny-mutant.statespace"
    mutant.write_text(original.read_text().replace('<time value="4"/>', '<time value="5"/>'))
    check(mutant.read_text() != original.read_text(), "the mutation changed the file")

    same = run(["equivalent", str(original), str(original), "--observable", "poll"])
    check(same.returncode == EXIT_OK, "a model is equivalent to itself")
    check("WEAK_TIMED_BISIMILAR" in same.stdout, "reports the positive verdict")

    differs = run(["equivalent", str(original), str(mutant), "--observable", "poll"])
    check(differs.returncode == EXIT_NEGATIVE,
          f"exit code 1 for the mutant (got {differs.returncode})")
    check("NOT_WEAK_TIMED_BISIMILAR" in differs.stdout, "reports the negative verdict")
    shutil.rmtree(mutant.parent, ignore_errors=True)


def case_time_semantics_is_selectable() -> None:
    """The time-additivity assumption has to be visible and switchable."""
    print("\n[6] the time semantics is an explicit choice")
    left = EVALUATION / "smarthome.statespace"
    right = EVALUATION / "smarthome-tc2step.statespace"

    observable = "getSense,activateh,switchoff"

    unit = run(["equivalent", str(left), str(right), "--observable", observable])
    strict = run(["equivalent", str(left), str(right),
                  "--observable", observable, "--time-semantics", "strict"])
    # The first-party export spells the same message servers in upper case.
    # Observable matching is case sensitive on purpose - Afra identifiers are -
    # so the canonical spelling must not silently match it.
    first_party = ROOT / "src" / "test" / "resources" / "afra" / "smarthome-tc2step.statespace"
    mismatched_case = run(["equivalent", str(left), str(first_party),
                           "--observable", observable])

    check(unit.returncode == EXIT_OK,
          "under time additivity the Case II models are equivalent")
    check(strict.returncode == EXIT_NEGATIVE,
          "under the strict single-edge reading they are not")
    check(mismatched_case.returncode == EXIT_NEGATIVE,
          "observable matching is case sensitive, so GETSENSE is not getSense")


def case_determinism() -> None:
    """Two runs must produce identical bytes."""
    print("\n[7] output is reproducible")
    model = MODELS / "tiny.statespace"
    first, out_a = reduce_model("det-a", model, "poll")
    second, out_b = reduce_model("det-b", model, "poll")
    check(first.returncode == EXIT_OK and second.returncode == EXIT_OK, "both runs succeeded")
    for artefact in ("partition.json", "reduced.json", "reduced.statespace", "reduced.dot"):
        check((out_a / artefact).read_bytes() == (out_b / artefact).read_bytes(),
              f"{artefact} is byte-identical across runs")
    shutil.rmtree(out_a, ignore_errors=True)
    shutil.rmtree(out_b, ignore_errors=True)


def case_comparison_method_is_selectable() -> None:
    """`equivalent` must be able to decide by reducing each side separately."""
    print("\n[8] the comparison method is selectable")
    left = EVALUATION / "smarthome.statespace"
    right = EVALUATION / "smarthome-notify.statespace"
    observable = "getSense,activateh,switchoff"

    union = run(["equivalent", str(left), str(right), "--observable", observable])
    reduced = run(["equivalent", str(left), str(right), "--observable", observable,
                   "--method", "reduced-iso"])
    check(union.returncode == EXIT_OK, "the union method says equivalent")
    check(reduced.returncode == union.returncode,
          "reducing each side separately gives the same verdict")
    check("method             reduced-iso" in reduced.stdout, "the method is reported")
    check("witness" in reduced.stdout, "a positive answer names its witness")
    check("reduced states" in reduced.stdout, "both reduced sizes are reported")

    # The same mutation the union method catches, caught structurally.
    original = MODELS / "tiny.statespace"
    mutant_dir = Path(tempfile.mkdtemp(prefix="awtr-e2e-method-"))
    mutant = mutant_dir / "tiny-mutant.statespace"
    mutant.write_text(original.read_text().replace('<time value="4"/>', '<time value="5"/>'))

    negative = run(["equivalent", str(original), str(mutant), "--observable", "poll",
                    "--method", "reduced-iso"])
    check(negative.returncode == EXIT_NEGATIVE,
          f"exit code 1 for the mutant (got {negative.returncode})")
    check("difference" in negative.stdout, "a negative answer says what differed")

    spliced = run(["equivalent", str(left), str(right), "--observable", observable,
                   "--method", "reduced-iso-spliced"])
    check(spliced.returncode == EXIT_OK, "the spliced form answers this pair too")
    check("not canonical" in spliced.stderr,
          "comparing the spliced quotient warns that it is not a decision procedure")

    unknown = run(["equivalent", str(left), str(right), "--observable", observable,
                   "--method", "nonsense"])
    check(unknown.returncode == EXIT_INVALID_INPUT,
          f"exit code 2 for an unknown method (got {unknown.returncode})")
    shutil.rmtree(mutant_dir, ignore_errors=True)


def main() -> int:
    jar = ROOT / "target" / "awtr.jar"
    if not os.environ.get("AWTR_CMD") and not jar.is_file():
        print(f"missing {jar}; run `mvn -q -DskipTests package` first", file=sys.stderr)
        return 2
    print(f"driving: {' '.join(command)}")

    case_reducible_positive()
    case_visualization()
    case_observable_set_changes_partition()
    case_invalid_input()
    case_semantic_mutation()
    case_time_semantics_is_selectable()
    case_determinism()
    case_comparison_method_is_selectable()

    print(f"\n{checks - len(failures)}/{checks} checks passed")
    if failures:
        print("\nfailures:")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
