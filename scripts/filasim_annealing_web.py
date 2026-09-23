#!/usr/bin/env python3
"""Patch filaSim WASM, TypeScript, viewer and React workflow for annealing."""
from __future__ import annotations

import pathlib
import re

from filasim_annealing_common import (
    ANNEALING_EVENT, THERMAL_EVENT, replace_all, replace_once,
)


def patch_wasm(wasm: pathlib.Path) -> None:
    replace_once(
        wasm,
        """    time_step_seconds: f64,
    free_expansion: bool,
""",
        """    time_step_seconds: f64,
    fixed_surface_enabled: bool,
    readiness_temperature_c: Option<f64>,
    readiness_hold_seconds: f64,
    readiness_cooling: bool,
    stop_when_ready: bool,
    free_expansion: bool,
""",
        "WASM annealing options",
    )
    replace_once(
        wasm,
        """            time_step_seconds: 10.0,
            free_expansion: true,
""",
        """            time_step_seconds: 10.0,
            fixed_surface_enabled: true,
            readiness_temperature_c: None,
            readiness_hold_seconds: 0.0,
            readiness_cooling: false,
            stop_when_ready: false,
            free_expansion: true,
""",
        "WASM annealing defaults",
    )

    text = wasm.read_text(encoding="utf-8")
    text, count = re.subn(
        r"(?m)^(\s*)tolerance: 1e-7,\s*$",
        r"\1fixed_surface_enabled: opts.fixed_surface_enabled,\n"
        r"\1readiness_temperature_c: opts.readiness_temperature_c,\n"
        r"\1readiness_hold_seconds: opts.readiness_hold_seconds,\n"
        r"\1readiness_cooling: opts.readiness_cooling,\n"
        r"\1stop_when_ready: opts.stop_when_ready,\n"
        r"\1tolerance: 1e-7,",
        text,
    )
    if count != 2:
        raise RuntimeError(f"Expected two WASM ThermalOptions constructors, patched {count}")
    wasm.write_text(text, encoding="utf-8")

    replace_all(
        wasm,
        """                \"peakTimeSeconds\": thermal.peak_time_seconds,
                \"heatedAreaMm2\": thermal.exposed_heated_area_mm2,
""",
        """                \"peakTimeSeconds\": thermal.peak_time_seconds,
                \"readinessReachedTimeSeconds\": thermal.readiness_reached_time_seconds,
                \"readinessCompleteTimeSeconds\": thermal.readiness_complete_time_seconds,
                \"heatedAreaMm2\": thermal.exposed_heated_area_mm2,
""",
        "outside-model readiness stats",
        minimum=1,
    )
    replace_all(
        wasm,
        """            \"peakTimeSeconds\": thermal.peak_time_seconds,
            \"heatedAreaMm2\": thermal.exposed_heated_area_mm2,
""",
        """            \"peakTimeSeconds\": thermal.peak_time_seconds,
            \"readinessReachedTimeSeconds\": thermal.readiness_reached_time_seconds,
            \"readinessCompleteTimeSeconds\": thermal.readiness_complete_time_seconds,
            \"heatedAreaMm2\": thermal.exposed_heated_area_mm2,
""",
        "valid readiness stats",
        minimum=1,
    )


def patch_client(client: pathlib.Path) -> None:
    replace_once(
        client,
        """  timeStepSeconds: number;
  freeExpansion: boolean;
""",
        """  timeStepSeconds: number;
  fixedSurfaceEnabled: boolean;
  readinessTemperatureC: number | null;
  readinessHoldSeconds: number;
  readinessCooling: boolean;
  stopWhenReady: boolean;
  freeExpansion: boolean;
""",
        "TypeScript annealing options",
    )
    replace_once(
        client,
        """  peakTimeSeconds: number;
  heatedAreaMm2: number;
""",
        """  peakTimeSeconds: number;
  readinessReachedTimeSeconds: number | null;
  readinessCompleteTimeSeconds: number | null;
  heatedAreaMm2: number;
""",
        "TypeScript annealing stats",
    )


