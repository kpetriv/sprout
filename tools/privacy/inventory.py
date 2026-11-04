#!/usr/bin/env python3
"""Checks the Play data-safety answers against the release build (M8 §2, D-M8-2: `checkDataSafetyInventory`).

Reads the permissions of the merged release manifest and the third-party modules of the release runtime classpath, and
compares them with the ```inventory block in docs/DATA_SAFETY.md:

    permission <android permission name>      every <uses-permission> of the merged manifest, exactly
    sdk <group>:<artifact>                     every module of a data-relevant SDK group (below), exactly
    library <group>[:<artifact>] | <prefix>.*  everything else: libraries that collect and share nothing

A permission or module without an entry fails (a new SDK or permission needs a data-safety decision first); so does an
entry the build no longer has (the form must not claim more than ships). Versions are ignored. Text after " -- " on a
line is a note.

By default it runs Gradle itself:
    ./gradlew -q :app:processReleaseManifest :app:dependencies --configuration releaseRuntimeClasspath
and reads app/build/intermediates/merged_manifest/release/**/AndroidManifest.xml. Python 3.9+, standard library only.

Usage:
    python3 tools/privacy/inventory.py                 # print the build's inventory as an inventory block
    python3 tools/privacy/inventory.py --check         # compare with docs/DATA_SAFETY.md (CI build job)
    python3 tools/privacy/inventory.py --check --gradle-arg=--no-daemon
    python3 tools/privacy/inventory.py --check --manifest M.xml --dependencies deps.txt   # no Gradle run
"""
from __future__ import annotations

import argparse
import fnmatch
import re
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

ROOT = Path(__file__).resolve().parents[2]
DATA_SAFETY = ROOT / "docs" / "DATA_SAFETY.md"
MANIFEST_DIR = ROOT / "app" / "build" / "intermediates" / "merged_manifest" / "release"
ANDROID = "{http://schemas.android.com/apk/res/android}"

# Groups whose modules can collect, send or receive user or device data: each module needs its own `sdk` entry with
# a data-safety answer. A `library` entry never covers them.
SDK_GROUPS = (
    "com.google.firebase",
    "com.google.android.gms",
    "com.google.mlkit",
    "com.google.android.play",
    "com.google.android.recaptcha",
    "com.google.android.datatransport",
    "com.google.android.libraries.*",
    "com.google.android.odml",
    "androidx.credentials",
    # androidx groups that read identifiers or health data (audit I3): `library androidx.*` must not cover them.
    "androidx.ads",
    "androidx.privacysandbox.*",
    "androidx.health",
    "androidx.health.*",
    "io.github.*",
    "com.squareup.okhttp3",
    "io.grpc",
)

DEPENDENCY_LINE = re.compile(r"^[| ]*[+\\]--- (.+)$")
# "group:artifact:version", "group:artifact -> version" (BOM-managed), "group:artifact:{prefer 1.0} -> 1.1 (*)"
MODULE = re.compile(r"^([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)(?:[: ].*)?$")


class InventoryError(Exception):
    pass


def is_sdk_group(group: str) -> bool:
    return any(group == g or (g.endswith(".*") and group.startswith(g[:-1])) for g in SDK_GROUPS)


# --------------------------------------------------------------------------------------------------------------------
# The build


def run_gradle(extra: List[str]) -> str:
    command = [
        str(ROOT / "gradlew"), "-q", "--console=plain", *extra,
        ":app:processReleaseManifest", ":app:dependencies", "--configuration", "releaseRuntimeClasspath",
    ]
    result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if result.returncode != 0:
        raise InventoryError("Gradle failed:\n" + result.stderr[-4000:])
    return result.stdout


def find_manifest() -> Path:
    candidates = sorted(MANIFEST_DIR.glob("**/AndroidManifest.xml"))
    if not candidates:
        raise InventoryError(f"no merged release manifest under {MANIFEST_DIR} (run ./gradlew :app:processReleaseManifest)")
    return candidates[0]


def manifest_permissions(path: Path) -> Set[str]:
    root = ElementTree.parse(path).getroot()
    names = set()
    for tag in ("uses-permission", "uses-permission-sdk-23"):
        for element in root.iter(tag):
            name = element.get(ANDROID + "name")
            if name:
                names.add(name)
    return names


def classpath_modules(report: str) -> Set[Tuple[str, str]]:
    if "releaseRuntimeClasspath" not in report:
        raise InventoryError("the dependency report has no releaseRuntimeClasspath section")
    modules = set()
    for line in report.splitlines():
        match = DEPENDENCY_LINE.match(line)
        if not match:
            continue
        entry = match.group(1).strip()
        if entry.endswith("FAILED"):
            # An unresolved module would otherwise count as shipped (review L4): the report is not trustworthy.
            raise InventoryError(f"the dependency report has an unresolved module: {entry}")
        if entry.startswith("project ") or entry.endswith("(c)") or entry.endswith("(n)"):
            continue
        module = MODULE.match(entry)
        if module is None:
            raise InventoryError(f"cannot read the dependency line: {line.strip()}")
        modules.add((module.group(1), module.group(2)))
    if not modules:
        raise InventoryError("the dependency report lists no modules")
    return modules


# --------------------------------------------------------------------------------------------------------------------
# docs/DATA_SAFETY.md


