#!/usr/bin/env python3
"""Render `.statespace` files with Afra's official Graphviz transformer.

The transformer is loaded from the Afra distribution archive. It produces DOT;
the local Graphviz `dot` executable then lays that graph out as SVG.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import tempfile
from pathlib import Path

from rebeca_to_statespace import (
    DEFAULT_AFRA_ARCHIVE,
    extract_rmc_dependencies,
    require_program,
)


WORKSPACE = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_DIR = (
    WORKSPACE / "AfraWeakTimedReduction" / "docs" / "images" / "rebeca-generated"
)
TRANSFORMER_MAIN = "org.rebecalang.statespacetransformer.StateSpaceTransformer"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("state_spaces", nargs="+", type=Path)
    parser.add_argument(
        "--afra-archive", type=Path, default=DEFAULT_AFRA_ARCHIVE,
        help=f"Afra distribution archive (default: {DEFAULT_AFRA_ARCHIVE})",
    )
    parser.add_argument(
        "--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR,
        help=f"DOT and SVG destination (default: {DEFAULT_OUTPUT_DIR})",
    )
    parser.add_argument(
        "--rankdir", choices=("TB", "LR"), default="TB",
        help="Graphviz layout direction (default: TB)",
    )
    parser.add_argument("--java", default="java", help="Java launcher (default: java)")
    parser.add_argument(
        "--graphviz", default="dot", help="Graphviz renderer (default: dot)"
    )
    args = parser.parse_args()

    archive = args.afra_archive.resolve()
    if not archive.is_file():
        raise SystemExit(f"Afra archive does not exist: {archive}")
    state_spaces = [path.resolve() for path in args.state_spaces]
    missing = [path for path in state_spaces if not path.is_file()]
    if missing:
        raise SystemExit(f"state-space file does not exist: {missing[0]}")
    stems = [path.stem for path in state_spaces]
    if len(stems) != len(set(stems)):
        raise SystemExit("state-space file names must have distinct stems")

    java = require_program(args.java)
    graphviz = require_program(args.graphviz)
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="awtr-afra-graphviz-") as temporary:
        temporary_dir = Path(temporary)
        dependencies = extract_rmc_dependencies(archive, temporary_dir)
        for state_space in state_spaces:
            official_dot = temporary_dir / f"{state_space.stem}.dot"
            subprocess.run(
                [
                    java,
                    "-cp",
                    str(dependencies),
                    TRANSFORMER_MAIN,
                    "--source",
                    str(state_space),
                    "--output",
                    str(official_dot),
                    "--extension",
                    "TimedRebeca",
                    "--targetmodel",
                    "GRAPH_VIZ",
                ],
                check=True,
            )

            dot_output = output_dir / f"{state_space.stem}.dot"
            svg_output = output_dir / f"{state_space.stem}.svg"
            shutil.copyfile(official_dot, dot_output)
            subprocess.run(
                [
                    graphviz,
                    f"-Grankdir={args.rankdir}",
                    "-Gbgcolor=white",
                    "-Tsvg",
                    str(official_dot),
                    "-o",
                    str(svg_output),
                ],
                check=True,
            )
            print(f"{state_space.name} -> {dot_output.name}, {svg_output.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
