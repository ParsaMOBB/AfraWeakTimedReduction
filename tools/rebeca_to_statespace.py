#!/usr/bin/env python3
"""Generate TTS `.statespace` files with the RMC bundled in Afra.

The script performs the same headless pipeline as Afra's model-check command:
Rebeca source -> RMC-generated C++ -> native model checker -> `.statespace`.
It requires Java 17+ and a C++11 compiler, but it does not start the Afra GUI.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path


WORKSPACE = Path(__file__).resolve().parents[2]
DEFAULT_AFRA_ARCHIVE = WORKSPACE / "Afra" / "Afra-macosx.cocoa.aarch64.tar.gz"
DEFAULT_OUTPUT_DIR = WORKSPACE / "Examples" / "models" / "statespace"
RMC_MAIN = "org.rebecalang.rmc.RMC"


def require_program(name: str) -> str:
    executable = shutil.which(name)
    if executable is None:
        raise SystemExit(f"required program is not on PATH: {name}")
    return executable


def extract_rmc_dependencies(archive_path: Path, temporary_dir: Path) -> Path:
    with tarfile.open(archive_path, "r:gz") as archive:
        candidates = [
            member
            for member in archive.getmembers()
            if member.isfile()
            and "/plugins/org.rebecalang.afra.ideplugin_" in member.name
            and member.name.endswith(".jar")
        ]
        if len(candidates) != 1:
            raise SystemExit(
                f"expected one Afra IDE plug-in in {archive_path}, found {len(candidates)}"
            )
        source = archive.extractfile(candidates[0])
        if source is None:
            raise SystemExit(f"cannot read {candidates[0].name} from {archive_path}")
        plugin_path = temporary_dir / "afra-ideplugin.jar"
        plugin_path.write_bytes(source.read())

    with zipfile.ZipFile(plugin_path) as plugin:
        dependency_jars = [
            name
            for name in plugin.namelist()
            if name.startswith("lib/dependencies-") and name.endswith(".jar")
        ]
        if len(dependency_jars) != 1:
            raise SystemExit(
                "expected one bundled dependencies jar in the Afra IDE plug-in, "
                f"found {len(dependency_jars)}"
            )
        dependencies_path = temporary_dir / "dependencies.jar"
        dependencies_path.write_bytes(plugin.read(dependency_jars[0]))
        return dependencies_path


def generate_one(
    model: Path,
    output_dir: Path,
    build_root: Path,
    dependencies: Path,
    java: str,
    compiler: str,
) -> Path:
    build_dir = build_root / model.stem
    build_dir.mkdir()
    output = (output_dir / f"{model.stem}.statespace").resolve()

    subprocess.run(
        [
            java,
            "-cp",
            str(dependencies),
            RMC_MAIN,
            "--source",
            str(model.resolve()),
            "--output",
            str(build_dir),
            "--extension",
            "TIMED_REBECA",
            "--version",
            "2.3",
            "--tts",
            "--exporttransitionsystem",
            output.name,
        ],
        check=True,
    )

    cpp_sources = sorted(build_dir.glob("*.cpp"))
    if not cpp_sources:
        raise SystemExit(f"RMC did not generate C++ sources for {model}")
    executable = build_dir / "execute"
    subprocess.run(
        [
            compiler,
            "-std=c++11",
            "-w",
            *(str(source) for source in cpp_sources),
            "-o",
            str(executable),
            "-pthread",
        ],
        check=True,
        cwd=build_dir,
    )
    subprocess.run(
        [str(executable), "--exportStatespace", str(output), "--exportResult", "result.xml"],
        check=True,
        cwd=build_dir,
    )

    if not output.is_file() or "<transitionsystem>" not in output.read_text(
        encoding="utf-8"
    ):
        raise SystemExit(f"model checker did not produce a state space for {model}")
    print(f"{model.name} -> {output}")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("models", nargs="+", type=Path, help="Timed Rebeca source files")
    parser.add_argument(
        "--afra-archive", type=Path, default=DEFAULT_AFRA_ARCHIVE,
        help=f"Afra distribution archive (default: {DEFAULT_AFRA_ARCHIVE})",
    )
    parser.add_argument(
        "--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR,
        help=f"destination directory (default: {DEFAULT_OUTPUT_DIR})",
    )
    parser.add_argument("--java", default="java", help="Java launcher (default: java)")
    parser.add_argument("--compiler", default="c++", help="C++ compiler (default: c++)")
    args = parser.parse_args()

    archive = args.afra_archive.resolve()
    if not archive.is_file():
        raise SystemExit(f"Afra archive does not exist: {archive}")
    models = [model.resolve() for model in args.models]
    missing = [model for model in models if not model.is_file()]
    if missing:
        raise SystemExit(f"Rebeca model does not exist: {missing[0]}")
    stems = [model.stem for model in models]
    if len(stems) != len(set(stems)):
        raise SystemExit("model file names must have distinct stems")

    java = require_program(args.java)
    compiler = require_program(args.compiler)
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="awtr-rmc-") as temporary:
        temporary_dir = Path(temporary)
        dependencies = extract_rmc_dependencies(archive, temporary_dir)
        build_root = temporary_dir / "build"
        build_root.mkdir()
        for model in models:
            generate_one(
                model, output_dir, build_root, dependencies, java, compiler
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
