#!/usr/bin/env python3
"""Shared deterministic helpers for the annealing filaSim transforms."""
from __future__ import annotations

import pathlib
import re

MARKER = "EnderSlicer geometry-aware annealing cycle v1"
THERMAL_EVENT = "enderslicer-thermal-workspace"
ANNEALING_EVENT = "enderslicer-annealing-workspace"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_all(path: pathlib.Path, old: str, new: str, label: str, minimum: int = 1) -> int:
    text = path.read_text(encoding="utf-8")
    if new in text and old not in text:
        return 0
    count = text.count(old)
    if count < minimum:
        raise RuntimeError(f"Expected at least {minimum} {label} in {path}, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")
    return count


def regex_once(path: pathlib.Path, pattern: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    compiled = re.compile(pattern, re.MULTILINE | re.DOTALL)
    matches = list(compiled.finditer(text))
    if len(matches) != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {len(matches)}")
    path.write_text(compiled.sub(replacement, text, count=1), encoding="utf-8")


def append_once(path: pathlib.Path, marker: str, body: str) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        return
    # Persist the marker in the generated Rust text, not only in the Python
    # guard. This makes the transform demonstrably idempotent across repeated
    # Gradle and CI preparation runs.
    addition = f"// {marker}\n{body.strip()}\n"
    path.write_text(text.rstrip() + "\n\n" + addition, encoding="utf-8")