class Allowlist:
    def __init__(self) -> None:
        self.permissions: Dict[str, int] = {}
        self.sdks: Dict[Tuple[str, str], int] = {}
        self.libraries: Dict[str, int] = {}


def read_allowlist(path: Path) -> Allowlist:
    text = path.read_text(encoding="utf-8")
    blocks = re.findall(r"^```inventory\n(.*?)^```", text, re.M | re.S)
    if len(blocks) != 1:
        raise InventoryError(f"{path} must have exactly one ```inventory block")
    first_line = text[: text.index("```inventory")].count("\n") + 2
    allow = Allowlist()
    for offset, raw in enumerate(blocks[0].splitlines()):
        number = first_line + offset
        line = raw.split(" -- ", 1)[0].strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) != 2:
            raise InventoryError(f"{path}:{number}: expected '<kind> <name>'")
        kind, name = parts
        if kind == "permission":
            allow.permissions[name] = number
        elif kind == "sdk":
            if name.count(":") != 1:
                raise InventoryError(f"{path}:{number}: an sdk entry is group:artifact")
            group, artifact = name.split(":")
            allow.sdks[(group, artifact)] = number
        elif kind == "library":
            group = name.split(":")[0]
            if is_sdk_group(group.rstrip(".*")) or is_sdk_group(group):
                raise InventoryError(f"{path}:{number}: {name} is in a data-relevant group; list it as sdk")
            allow.libraries[name] = number
        else:
            raise InventoryError(f"{path}:{number}: unknown kind {kind!r}")
    return allow


def library_covers(pattern: str, group: str, artifact: str) -> bool:
    if ":" in pattern:
        return pattern == f"{group}:{artifact}"
    if pattern.endswith(".*"):
        return fnmatch.fnmatchcase(group, pattern) or group == pattern[:-2]
    return group == pattern


def compare(permissions: Set[str], modules: Set[Tuple[str, str]], allow: Allowlist) -> List[str]:
    problems = []
    for name in sorted(permissions - set(allow.permissions)):
        problems.append(f"new permission without a DATA_SAFETY.md entry: permission {name}")
    for name in sorted(set(allow.permissions) - permissions):
        problems.append(f"DATA_SAFETY.md:{allow.permissions[name]}: permission {name} is no longer in the merged manifest")
    used_libraries: Set[str] = set()
    for group, artifact in sorted(modules):
        if (group, artifact) in allow.sdks:
            continue
        if is_sdk_group(group):
            problems.append(f"new SDK without a DATA_SAFETY.md entry: sdk {group}:{artifact}")
            continue
        covering = [p for p in allow.libraries if library_covers(p, group, artifact)]
        if not covering:
            problems.append(f"new library without a DATA_SAFETY.md entry: library {group} (module {group}:{artifact})")
        used_libraries.update(covering)
    for (group, artifact), number in sorted(allow.sdks.items()):
        if (group, artifact) not in modules:
            problems.append(f"DATA_SAFETY.md:{number}: sdk {group}:{artifact} is no longer on the release classpath")
    for pattern, number in sorted(allow.libraries.items()):
        if pattern not in used_libraries:
            problems.append(f"DATA_SAFETY.md:{number}: library {pattern} matches nothing on the release classpath")
    return problems


def print_inventory(permissions: Set[str], modules: Set[Tuple[str, str]]) -> None:
    print("```inventory")
    for name in sorted(permissions):
        print(f"permission {name}")
    for group, artifact in sorted(modules):
        if is_sdk_group(group):
            print(f"sdk {group}:{artifact}")
    for group in sorted({g for g, _ in modules if not is_sdk_group(g)}):
        print(f"library {group}")
    print("```")


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="compare with docs/DATA_SAFETY.md and fail on a mismatch")
    parser.add_argument("--manifest", type=Path, help="merged release manifest (with --dependencies: no Gradle run)")
    parser.add_argument(
        "--dependencies", type=Path,
        help="saved output of :app:dependencies --configuration releaseRuntimeClasspath (with --manifest)",
    )
    parser.add_argument("--gradle-arg", action="append", default=[], help="extra Gradle argument (repeatable)")
    parser.add_argument("--data-safety", type=Path, default=DATA_SAFETY, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    if (args.manifest is None) != (args.dependencies is None):
        # Either both saved inputs or a fresh Gradle run: never a fresh report with a stale manifest (review L5).
        parser.error("--manifest and --dependencies go together")
    try:
        if args.dependencies is not None:
            report = args.dependencies.read_text(encoding="utf-8")
        else:
            report = run_gradle(args.gradle_arg)
        manifest = args.manifest if args.manifest is not None else find_manifest()
        permissions = manifest_permissions(manifest)
        modules = classpath_modules(report)
        if not args.check:
            print_inventory(permissions, modules)
            return 0
        problems = compare(permissions, modules, read_allowlist(args.data_safety))
    except InventoryError as error:
        print(f"inventory.py: {error}", file=sys.stderr)
        return 1
    if problems:
        print("Data-safety inventory does not match the release build:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "Decide the Play data-safety answer for each (docs/DATA_SAFETY.md), then update the inventory block.",
            file=sys.stderr,
        )
        return 1
    sdk_count = sum(1 for g, _ in modules if is_sdk_group(g))
    print(
        f"data-safety inventory matches the release build: {len(permissions)} permissions, {sdk_count} SDK modules, "
        f"{len(modules) - sdk_count} other modules"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
