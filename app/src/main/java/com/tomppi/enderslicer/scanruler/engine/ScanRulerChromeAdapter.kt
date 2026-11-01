package com.tomppi.enderslicer.scanruler.engine

/**
 * The embedded workspace's page DOM adapter. Only the embedded engine injects it: a style
 * element that hides the instrument's chrome but keeps the 3D viewport, the notes group
 * (Note key, pin text box, note rows) and the viewport note pins/labels. The page is not
 * edited. Every rule is fail-soft: an upstream rename makes one rule stop matching, not
 * the page fail; the script catches everything and the page keeps its full UI if it cannot
 * install.
 */
object ScanRulerChromeAdapter {

    const val STYLE_ID = "scanruler-embedded-style"
    const val EMBEDDED_CLASS = "scanruler-embedded"
    const val EMBEDDED_WORKSPACE = "reverse"

    internal val CSS = """
        /* The page is a desktop instrument: .app has a 640px floor, so on a
           phone-width viewport it overflows sideways and the 3D stage starts
           off-screen. Embedded mode gives the page the WebView's real width;
           the panel is out of the flow, so the stage takes the whole face.

           The WebView loads the page before the plate attaches it, and this
           Chromium resolves the page's 100dvh/percentage height chain to 0 in
           that window (innerHeight is fine, the units are not). A fixed root
           with inset:0 gets its height from the viewport itself, so the app
           fills the WebView whatever the units did at load. */
        html.scanruler-embedded,
        html.scanruler-embedded body {
            width: 100% !important;
            min-width: 0 !important;
            overflow: hidden !important;
        }
        html.scanruler-embedded #root {
            position: fixed !important;
            inset: 0 !important;
            width: auto !important;
            height: auto !important;
            min-width: 0 !important;
            overflow: hidden !important;
        }
        html.scanruler-embedded .app {
            width: 100% !important;
            height: 100% !important;
            min-width: 0 !important;
            overflow: hidden !important;
        }
        html.scanruler-embedded .mid { min-height: 0 !important; }
        html.scanruler-embedded header.top,
        html.scanruler-embedded .modeswitch,
        html.scanruler-embedded .viewbar,
        html.scanruler-embedded .strip,
        html.scanruler-embedded .statusstrip,
        html.scanruler-embedded .status-strip,
        html.scanruler-embedded .connector-bar,
        html.scanruler-embedded .connectorbar,
        html.scanruler-embedded .recovery-bar,
        html.scanruler-embedded .recoverybar,
        html.scanruler-embedded .startpane,
        html.scanruler-embedded .stagecorner,
        html.scanruler-embedded .hintchip,
        html.scanruler-embedded .warnchip,
        html.scanruler-embedded .toast,
        html.scanruler-embedded .drop-overlay,
        html.scanruler-embedded .splitcap,
        html.scanruler-embedded .modal,
        html.scanruler-embedded .settings,
        html.scanruler-embedded aside.panel > *:not(.group.notes),
        html.scanruler-embedded aside.panel > .group.notes .g-label,
        html.scanruler-embedded aside.panel > .group.notes .toolrow [data-test="note-remove-all"] {
            display: none !important;
        }
        html.scanruler-embedded aside.panel {
            position: fixed !important;
            left: 12px !important;
            bottom: 12px !important;
            top: auto !important;
            right: auto !important;
            width: min(360px, 58vw) !important;
            /* Fixed px, not vh: viewport units resolve to 0 in the detached WebView. */
            max-height: 380px !important;
            overflow: auto !important;
            z-index: 40 !important;
            padding: 8px 10px !important;
            border-radius: 14px !important;
            background: rgba(18, 21, 25, 0.88) !important;
            backdrop-filter: blur(8px);
            box-shadow: 0 6px 24px rgba(0, 0, 0, 0.35);
        }
        html.scanruler-embedded aside.panel > .group.notes {
            display: block !important;
            margin: 0 !important;
        }
        html.scanruler-embedded aside.panel > .group.notes .toolrow { display: flex !important; }
        html.scanruler-embedded .stage {
            flex: 1 1 auto !important;
            width: 100% !important;
            min-width: 0 !important;
        }
        html.scanruler-embedded .viewslot,
        html.scanruler-embedded .viewport {
            width: 100% !important;
            height: 100% !important;
        }
        html.scanruler-embedded .viewport-label.note-label,
        html.scanruler-embedded .note-label,
        html.scanruler-embedded .notecard,
        html.scanruler-embedded .notecard-keys,
        html.scanruler-embedded .noterow { display: initial; }
        html.scanruler-embedded .note-label { display: block !important; }
    """.trimIndent()

    fun installScript(): String = """
        (function () {
          try {
            var root = document.documentElement;
            if (!root) return;
            root.classList.add('$EMBEDDED_CLASS');
            function install() {
              if (document.getElementById('$STYLE_ID')) return;
              var style = document.createElement('style');
              style.id = '$STYLE_ID';
              style.textContent = ${org.json.JSONObject.quote(CSS)};
              (document.head || root).appendChild(style);
            }
            install();
            if (!window.__scanRulerEmbeddedObserver) {
              window.__scanRulerEmbeddedObserver = new MutationObserver(function () {
                root.classList.add('$EMBEDDED_CLASS');
                install();
              });
              window.__scanRulerEmbeddedObserver.observe(root, { childList: true, subtree: true });
            }
            console.warn('ScanRuler embedded workspace: chrome hidden, notes kept.');
          } catch (e) {
            console.warn('ScanRuler embedded adapter failed; page continues with its full UI: ' + e);
          }
        })();
    """.trimIndent()

    fun removeScript(): String = """
        (function () {
          try {
            document.documentElement.classList.remove('$EMBEDDED_CLASS');
            var style = document.getElementById('$STYLE_ID');
            if (style && style.parentNode) style.parentNode.removeChild(style);
            if (window.__scanRulerEmbeddedObserver) {
              window.__scanRulerEmbeddedObserver.disconnect();
              window.__scanRulerEmbeddedObserver = null;
            }
            console.warn('ScanRuler embedded workspace: full UI restored.');
          } catch (e) {
            console.warn('ScanRuler full UI restore failed: ' + e);
          }
        })();
    """.trimIndent()
}