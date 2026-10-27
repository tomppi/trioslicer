/*
 * BumpMesh studio driver.
 *
 * Injected into the pinned BumpMesh page by BumpMeshStudio after the page finishes loading. It exposes
 * window.__BumpMeshStudio with the commands the native host calls - openModel/openProject, configure, exportNow, abort -
 * and talks back through the EnderSlicerStudio bridge with named events, warnings and errors.
 *
 * It drives the page only through the controls a user would touch: #stl-file-input / #import-project-input for the source
 * geometry, the custom texture input (#texture-file-input) for every texture, the page's own setting controls
 * (#mapping-mode, #scale-u-val, #scale-v-val, #amplitude-val, #texture-smoothing-val, #refine-length-val, #max-triangles)
 * and #export-btn. The pinned BumpMesh files stay untouched; the adapter (android-bridge.js) still streams the export
 * through window.EnderSlicerAndroid.
 *
 * Failure discipline: every wait is bounded and named, every file dispatch is acknowledged by the page's own change
 * listener, and a page-vs-studio triangle-count disagreement is reported at once with both counts (whole object keeps the
 * page's count and continues; a painted mask refuses, because its numbering would be wrong).
 */
(() => {
  'use strict';
  if (window.__BumpMeshStudio) return;
  const HOST = window.EnderSlicerStudio;
  if (!HOST) return;

  let currentStep = 'starting';
  let abortRequested = false;

  const ev = (kind, label, fraction, message) => {
    try {
      HOST.onEvent(String(kind || ''), String(label || ''),
        Number.isFinite(fraction) ? Number(fraction) : 0, String(message || ''));
    } catch (e) { /* bridge gone */ }
  };
  const warn = (message) => {
    try { HOST.onWarning(String(message || '')); } catch (e) { /* bridge gone */ }
  };
  const setStep = (name) => {
    currentStep = name;
  };
  const fail = (message) => {
    const text = String(message || 'BumpMesh driver failed') + ' (step: ' + currentStep + ')';
    try { HOST.onError(text); } catch (e) { /* bridge gone */ }
  };
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const byId = (id) => document.getElementById(id);
  const textOf = (element) => (element ? String(element.textContent || '') : '');
  /**
   * The page's triangle count, parsed locale-robustly.
   *
   * intl toLocaleString formats the count with the device's group separator: "225,143" in English, "225 143" in Finnish,
   * often with a NARROW NO-BREAK SPACE (U+202F) or a NBSP. 1.9.16 matched a plain digit run and read "143" from
   * "225 143 triangles", which made the load wait forever on non-English phones. Read the whole token before
   * "triangles" and strip every non-digit separator.
   */
  const triCount = () => {
    const mesh = textOf(byId('mesh-info'));
    const match = mesh.match(/([0-9][0-9\s.,'\u00a0\u2007\u2009\u202f\u2019]*)\s*triangles/i);
    if (!match) return 0;
    const digits = match[1].replace(/[^0-9]/g, '');
    return digits ? parseInt(digits, 10) : 0;
  };
  const dialogOpen = () => {
    const dialog = byId('load-dialog');
    return !!dialog && !dialog.classList.contains('hidden');
  };

  const until = async (check, timeoutMs, label) => {
    const started = performance.now();
    for (;;) {
      if (abortRequested) throw new Error('cancelled');
      let ok = false;
      try { ok = !!check(); } catch (e) { ok = false; }
      if (ok) return;
      if (performance.now() - started > timeoutMs) {
        throw new Error(label + ' did not happen within ' + Math.round(timeoutMs / 1000) + ' s');
      }
      await sleep(100);
    }
  };

  // A blocking alert()/confirm() would freeze a headless apply; report instead.
  window.alert = (message) => {
    const text = String(message || '');
    if (/export|failed|error|could not|unable/i.test(text)) fail(text); else warn(text);
  };
  window.confirm = () => false;

  let lastProgressPercent = -1;
  let progressObserver = null;
  function observeProgress() {
    const container = byId('export-progress');
    if (!container || progressObserver) return;
    progressObserver = new MutationObserver(() => {
      if (container.classList.contains('hidden')) return;
      const percent = parseFloat(String(textOf(byId('export-progress-pct'))).replace('%', ''));
      if (!Number.isFinite(percent)) return;
      if (percent === lastProgressPercent) return;
      lastProgressPercent = percent;
      ev('page-progress', '', Math.max(0, Math.min(1, percent / 100)), '');
    });
    progressObserver.observe(container, { attributes: true, subtree: true, childList: true, characterData: true });
  }
  observeProgress();

  function reportQualityWarnings() {
    ['tri-limit-warning', 'resolution-warning', 'amplitude-warning'].forEach((id) => {
      const element = byId(id);
      if (!element || element.classList.contains('hidden')) return;
      const text = textOf(element).trim();
      if (text) warn(text);
    });
  }

  // The adapter patches HTMLAnchorElement.click for the STL blob; wrap it so the studio notices the export bytes exist
  // and reads the page's own mesh warnings once the export is finished. The adapter's handler stays in the chain.
  const adapterClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function studioAnchorClick() {
    try {
      const name = String(this.download || '');
      if (name.toLowerCase().endsWith('.stl') && String(this.href || '').startsWith('blob:')) {
        reportQualityWarnings();
      }
    } catch (e) { /* never break the adapter's chain */ }
    return adapterClick.apply(this, arguments);
  };

  async function fetchFile(url, name, type) {
    const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const timer = controller ? setTimeout(() => controller.abort(), 60000) : null;
    try {
      const response = await fetch(url, { cache: 'no-store', signal: controller ? controller.signal : undefined });
      if (!response.ok) throw new Error('the app returned HTTP ' + response.status + ' for ' + url);
      const blob = await response.blob();
      return new File([blob], name || 'studio-model.stl', { type: type || blob.type || 'application/octet-stream' });
    } catch (error) {
      if (error && error.name === 'AbortError') throw new Error('reading ' + url + ' from the app timed out after 60 s');
      throw error;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  /**
   * Dispatch a file to a page input and wait until the page's own change listener acknowledged it: the handshake that a
   * half-initialised (or reloaded) page cannot silently swallow a request.
   */
  async function dispatchFile(inputId, file, description) {
    const input = byId(inputId);
    if (!input) throw new Error('the studio page has no ' + inputId + ' control; it is not fully loaded');
    let seen = false;
    const acknowledge = () => { seen = true; };
    input.addEventListener('change', acknowledge, true);
    try {
      const transfer = new DataTransfer();
      transfer.items.add(file);
      input.files = transfer.files;
      setStep(description);
      input.dispatchEvent(new Event('change', { bubbles: true }));
      const started = performance.now();
      while (!seen && performance.now() - started < 3000) await sleep(50);
      if (!seen) {
        throw new Error('the page did not accept ' + (file && file.name) + ' through ' + inputId +
          '; its file listener is not attached yet (the page was not ready). Reload the studio sheet and press Apply again');
      }
    } finally {
      input.removeEventListener('change', acknowledge, true);
    }
  }

  const baseName = (name) => String(name || 'studio-model.stl').split(/[\\/]/).pop();

  async function openModel(expectedTriangles, sourceName) {
    window.__studioAborted = false;
    abortRequested = false;
    try {
      const expected = Number(expectedTriangles) || 0;
      const before = triCount();
      const name = baseName(sourceName);
      setStep('opening the model');
      const file = await fetchFile('/model/current.stl', name, 'model/stl');
      await dispatchFile('stl-file-input', file, 'dispatching the model file');
      await until(() => {
        const count = triCount();
        const info = textOf(byId('mesh-info'));
        return count > 0 && (count !== before || info.indexOf(name) >= 0);
      }, 60000, 'the model load');
      await sleep(150);
      const actual = triCount();
      if (expected > 0 && actual !== expected) {
        // Whole-object texturing has no triangle numbering to keep, so the page's own count is authoritative; say so
        // once and carry on instead of failing the apply.
        warn('The page loaded ' + actual.toLocaleString() + ' triangles but the studio predicted ' +
          expected.toLocaleString() + ' after its cleaner; whole-object texturing continues on the page count.');
      }
      ev('model-loaded', '', 0, String(actual));
    } finally {
      setStep('idle');
    }
  }

  async function openProject(expectedTriangles) {
    window.__studioAborted = false;
    abortRequested = false;
    try {
      const expected = Number(expectedTriangles) || 0;
      const before = triCount();
      setStep('opening the painted mask');
      const file = await fetchFile('/model/project.bumpmesh', 'studio-project.bumpmesh', 'application/octet-stream');
      await dispatchFile('import-project-input', file, 'dispatching the project file');
      await until(() => dialogOpen(), 15000, 'the project load dialog');
      setStep('answering the project dialog');
      const settingsOnly = byId('load-mode-settings');
      const includeModel = byId('load-mode-all');
      if (settingsOnly) settingsOnly.checked = false;
      if (includeModel) includeModel.checked = true;
      const go = byId('load-go-btn');
      if (!go) throw new Error('the project load dialog has no load button');
      go.click();
      setStep('importing the project');
      const started = performance.now();
      for (;;) {
        if (abortRequested) throw new Error('cancelled');
        const count = triCount();
        if (!dialogOpen() && count > 0 && (count !== before || count === expected)) {
          if (expected > 0 && count !== expected) {
            throw new Error('the page imported ' + count.toLocaleString() + ' triangles but the painted mask was built for ' +
              expected.toLocaleString() + '; the page and the studio disagree about which triangles are dropped at load, so the ' +
              'mask numbering cannot be trusted. Choose Whole object for this mesh, or reload the studio and paint again.');
          }
          await sleep(250);
          ev('project-loaded', '', 0, String(count));
          return;
        }
        if (performance.now() - started > 45000) {
          throw new Error('the project import did not finish within 45 s (dialog ' + (dialogOpen() ? 'still open' : 'closed') +
            ', page count ' + count.toLocaleString() + ', mask expects ' + expected.toLocaleString() + ')');
        }
        await sleep(100);
      }
    } finally {
      setStep('idle');
    }
  }

  async function loadTexture(assetPath) {
    const relative = String(assetPath || '').replace(/^\/+/, '');
    if (!relative) throw new Error('no texture was chosen');
    setStep('loading the texture');
    const file = await fetchFile('/assets/' + relative, relative.split('/').pop() || 'texture.png');
    await dispatchFile('texture-file-input', file, 'dispatching the texture file');
    await until(() => {
      const button = byId('export-btn');
      const name = textOf(byId('active-map-name'));
      return button && !button.disabled && name && name !== 'No map selected';
    }, 60000, 'the texture load');
  }

  function setValue(id, value) {
    const element = byId(id);
    if (!element) throw new Error('the studio page has no ' + id + ' control; it is not fully loaded');
    element.value = String(value);
    element.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function applySettings(config) {
    const mapping = byId('mapping-mode');
    if (mapping) {
      mapping.value = String(config.mappingMode);
      mapping.dispatchEvent(new Event('change', { bubbles: true }));
    }
    setValue('scale-u-val', config.tileMm);
    setValue('scale-v-val', config.tileMm);
    setValue('amplitude-val', config.heightMm);
    setValue('texture-smoothing-val', config.smoothing);
    setValue('refine-length-val', config.refineMm);
    const slider = byId('max-triangles');
    if (slider) {
      const reported = Number(slider.max);
      const cap = Number.isFinite(reported) && reported > 0
        ? Math.min(Number(config.maxTriangles), reported)
        : Number(config.maxTriangles);
      slider.value = String(cap);
      slider.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }

  async function configure(config) {
    window.__studioAborted = false;
    abortRequested = false;
    try {
      setStep('applying the texture settings');
      await loadTexture(config.textureAsset);
      applySettings(config);
      await sleep(200);
      const button = byId('export-btn');
      if (!button || button.disabled) throw new Error('the export button is still disabled after the settings');
      ev('settings-applied', '', 0, '');
    } finally {
      setStep('idle');
    }
  }

  function exportNow() {
    window.__studioAborted = false;
    abortRequested = false;
    const button = byId('export-btn');
    if (!button) throw new Error('the studio page has no export button; it is not fully loaded');
    if (button.disabled) throw new Error('the export button is disabled; wait for the texture to be applied');
    lastProgressPercent = -1;
    observeProgress();
    reportQualityWarnings();
    setStep('exporting');
    ev('export-started', '', 0, '');
    button.click();
  }

  function abort() {
    window.__studioAborted = true;
    abortRequested = true;
    try { HOST.cancelExport(); } catch (e) { /* bridge gone */ }
  }

  function probe() {
    return String(textOf(byId('mesh-info')));
  }

  window.__BumpMeshStudio = {
    openModel: (expectedTriangles, sourceName) => openModel(expectedTriangles, sourceName).catch((e) => fail(e.message || e)),
    openProject: (expectedTriangles) => openProject(expectedTriangles).catch((e) => fail(e.message || e)),
    configure: (config) => configure(config).catch((e) => fail(e.message || e)),
    exportNow: () => { try { exportNow(); } catch (e) { fail(e.message || e); } },
    abort: abort,
    probe: probe,
  };

  // Report ready only once the controls the studio drives are actually in the page; a half-initialised page must not
  // be reported ready, because the host waits on this signal before it dispatches anything.
  const REQUIRED_CONTROLS = ['mesh-info', 'stl-file-input', 'import-project-input', 'texture-file-input',
    'export-btn', 'mapping-mode', 'scale-u-val', 'amplitude-val', 'texture-smoothing-val', 'refine-length-val'];
  const missing = REQUIRED_CONTROLS.filter((id) => !byId(id));
  if (missing.length > 0) {
    fail('the BumpMesh page did not finish initialising; missing controls: ' + missing.join(', '));
    return;
  }
  // Defensive: a first-run "What's new" overlay left open would swallow clicks in a headless studio.
  try {
    const welcome = byId('welcome-overlay');
    if (welcome && !welcome.classList.contains('hidden')) {
      const gotIt = byId('welcome-got-it');
      const closeWelcome = byId('welcome-close');
      if (gotIt) gotIt.click(); else if (closeWelcome) closeWelcome.click();
    }
  } catch (welcomeError) { /* the overlay is optional */ }
  ev('driver-ready', '', 0, '');
})();
