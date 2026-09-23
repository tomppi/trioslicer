#!/usr/bin/env python3
"""Integrate Thermal Integrity as a React-owned filaSim workflow station.

The runtime UI still stays Android-only, but React owns the rail button and the
panel host. This avoids mutating or hiding React-managed `.panel` children from
an injected script, which can otherwise leave the sidebar blank after a render.
"""

from __future__ import annotations

import pathlib


MARKER = "EnderSlicer thermal integrity React tab v1"
EVENT = "enderslicer-thermal-workspace"


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
    topbar = source_root / "web/src/ui/TopBar.tsx"
    for path in (rail, panel, topbar):
        if not path.is_file():
            raise RuntimeError(f"Pinned filaSim React tab target is missing: {path}")

    replace_once(
        rail,
        'import { useShallow } from "zustand/shallow";\n',
        'import { useEffect, useState } from "react";\n'
        'import { useShallow } from "zustand/shallow";\n',
        "StepRail React import",
    )
    replace_once(
        rail,
        '''export function StepRail() {
  const s = useStore(
''',
        f'''export function StepRail() {{
  const [thermalActive, setThermalActive] = useState(false);
  useEffect(() => {{
    const onThermalWorkspace = (event: Event) =>
      setThermalActive(Boolean((event as CustomEvent<boolean>).detail));
    window.addEventListener("{EVENT}", onThermalWorkspace);
    return () => window.removeEventListener("{EVENT}", onThermalWorkspace);
  }}, []);

  const s = useStore(
''',
        "StepRail thermal workspace state",
    )
    replace_once(
        rail,
        'className={`station${active === st.n ? " active" : ""}${done[st.n] ? " done" : ""}`}',
        'className={`station${!thermalActive && active === st.n ? " active" : ""}${done[st.n] ? " done" : ""}`}',
        "normal station active state",
    )
    replace_once(
        rail,
        'onClick={() => s.setActiveStep(st.n)}',
        f'''onClick={{() => {{
            window.dispatchEvent(new CustomEvent<boolean>("{EVENT}", {{ detail: false }}));
            s.setActiveStep(st.n);
          }}}}''',
        "normal station workspace exit",
    )
    replace_once(
        rail,
        '''      ))}
    </nav>
''',
        f'''      ))}}
      <button
        className={{`station${{thermalActive ? " active" : ""}}`}}
        disabled={{!s.model}}
        title="Thermal Integrity — service-temperature heat flow and structural FEA"
        aria-pressed={{thermalActive}}
        onClick={{() =>
          window.dispatchEvent(new CustomEvent<boolean>("{EVENT}", {{ detail: true }}))
        }}
      >
        <span className="st-no">T</span>
        <span className="st-name">Thermal</span>
      </button>
    </nav>
''',
        "Thermal workflow station",
    )

    replace_once(
        panel,
        '''export function StepPanel() {
  const s = useStore(
''',
        f'''export function StepPanel() {{
  const [thermalActive, setThermalActive] = useState(false);
  useEffect(() => {{
    const onThermalWorkspace = (event: Event) =>
      setThermalActive(Boolean((event as CustomEvent<boolean>).detail));
    window.addEventListener("{EVENT}", onThermalWorkspace);
    return () => window.removeEventListener("{EVENT}", onThermalWorkspace);
  }}, []);

  const s = useStore(
''',
        "StepPanel thermal workspace state",
    )
    replace_once(
        panel,
        '''  }, []);

  let head = (buildsim ? BUILD_HEAD : HEAD)[step];
''',
        '''  }, []);

  if (thermalActive) {
    return (
      <section className="panel" id="enderslicer-thermal-panel">
        <div className="p-head">
          <b>Thermal Integrity</b>
          <span>Service-temperature heat flow and structural FEA.</span>
        </div>
        <div id="enderslicer-thermal-integrity-mount" />
      </section>
    );
  }

  let head = (buildsim ? BUILD_HEAD : HEAD)[step];
''',
        "Thermal panel host",
    )

    replace_once(
        topbar,
        '''            onChange={(e) => s.setAppMode(e.target.value as "optimize" | "buildsim")}
''',
        f'''            onChange={{(e) => {{
              window.dispatchEvent(new CustomEvent<boolean>("{EVENT}", {{ detail: false }}));
              s.setAppMode(e.target.value as "optimize" | "buildsim");
            }}}}
''',
        "workspace selector thermal exit",
    )

    marker = source_root / ".enderslicer-thermal-integrity-react-tab-v1"
    marker.write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
