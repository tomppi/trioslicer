/*
 * BumpMesh studio driver.
 *
 * Injected into the pinned BumpMesh page by BumpMeshStudio (app/src/main/java/.../texturizer/BumpMeshStudio.kt) after the
 * page finishes loading. It exposes window.__BumpMeshStudio with four commands the native host calls through
 * evaluateJavascript - openModel/openProject, configure, exportNow, abort - and talks back through the
 * EnderSlicerStudio bridge with named events, warnings and errors.
 *
 * It deliberately drives the page only through the controls a user would touch: #stl-file-input / #import-project-input
 * for the source geometry, the custom texture input (#texture-file-input) for every texture, the page's own setting
 * controls (#mapping-mode, #scale-u-val, #scale-v-val, #amplitude-val, #texture-smoothing-val, #refine-length-val,
 * #max-triangles) and #export-btn. The pinned BumpMesh files stay untouched; the adapter (android-bridge.js) still does
 * the export streaming through window.EnderSlicerAndroid.
 */
(() => {
  'use strict';
  if (window.__BumpMeshStudio) return;
  const HOST = window.EnderSlicerStudio;
  if (!HOST) return;

  const ev = (kind, label, fraction, message) => {
    try {
      HOST.onEvent(String(kind || ''), String(label || ''),
        Number.isFinite(fraction) ? Number(fraction) : 0, String(message || ''));
    } catch (e) { /* bridge gone */ }
  };
  const warn = (message) => {
    try { HOST.onWarning(String(message || '')); } catch (e) { /* bridge gone */ }
  };
  const fail = (message) => {
    try { HOST.onError(String(message || '')); } catch (e) { /* bridge gone */ }
    try { console.error('[bumpmesh-studio] ' + message); } catch (e) { /* ignore */ }
  };
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const byId = (id) => document.getElementById(id);
  const textOf = (element) => (element ? String(element.textContent || '') : '');
  const triCount = () => {
    const match = textOf(byId('mesh-info')).match(/([0-9][0-9,]*)\s*triangles/);
    return match ? parseInt(match[1].replace(/,/g, ''), 10) : 0;
  };
  const until = async (check, timeoutMs, label) => {
    const started = performance.now();
    for (;;) {
      let ok = false;
      try { ok = !!check(); } catch (e) { ok = false; }
      if (ok) return;
      if (performance.now() - started > timeoutMs) throw new Error(label + ' timed out');
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
    const response = await fetch(url, { cache: 'no-store' });
    if (!response.ok) throw new Error(url + ' returned HTTP ' + response.status);
    const blob = await response.blob();
    return new File([blob], name || 'studio-model.stl', { type: type || blob.type || 'application/octet-stream' });
  }

  function dispatchFile(inputId, file) {
    const input = byId(inputId);
    if (!input) throw new Error('missing ' + inputId);
    const transfer = new DataTransfer();
    transfer.items.add(file);
    input.files = transfer.files;
    input.dispatchEvent(new Event('change', { bubbles: true }));
  }

  const baseName = (name) => String(name || 'studio-model.stl').split(/[\\/]/).pop();

  async function openModel(expectedTriangles, sourceName) {
    window.__studioAborted = false;
    const name = baseName(sourceName);
    const file = await fetchFile('/model/current.stl', name, 'model/stl');
    dispatchFile('stl-file-input', file);
    await until(
      () => triCount() === Number(expectedTriangles) && textOf(byId('mesh-info')).indexOf(name) >= 0,
      240000,
      'model load',
    );
    await sleep(200);
    ev('model-loaded', '', 0, String(triCount()));
  }

  async function openProject(expectedTriangles) {
    window.__studioAborted = false;
    const file = await fetchFile('/model/project.bumpmesh', 'studio-project.bumpmesh', 'application/octet-stream');
    dispatchFile('import-project-input', file);
    await until(() => {
      const dialog = byId('load-dialog');
      return dialog && !dialog.classList.contains('hidden');
    }, 30000, 'project dialog');
    const settingsOnly = byId('load-mode-settings');
    const includeModel = byId('load-mode-all');
    if (settingsOnly) settingsOnly.checked = false;
    if (includeModel) includeModel.checked = true;
    const go = byId('load-go-btn');
    if (!go) throw new Error('missing project load button');
    go.click();
    await until(() => {
      const dialog = byId('load-dialog');
      return (!dialog || dialog.classList.contains('hidden')) && triCount() === Number(expectedTriangles);
    }, 240000, 'project import');
    await sleep(250);
    ev('project-loaded', '', 0, String(triCount()));
  }

  async function loadTexture(assetPath) {
    const relative = String(assetPath || '').replace(/^\/+/, '');
    if (!relative) throw new Error('no texture was chosen');
    const file = await fetchFile('/assets/' + relative, relative.split('/').pop() || 'texture.png');
    dispatchFile('texture-file-input', file);
    await until(() => {
      const button = byId('export-btn');
      const name = textOf(byId('active-map-name'));
      return button && !button.disabled && name && name !== 'No map selected';
    }, 90000, 'texture load');
  }

  function setValue(id, value) {
    const element = byId(id);
    if (!element) throw new Error('missing ' + id);
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
    await loadTexture(config.textureAsset);
    applySettings(config);
    await sleep(200);
    const button = byId('export-btn');
    if (!button || button.disabled) throw new Error('the export button is still disabled after the settings');
    ev('settings-applied', '', 0, '');
  }

  function exportNow() {
    window.__studioAborted = false;
    const button = byId('export-btn');
    if (!button) throw new Error('missing export button');
    if (button.disabled) throw new Error('the export button is disabled');
    lastProgressPercent = -1;
    observeProgress();
    reportQualityWarnings();
    ev('export-started', '', 0, '');
    button.click();
  }

  function abort() {
    window.__studioAborted = true;
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

  ev('driver-ready', '', 0, '');
})();
