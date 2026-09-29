#!/usr/bin/env python3
"""check_provenance — prove the tree belongs to Eclipse and to nobody else.

eclipsesdl started from a study of other people's launchers, and study is only
useful if it stays study: a name, a string, a build artefact or a whole file
carried across turns somebody else's work into ours, and there is no build
warning for that. Nothing in the compiler will ever notice either — a copied
string is a valid string.

So this looks for the names it should never find, everywhere it could find
them:

  * every text file in the repository (source, build files, scripts, docs);
  * every shipped binary — .so, .aar, .jar, .zip — read as bytes and swept for
    ASCII runs, because a string that only survives into a library is exactly
    the case a text search misses.

Both directions matter, so it also confirms that *our* identifiers are where
they must be: the Java package in the bindings, and the JNI prefix in the
native code.

Usage:  tools/check_provenance.py [--root DIR] [--quiet]
Exit:   0 clean, 1 otherwise.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Names that must not appear, as they would appear if they had been carried
# over: in a comment, a package path, a soname, a log line.
BANNED = (
    "mojo",
    "mojosdl",
    "mojoexec",
    "pojav",
    "pojavlauncher",
    "amethyst",
)

# Directories that are not ours to have opinions about: .git for obvious
# reasons, and the two Gradle/CMake build trees — their contents are derived
# from the sources above and are swept where they matter instead, in the
# staged jniLibs. dist/ is deliberately *not* skipped: a release zip is the
# single most important thing to have swept.
SKIP_DIRS = {".git", ".gradle", "build", "node_modules", "__pycache__"}

# Extensions we sweep as text. Anything else binary gets the byte sweep only if
# it is a shipped artefact, below.
TEXT_SUFFIXES = {
    ".c", ".h", ".cpp", ".hpp", ".cc", ".java", ".kt", ".py", ".sh", ".bash",
    ".gradle", ".kts", ".properties", ".xml", ".pro", ".md", ".txt", ".yml",
    ".yaml", ".json", ".cmake", ".in", ".sym", ".script", ".mk", ".mkd",
    ".toml", ".cfg", ".ini", ".html", ".css", ".js", ".ts", ".gitignore",
    ".gitattributes", ".editorconfig", "",
}

# The identifiers that make this tree ours. Checked in both directions: an
# absence here is as much a defect as a banned name is.
REQUIRED = {
    "jni_bindings/src/main/java/me/shadow/eclipselauncher/sdl/EclipseSDL.java":
        "package me.shadow.eclipselauncher.sdl;",
    "src/core/android/SDL_android.c":
        "#define SDL_JAVA_PREFIX                               me_shadow_eclipselauncher_sdl",
}

BIN_SUFFIXES = {".so", ".aar", ".jar", ".zip", ".apk"}


class Findings:
    def __init__(self) -> None:
        self.hits: list[str] = []
        self.notes: list[str] = []

    def fail(self, message: str) -> None:
        self.hits.append(message)

    def note(self, message: str) -> None:
        self.notes.append(message)


def is_text(path: Path) -> bool:
    if path.suffix.lower() in TEXT_SUFFIXES:
        return True
    # Extensionless files (gradlew, LICENSE, version.script) are still text.
    return path.suffix == ""


def sweep_text(path: Path, banned: list[re.Pattern[str]], findings: Findings) -> None:
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        findings.fail(f"{path}: unreadable ({exc})")
        return
    for lineno, line in enumerate(text.splitlines(), 1):
        lower = line.lower()
        for pat in banned:
            m = pat.search(lower)
            if m:
                findings.fail(f"{path}: line {lineno}: contains '{m.group(0)}': {line.strip()[:160]}")


def sweep_binary(path: Path, banned: list[re.Pattern[str]], findings: Findings) -> None:
    """Sweep a shipped artefact for ASCII runs containing a banned name."""
    try:
        data = path.read_bytes()
    except OSError as exc:
        findings.fail(f"{path}: unreadable ({exc})")
        return
    # Extract runs of printable ASCII; a copied string lands in .rodata as one.
    runs = re.findall(rb"[\x20-\x7e]{4,}", data)
    for run in runs:
        text = run.decode("ascii", errors="ignore").lower()
        for pat in banned:
            m = pat.search(text)
            if m:
                findings.fail(f"{path}: binary contains '{m.group(0)}': {run.decode('ascii', errors='ignore')[:160]}")
                break


def walk(root: Path, exclude: set[Path]) -> list[Path]:
    files: list[Path] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        if path.resolve() in exclude:
            continue
        rel = path.relative_to(root)
        if any(part in SKIP_DIRS for part in rel.parts):
            continue
        files.append(path)
    return files


def run(root: Path, quiet: bool) -> int:
    findings = Findings()

    # Anchored with look-arounds rather than \b: the terms are plain letters,
    # so a word boundary adds nothing, and these still catch a name sitting in
    # the middle of a package path or a soname.
    banned = [re.compile(rf"(?<![a-z0-9]){re.escape(term)}(?![a-z0-9])") for term in BANNED]

    # The banned list itself lives in this file, so scanning it would always
    # fail — the one file allowed to spell the names out. Everything else in
    # the repository is swept, including this directory's neighbours.
    files = walk(root, exclude={Path(__file__).resolve()})
    text_files = [f for f in files if is_text(f)]
    bin_files = [f for f in files if f.suffix.lower() in BIN_SUFFIXES]

    for path in text_files:
        sweep_text(path, banned, findings)
    for path in bin_files:
        sweep_binary(path, banned, findings)

    # Our own identifiers have to be present, or the rename went somewhere else.
    for rel, needle in REQUIRED.items():
        path = root / rel
        if not path.is_file():
            findings.fail(f"{rel}: expected file is missing")
            continue
        body = path.read_text(encoding="utf-8", errors="replace")
        if needle not in body:
            findings.fail(f"{rel}: expected to find `{needle}`")

    if not quiet:
        print(f"root          {root}")
        print(f"text files    {len(text_files)}")
        print(f"binaries      {len(bin_files)}")
        print(f"banned names  {', '.join(BANNED)}")

    if findings.hits:
        print(f"\nFAIL — {len(findings.hits)} trace(s) of work that is not ours:", file=sys.stderr)
        for hit in findings.hits:
            print(f"  - {hit}", file=sys.stderr)
        return 1

    print("OK — no foreign branding in source, build files or shipped binaries.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=None, help="repository root (default: parent of this script)")
    parser.add_argument("--quiet", action="store_true", help="only report failures")
    args = parser.parse_args()

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent
    if not root.is_dir():
        print(f"check_provenance: {root} is not a directory", file=sys.stderr)
        return 2
    return run(root, args.quiet)


if __name__ == "__main__":
    sys.exit(main())
