/*
 * Android host integration for the vendored BumpMesh workspace.
 * BumpMesh itself remains pinned and unmodified; this adapter only loads the
 * current enderslicercura STL and hands exported STL bytes back to Android.
 */
(() => {
  'use strict';

  const nativeBridge = window.EnderSlicerAndroid;
  if (!nativeBridge) return;

  const fallbackLimit = 1_500_000;
  const reportedLimit = Number(nativeBridge.maxOutputTriangles?.());
  const MAX_OUTPUT_TRIANGLES = Number.isFinite(reportedLimit) && reportedLimit > 0
    ? Math.floor(reportedLimit)
    : fallbackLimit;
  const BRIDGE_CHUNK_BYTES = 48 * 1024;
  let modelLoadStarted = false;
  let exportInProgress = false;

  function bytesToBase64(bytes) {
    let binary = '';
    const block = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += block) {
      const part = bytes.subarray(offset, Math.min(offset + block, bytes.length));
      binary += String.fromCharCode.apply(null, part);
    }
    return btoa(binary);
  }

  function configureAndroidMode() {
    document.documentElement.classList.add('android-host');

    const triangleSlider = document.getElementById('max-triangles');
    if (triangleSlider) {
      triangleSlider.max = String(MAX_OUTPUT_TRIANGLES);
      triangleSlider.step = '10000';
      const current = Number.parseInt(triangleSlider.value, 10);
      if (Number.isFinite(current) && current > MAX_OUTPUT_TRIANGLES) {
        triangleSlider.value = String(MAX_OUTPUT_TRIANGLES);
        triangleSlider.dispatchEvent(new Event('input', { bubbles: true }));
        triangleSlider.dispatchEvent(new Event('change', { bubbles: true }));
      }
      triangleSlider.title = `Android export limit: ${MAX_OUTPUT_TRIANGLES.toLocaleString()} triangles`;
    }

    const export3mf = document.getElementById('export-3mf-btn');
    if (export3mf) export3mf.style.display = 'none';

    const fileInput = document.getElementById('stl-file-input');
    if (fileInput) fileInput.accept = '.stl';
  }

  async function loadModelFromAndroid() {
    if (modelLoadStarted) return;
    const input = document.getElementById('stl-file-input');
    if (!input) return;

    modelLoadStarted = true;
    try {
      const response = await fetch('/model/current.stl', { cache: 'no-store' });
      if (!response.ok) throw new Error(`Unable to load source STL (${response.status})`);
      const blob = await response.blob();
      const suggestedName = nativeBridge.sourceFileName() || 'enderslicercura-model.stl';
      const file = new File([blob], suggestedName, { type: 'model/stl' });
      const transfer = new DataTransfer();
      transfer.items.add(file);
      input.files = transfer.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
    } catch (error) {
      modelLoadStarted = false;
      alert(`Could not open the model in BumpMesh: ${error.message || error}`);
    }
  }

  async function sendExportToAndroid(blob, filename) {
    if (exportInProgress) return;
    exportInProgress = true;
    try {
      const accepted = nativeBridge.beginExport(filename || 'textured.stl', blob.size);
      if (!accepted) {
        throw new Error(`Android rejected the STL export. The active limit is ${MAX_OUTPUT_TRIANGLES.toLocaleString()} triangles.`);
      }

      const reader = blob.stream().getReader();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        for (let offset = 0; offset < value.length; offset += BRIDGE_CHUNK_BYTES) {
          const chunk = value.subarray(offset, Math.min(offset + BRIDGE_CHUNK_BYTES, value.length));
          if (!nativeBridge.appendExportChunk(bytesToBase64(chunk))) {
            throw new Error('Android could not store the STL export');
          }
        }
      }
      if (!nativeBridge.finishExport()) throw new Error('Android could not finalize the STL export');
    } catch (error) {
      nativeBridge.cancelExport();
      alert(`Could not return the textured STL to enderslicercura: ${error.message || error}`);
    } finally {
      exportInProgress = false;
    }
  }

  const originalAnchorClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function patchedAnchorClick() {
    const filename = String(this.download || '');
    const href = String(this.href || '');
    if (filename.toLowerCase().endsWith('.stl') && href.startsWith('blob:')) {
      fetch(href)
        .then(response => {
          if (!response.ok) throw new Error(`Unable to read BumpMesh export (${response.status})`);
          return response.blob();
        })
        .then(blob => sendExportToAndroid(blob, filename))
        .catch(error => alert(`Could not read the BumpMesh STL export: ${error.message || error}`));
      return;
    }
    return originalAnchorClick.call(this);
  };

  window.EnderSlicerBridge = { loadModelFromAndroid };
  window.addEventListener('DOMContentLoaded', () => {
    configureAndroidMode();
    setTimeout(loadModelFromAndroid, 0);
  }, { once: true });
})();
