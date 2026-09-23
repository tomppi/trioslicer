/* Shared filaSim material resolver for Thermal Integrity and Anneal. */
(() => {
  "use strict";
  const CHANGE_EVENT = "enderslicer-filasim-materials-changed";
  const SUPPLEMENTS = Object.freeze({
    PLA: { k: [0.18, 0.18, 0.13], cp: 1800, cteZ: 110e-6, service: 50, oven: 75, soak: 60, handling: 45, status: "literature-seeded" },
    "PLA+": { k: [0.18, 0.18, 0.13], cp: 1800, cteZ: 105e-6, service: 50, oven: 70, soak: 60, handling: 45, status: "brand-dependent" },
    HTPLA: { k: [0.19, 0.19, 0.135], cp: 1750, cteZ: 100e-6, service: 55, oven: 90, soak: 30, handling: 50, status: "manufacturer-profile-required" },
    PETG: { k: [0.20, 0.20, 0.14], cp: 1200, cteZ: 80e-6, service: 70, oven: 85, soak: 60, handling: 50, status: "experimental" },
    ABS: { k: [0.17, 0.17, 0.12], cp: 1300, cteZ: 95e-6, service: 85, oven: 105, soak: 15, handling: 55, status: "experimental" },
    ASA: { k: [0.17, 0.17, 0.12], cp: 1300, cteZ: 100e-6, service: 90, oven: 105, soak: 20, handling: 55, status: "experimental" },
    GENERIC: { k: [0.18, 0.18, 0.13], cp: 1500, cteZ: null, service: null, oven: null, soak: 60, handling: 45, status: "derived-custom-profile" },
  });
  const FALLBACK_MATERIALS = Object.freeze([
    { name: "PLA", e0: 3500, nu: 0.35, density: 1.24, strength: 50, strengthZ: 35, shrink: 0.004, shrinkZ: 0.002, yieldStrength: 45, tLock: 60, cte: 96e-6 },
    { name: "PETG", e0: 2100, nu: 0.37, density: 1.27, strength: 45, strengthZ: 34, shrink: 0.004, shrinkZ: 0.002, yieldStrength: 40, tLock: 80, cte: 68e-6 },
    { name: "ABS", e0: 2250, nu: 0.37, density: 1.05, strength: 38, strengthZ: 25, shrink: 0.008, shrinkZ: 0.004, yieldStrength: 33, tLock: 100, cte: 88e-6 },
    { name: "ASA", e0: 2400, nu: 0.37, density: 1.07, strength: 43, strengthZ: 29, shrink: 0.006, shrinkZ: 0.003, yieldStrength: 38, tLock: 100, cte: 90e-6 },
  ]);
  const numberOr = (value, fallback) => Number.isFinite(Number(value)) ? Number(value) : fallback;
  const positiveOr = (value, fallback) => Number.isFinite(Number(value)) && Number(value) > 0 ? Number(value) : fallback;

  function inferFamily(name) {
    const n = String(name || "").toUpperCase().replace(/[\s_-]+/g, "");
    if (n.includes("HTPLA")) return "HTPLA";
    if (n.includes("PLA+") || n.includes("PLAPLUS") || n.includes("TOUGHPLA")) return "PLA+";
    if (n.includes("PETG")) return "PETG";
    if (n.includes("ASA")) return "ASA";
    if (n.includes("ABS")) return "ABS";
    if (n.includes("PLA")) return "PLA";
    return "GENERIC";
  }
  function persistedSnapshot() {
    try {
      const data = JSON.parse(localStorage.getItem("sig.settings.v1") || "null");
      const materials = Array.isArray(data?.materials) ? data.materials.filter((m) => m && m.process !== "isotropic") : [];
      if (materials.length) return { activeMaterialName: materials[0].name, materials, print: null, source: "filaSim persisted material library" };
    } catch (_) { /* optional */ }
    return null;
  }
  function getSnapshot() {
    try {
      const live = window.EnderSlicerFilaSimMaterialSource?.getSnapshot?.();
      if (live?.materials?.length) return { ...live, source: "filaSim live material library" };
    } catch (_) { /* bridge not ready */ }
    return persistedSnapshot() || { activeMaterialName: "PLA", materials: FALLBACK_MATERIALS.map((m) => ({ ...m })), print: null, source: "pinned filaSim default fallback" };
  }
  function fdmMaterials(snapshot = getSnapshot()) {
    return snapshot.materials.filter((m) => m && m.process !== "isotropic");
  }
  function resolveMaterialFromSnapshot(snapshot, requestedName) {
    const materials = fdmMaterials(snapshot);
    const material = materials.find((m) => m.name === requestedName)
      || materials.find((m) => m.name === snapshot.activeMaterialName)
      || materials[0] || FALLBACK_MATERIALS[0];
    const family = inferFamily(material.name);
    const supplement = SUPPLEMENTS[family] || SUPPLEMENTS.GENERIC;
    const tLock = Number.isFinite(Number(material.tLock)) ? Number(material.tLock) : null;
    const cteXY = positiveOr(material.cte, 90e-6);
    const strength = positiveOr(material.strength, 40);
    const strengthZ = positiveOr(material.strengthZ, strength);
    const service = positiveOr(supplement.service, tLock == null ? 55 : Math.max(35, tLock - 10));
    const oven = positiveOr(supplement.oven, tLock == null ? 75 : Math.min(140, tLock + 15));
    const source = snapshot.source || "filaSim material library";
    return {
      name: String(material.name), family, status: supplement.status, profileSource: source,
      filaSim: { ...material },
      densityKgM3: positiveOr(material.density, 1.2) * 1000,
      alphaXyPerK: cteXY,
      alphaZPerK: positiveOr(material.cteZ, positiveOr(supplement.cteZ, cteXY)),
      youngsModulusMpa: positiveOr(material.e0, 2000),
      poissonRatio: numberOr(material.nu, 0.35),
      referenceStrengthMpa: Math.min(strength, strengthZ),
      conductivityXWmK: supplement.k[0], conductivityYWmK: supplement.k[1], conductivityZWmK: supplement.k[2],
      specificHeatJkgK: supplement.cp, serviceLimitC: service,
      ovenTemperatureC: oven, soakMinutes: supplement.soak,
      handlingTemperatureC: supplement.handling, emissivity: 0.9,
      fieldProvenance: {
        densityKgM3: source, alphaXyPerK: positiveOr(material.cte, null) == null ? `${family} complement` : source,
        alphaZPerK: positiveOr(material.cteZ, null) == null ? `${family} complement` : source,
        youngsModulusMpa: source, poissonRatio: source, referenceStrengthMpa: source,
        conductivity: `${family} complement`, specificHeat: `${family} complement`,
        serviceLimit: supplement.service == null ? "derived from filaSim locking temperature" : `${family} complement`,
        annealingSchedule: supplement.oven == null ? "derived from filaSim locking temperature" : `${family} complement`,
      },
    };
  }
  function resolveMaterial(name) { return resolveMaterialFromSnapshot(getSnapshot(), name); }
  function setActiveMaterial(name) {
    try { return window.EnderSlicerFilaSimMaterialSource?.setActiveMaterial?.(name) === true; }
    catch (_) { return false; }
  }
  function populateSelect(select, materials, preferredName) {
    const names = materials.map((m) => String(m.name));
    const current = Array.from(select.options || []).map((option) => option.value);
    if (names.length && (names.length !== current.length || names.some((name, i) => name !== current[i]))) {
      select.textContent = "";
      for (const material of materials) {
        const option = document.createElement("option");
        option.value = String(material.name);
        option.textContent = String(material.name).replace(/[<>&\u0000-\u001f]/g, "");
        select.appendChild(option);
      }
    }
    const selected = names.includes(preferredName) ? preferredName : names[0];
    if (selected) select.value = selected;
    return selected;
  }
  window.EnderSlicerFilaSimProfiles = Object.freeze({
    eventName: CHANGE_EVENT, getSnapshot, fdmMaterials, inferFamily,
    resolveMaterial, resolveMaterialFromSnapshot, setActiveMaterial, populateSelect,
  });
  window.EnderSlicerMaterialProfileTestApi = Object.freeze({ inferFamily, resolveMaterialFromSnapshot });
})();