def patch_viewer(viewer: pathlib.Path) -> None:
    replace_once(
        viewer,
        """  structuralValid: boolean;
  maxDisplacementMm: number | null;
};
""",
        """  structuralValid: boolean;
  maxDisplacementMm: number | null;
  thermalOnly?: boolean;
};
""",
        "viewer annealing result flag",
    )
    replace_once(
        viewer,
        """    structuralValid: boolean;
  } | null>(null);
""",
        """    structuralValid: boolean;
    thermalOnly: boolean;
  } | null>(null);
""",
        "viewer annealing legend state",
    )
    replace_once(
        viewer,
        """        structuralValid: detail.structuralValid,
      });
""",
        """        structuralValid: detail.structuralValid,
        thermalOnly: detail.thermalOnly === true,
      });
""",
        "viewer annealing legend detail",
    )
    replace_once(
        viewer,
        """          <div style={{ fontWeight: 700, marginBottom: 6 }}>Temperature · 3D result</div>
""",
        """          <div style={{ fontWeight: 700, marginBottom: 6 }}>
            {thermalLegend.thermalOnly ? \"Annealing temperature · 3D result\" : \"Temperature · 3D result\"}
          </div>
""",
        "viewer annealing title",
    )
    replace_once(
        viewer,
        """          {!thermalLegend.structuralValid && (
""",
        """          {!thermalLegend.structuralValid && !thermalLegend.thermalOnly && (
""",
        "viewer thermal-only warning suppression",
    )


