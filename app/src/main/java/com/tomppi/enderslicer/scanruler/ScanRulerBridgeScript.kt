package com.tomppi.enderslicer.scanruler

/**
 * The in-page half of the ScanRuler export bridge.
 *
 * Upstream exports download a Blob through an anchor with a `download` attribute. WebView's
 * DownloadListener never sees `blob:` URLs, so this adapter (injected after the page loads,
 * leaving the upstream page unmodified) intercepts those clicks, reads the Blob, splits it
 * into bounded base64 chunks and drives the Kotlin [ScanRulerAndroid] interface:
 *
 *   beginDownload(name, mime, bytes) -> Kotlin opens the SAF picker
 *   __scanRulerBridgeReady()        -> Kotlin's stream is open; send chunks
 *   appendDownloadChunk(base64)     -> one bounded chunk, in order
 *   finishDownload()                -> Kotlin closes and checks the byte count
 *   __scanRulerBridgeResult(ok,msg) -> Kotlin's visible sentence
 *
 * Chunks are 192 KiB raw (256 KiB base64): small enough for a JS bridge string, large enough
 * that a multi-megabyte STEP is a handful of calls. The adapter never writes anything itself;
 * the only destination is the stream the human chose in the system picker.
 */
internal const val SCANRULER_BRIDGE_SCRIPT = """
(function () {
  if (window.__scanRulerAndroidBridge) return;
  window.__scanRulerAndroidBridge = true;
  var CHUNK_BYTES = 192 * 1024;
  var MAX_BYTES = 256 * 1024 * 1024;
  var bridge = window.ScanRulerAndroid;
  var pending = null;
  var blobs = new Map();

  function notice(message, ok) {
    try {
      var node = document.getElementById('__scanRulerNativeNotice');
      if (!node) {
        node = document.createElement('div');
        node.id = '__scanRulerNativeNotice';
        node.style.cssText = 'position:fixed;left:12px;right:12px;bottom:16px;z-index:2147483647;' +
          'padding:10px 14px;border-radius:8px;font:14px/1.4 sans-serif;color:#fff;' +
          'box-shadow:0 4px 18px rgba(0,0,0,.45);';
        document.body.appendChild(node);
      }
      node.style.background = ok ? '#1d5c3a' : '#7a2020';
      node.textContent = message;
      node.style.display = 'block';
      if (ok) setTimeout(function () { if (node) node.style.display = 'none'; }, 5000);
    } catch (e) { /* a page without a body still gets the native Toast */ }
  }

  // Track the Blobs behind object URLs: some exports revoke the URL immediately after the
  // click, and fetching a revoked URL loses the bytes.
  var createObjectURL = URL.createObjectURL.bind(URL);
  URL.createObjectURL = function (object) {
    var url = createObjectURL(object);
    if (object instanceof Blob) blobs.set(url, object);
    return url;
  };
  var revokeObjectURL = URL.revokeObjectURL.bind(URL);
  URL.revokeObjectURL = function (url) {
    setTimeout(function () { blobs.delete(url); }, 60000);
    revokeObjectURL(url);
  };

  function sendNext() {
    if (!pending) return;
    var bytes = pending.bytes;
    if (pending.offset >= bytes.length) {
      pending = null;
      bridge.finishDownload();
      return;
    }
    var end = Math.min(pending.offset + CHUNK_BYTES, bytes.length);
    var slice = bytes.subarray(pending.offset, end);
    pending.offset = end;
    var binary = '';
    for (var i = 0; i < slice.length; i += 4096) {
      binary += String.fromCharCode.apply(null, slice.subarray(i, Math.min(i + 4096, slice.length)));
    }
    var accepted = bridge.appendDownloadChunk(btoa(binary));
    if (!accepted) { pending = null; return; }
    setTimeout(sendNext, 0);
  }

  function start(blob, name) {
    if (pending) {
      // A second export while one is in flight: refused here in the page, without a
      // bridge call and without the stream the first export is sending - the app never
      // hears of it and the in-flight export is untouched.
      notice('Another export is already being written.', false);
      return;
    }
    if (!blob || blob.size <= 0) {
      notice('The export was empty, so no file was written.', false);
      return;
    }
    if (blob.size > MAX_BYTES) {
      notice('The export is too large for this app to write, so no file was written.', false);
      return;
    }
    blob.arrayBuffer().then(function (buffer) {
      var bytes = new Uint8Array(buffer);
      // The read above is async, so a second tap's export can arrive here after this one
      // started: the stream is claimed only while none is pending, or the second claim
      // would overwrite the first one's bytes and stop its chunks for good.
      if (pending) {
        notice('Another export is already being written.', false);
        return;
      }
      pending = { bytes: bytes, offset: 0 };
      var started = bridge.beginDownload(name, blob.type || 'application/octet-stream', bytes.length);
      if (!started) pending = null;
    }).catch(function () {
      notice('Could not read the export, so no file was written.', false);
    });
  }

  window.__scanRulerBridgeReady = function () {
    if (pending) setTimeout(sendNext, 0);
  };
  window.__scanRulerBridgeResult = function (ok, message) {
    pending = null;
    notice(message, ok);
  };

  document.addEventListener('click', function (event) {
    var target = event.target;
    var anchor = target && target.closest ? target.closest('a[download]') : null;
    if (!anchor) return;
    var href = anchor.getAttribute('href') || anchor.href || '';
    if (href.indexOf('blob:') !== 0) return;
    event.preventDefault();
    event.stopPropagation();
    var name = anchor.getAttribute('download') || 'scanruler-export';
    var known = blobs.get(href);
    if (known) {
      start(known, name);
      return;
    }
    fetch(href).then(function (response) { return response.blob(); })
      .then(function (blob) { start(blob, name); })
      .catch(function () { notice('Could not read the export, so no file was written.', false); });
  }, true);

  // This port deliberately excludes the 4 MB fridge-bracket sample (upstream only uses it in
  // an e2e script, but the built page still offers it). The file is not shipped, so the
  // control must not be offered: hide it when it appears and refuse a click if the page
  // creates it later, instead of letting the user open a missing asset.
  var SAMPLE_LABEL = 'Open a sample scan';
  var SAMPLE_NOTE = 'No scan to hand?';
  function hideSampleControl() {
    var nodes = document.querySelectorAll('button, a, [role=button]');
    for (var i = 0; i < nodes.length; i++) {
      var text = (nodes[i].textContent || '').trim();
      if (text === SAMPLE_LABEL || text.indexOf(SAMPLE_NOTE) === 0) {
        nodes[i].style.display = 'none';
      }
    }
    try {
      var note = document.evaluate(
        "//*[contains(text(),'No scan to hand?')]",
        document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null
      );
      for (var k = 0; k < note.snapshotLength; k++) {
        var element = note.snapshotItem(k);
        if (element) element.style.display = 'none';
      }
    } catch (e) { /* an older engine without XPath just keeps the note */ }
  }
  document.addEventListener('click', function (event) {
    var target = event.target;
    var control = target && target.closest ? target.closest('button, a, [role=button]') : null;
    var text = control ? (control.textContent || '').trim() : '';
    if (text === SAMPLE_LABEL || text.indexOf(SAMPLE_NOTE) === 0) {
      event.preventDefault();
      event.stopPropagation();
      notice('The built-in sample scan is not shipped in this offline build - import a scan instead.', false);
    }
  }, true);
  hideSampleControl();
  setInterval(hideSampleControl, 1500);
})();
"""
