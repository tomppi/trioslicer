#!/usr/bin/env python3
"""Keep filaSim Thermal and Anneal workspaces mutually exclusive and remount-safe.

The original integration kept one boolean per workspace and relied on two
separate synchronous CustomEvents for every tab switch. If the deactivation
event was missed, both booleans remained true and StepPanel kept rendering the
first matching workspace. Each activation handler now clears the other state
itself, so the true event is sufficient to select the requested view.

React also reconciles the Thermal and Anneal branches as the same unkeyed
<section> tree. Their UIs are inserted imperatively below the mount div, so a
T <-> A switch can retain the previous workspace children and append the next
workspace beside them. Distinct root keys force React to replace the complete
panel subtree on every cross-workspace switch.
"""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer mutually exclusive and keyed Thermal and Anneal tabs v2"
THERMAL_EVENT = "enderslicer-thermal-workspace"
ANNEALING_EVENT = "enderslicer-annealing-workspace"

THERMAL_OLD = f'''    const onThermalWorkspace = (event: Event) =>
      setThermalActive(Boolean((event as CustomEvent<boolean>).detail));
    window.addEventListener("{THERMAL_EVENT}", onThermalWorkspace);
'''
THERMAL_NEW = f'''    const onThermalWorkspace = (event: Event) => {{
      const active = Boolean((event as CustomEvent<boolean>).detail);
      setThermalActive(active);
      if (active) setAnnealingActive(false);
    }};
    window.addEventListener("{THERMAL_EVENT}", onThermalWorkspace);
'''
ANNEALING_OLD = f'''    const onAnnealingWorkspace = (event: Event) =>
      setAnnealingActive(Boolean((event as CustomEvent<boolean>).detail));
    window.addEventListener("{ANNEALING_EVENT}", onAnnealingWorkspace);
'''
ANNEALING_NEW = f'''    const onAnnealingWorkspace = (event: Event) => {{
      const active = Boolean((event as CustomEvent<boolean>).detail);
      setAnnealingActive(active);
      if (active) setThermalActive(false);
    }};
    window.addEventListener("{ANNEALING_EVENT}", onAnnealingWorkspace);
'''
THERMAL_PANEL_OLD = '<section className="panel" id="enderslicer-thermal-panel">'
THERMAL_PANEL_NEW = (
    '<section key="enderslicer-thermal-workspace" className="panel" '
    'id="enderslicer-thermal-panel">'
)
ANNEALING_PANEL_OLD = '<section className="panel" id="enderslicer-annealing-panel">'
ANNEALING_PANEL_NEW = (
    '<section key="enderslicer-annealing-workspace" className="panel" '
    'id="enderslicer-annealing-panel">'
)


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    rail = source_root / "web/src/ui/StepRail.tsx"
    panel = source_root / "web/src/ui/StepPanel.tsx"
    for path in (rail, panel):
        if not path.is_file():
            raise RuntimeError(f"Workspace-tab state target is missing: {path}")
        replace_once(path, THERMAL_OLD, THERMAL_NEW, "mutually exclusive Thermal handler")
        replace_once(path, ANNEALING_OLD, ANNEALING_NEW, "mutually exclusive Anneal handler")
        text = path.read_text(encoding="utf-8")
        for contract in (
            "if (active) setAnnealingActive(false);",
            "if (active) setThermalActive(false);",
        ):
            if contract not in text:
                raise RuntimeError(f"Workspace-tab contract {contract!r} is missing from {path}")

    replace_once(
        panel,
        THERMAL_PANEL_OLD,
        THERMAL_PANEL_NEW,
        "keyed Thermal workspace panel",
    )
    replace_once(
        panel,
        ANNEALING_PANEL_OLD,
        ANNEALING_PANEL_NEW,
        "keyed Anneal workspace panel",
    )
    panel_text = panel.read_text(encoding="utf-8")
    for contract in (
        'key="enderslicer-thermal-workspace"',
        'key="enderslicer-annealing-workspace"',
    ):
        if panel_text.count(contract) != 1:
            raise RuntimeError(
                f"Workspace-tab remount contract {contract!r} must appear exactly once in {panel}"
            )

    marker = source_root / ".enderslicer-workspace-tab-state-fix-v2"
    marker.write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