def patch_react_tabs(rail: pathlib.Path, panel: pathlib.Path, topbar: pathlib.Path) -> None:
    replace_once(
        rail,
        f'''  const [thermalActive, setThermalActive] = useState(false);
  useEffect(() => {{
    const onThermalWorkspace = (event: Event) =>
      setThermalActive(Boolean((event as CustomEvent<boolean>).detail));
    window.addEventListener("{THERMAL_EVENT}", onThermalWorkspace);
    return () => window.removeEventListener("{THERMAL_EVENT}", onThermalWorkspace);
  }}, []);

''',
        f'''  const [thermalActive, setThermalActive] = useState(false);
  const [annealingActive, setAnnealingActive] = useState(false);
  useEffect(() => {{
    const onThermalWorkspace = (event: Event) =>
      setThermalActive(Boolean((event as CustomEvent<boolean>).detail));
    window.addEventListener("{THERMAL_EVENT}", onThermalWorkspace);
    return () => window.removeEventListener("{THERMAL_EVENT}", onThermalWorkspace);
  }}, []);
  useEffect(() => {{
    const onAnnealingWorkspace = (event: Event) =>
      setAnnealingActive(Boolean((event as CustomEvent<boolean>).detail));
    window.addEventListener("{ANNEALING_EVENT}", onAnnealingWorkspace);
    return () => window.removeEventListener("{ANNEALING_EVENT}", onAnnealingWorkspace);
  }}, []);

''',
        "StepRail annealing state",
    )
    replace_once(
        rail,
        'className={`station${!thermalActive && active === st.n ? " active" : ""}${done[st.n] ? " done" : ""}`}',
        'className={`station${!thermalActive && !annealingActive && active === st.n ? " active" : ""}${done[st.n] ? " done" : ""}`}',
        "normal station annealing active state",
    )
    replace_once(
        rail,
        f'''            window.dispatchEvent(new CustomEvent<boolean>("{THERMAL_EVENT}", {{ detail: false }}));
            s.setActiveStep(st.n);
''',
        f'''            window.dispatchEvent(new CustomEvent<boolean>("{THERMAL_EVENT}", {{ detail: false }}));
            window.dispatchEvent(new CustomEvent<boolean>("{ANNEALING_EVENT}", {{ detail: false }}));
            s.setActiveStep(st.n);
''',
        "normal station annealing exit",
    )
    replace_once(
        rail,
        f'''        onClick={{() =>
          window.dispatchEvent(new CustomEvent<boolean>("{THERMAL_EVENT}", {{ detail: true }}))
        }}
      >
        <span className="st-no">T</span>
        <span className="st-name">Thermal</span>
      </button>
    </nav>
''',
        f'''        onClick={{() => {{
          window.dispatchEvent(new CustomEvent<boolean>("{ANNEALING_EVENT}", {{ detail: false }}));
          window.dispatchEvent(new CustomEvent<boolean>("{THERMAL_EVENT}", {{ detail: true }}));
        }}}}
      >
        <span className="st-no">T</span>
        <span className="st-name">Thermal</span>
      </button>
      <button
        className={{`station${{annealingActive ? " active" : ""}}`}}
        disabled={{!s.model}}
        title="Annealing Planner — geometry-aware oven heat-up, soak and cooling"
        aria-pressed={{annealingActive}}
        onClick={{() => {{
          window.dispatchEvent(new CustomEvent<boolean>("{THERMAL_EVENT}", {{ detail: false }}));
          window.dispatchEvent(new CustomEvent<boolean>("{ANNEALING_EVENT}", {{ detail: true }}));
        }}}}
      >
        <span className="st-no">A</span>
        <span className="st-name">Anneal</span>
      </button>
    </nav>
''',
        "Annealing workflow station",
    )

    replace_once(
        panel,
        f'''  const [thermalActive, setThermalActive] = useState(false);
  useEffect(() => {{
    const onThermalWorkspace = (event: Event) =>
      setThermalActive(Boolean((event as CustomEvent<boolean>).detail));
    window.addEventListener("{THERMAL_EVENT}", onThermalWorkspace);
    return () => window.removeEventListener("{THERMAL_EVENT}", onThermalWorkspace);
  }}, []);

''',
        f'''  const [thermalActive, setThermalActive] = useState(false);
  const [annealingActive, setAnnealingActive] = useState(false);
  useEffect(() => {{
    const onThermalWorkspace = (event: Event) =>
      setThermalActive(Boolean((event as CustomEvent<boolean>).detail));
    window.addEventListener("{THERMAL_EVENT}", onThermalWorkspace);
    return () => window.removeEventListener("{THERMAL_EVENT}", onThermalWorkspace);
  }}, []);
  useEffect(() => {{
    const onAnnealingWorkspace = (event: Event) =>
      setAnnealingActive(Boolean((event as CustomEvent<boolean>).detail));
    window.addEventListener("{ANNEALING_EVENT}", onAnnealingWorkspace);
    return () => window.removeEventListener("{ANNEALING_EVENT}", onAnnealingWorkspace);
  }}, []);

''',
        "StepPanel annealing state",
    )
    replace_once(
        panel,
        '''  if (thermalActive) {
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
        '''  if (thermalActive) {
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

  if (annealingActive) {
    return (
      <section className="panel" id="enderslicer-annealing-panel">
        <div className="p-head">
          <b>Annealing Planner</b>
          <span>Geometry-aware oven heat-up, soak and controlled cooling.</span>
        </div>
        <div id="enderslicer-annealing-calculator-mount" />
      </section>
    );
  }

  let head = (buildsim ? BUILD_HEAD : HEAD)[step];
''',
        "Annealing panel host",
    )

    replace_once(
        topbar,
        f'''              window.dispatchEvent(new CustomEvent<boolean>("{THERMAL_EVENT}", {{ detail: false }}));
              s.setAppMode(e.target.value as "optimize" | "buildsim");
''',
        f'''              window.dispatchEvent(new CustomEvent<boolean>("{THERMAL_EVENT}", {{ detail: false }}));
              window.dispatchEvent(new CustomEvent<boolean>("{ANNEALING_EVENT}", {{ detail: false }}));
              s.setAppMode(e.target.value as "optimize" | "buildsim");
''',
        "workspace selector annealing exit",
    )
