package com.tomppi.enderslicer.scanruler.engine

/**
 * The embedded workspace's page DOM adapter. Only the embedded engine injects it: a style
 * element that hides the instrument's chrome but keeps the 3D viewport, the notes group
 * (Note key, pin text box, note rows) and the viewport note pins/labels. The page is not
 * edited. The page's own workspace tab bar is kept too, restyled as a floating touch bar;
 * every rule is fail-soft: an upstream rename makes one rule stop matching, not the page fail;
 * the script catches everything and the page keeps its full UI if it cannot install.
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
        /* The embedded page is an instrument face, not a scrolling document:
           hide scrollbar chrome (touch scrolling still works) so no page
           scrollbar shows up under a workspace. */
        html.scanruler-embedded,
        html.scanruler-embedded * {
            scrollbar-width: none !important;
        }
        html.scanruler-embedded ::-webkit-scrollbar {
            width: 0 !important;
            height: 0 !important;
            display: none !important;
        }

        /* ---- the workspace tabs, kept as the one piece of the top bar ---- */
        html.scanruler-embedded header.top {
            box-sizing: border-box !important;
            position: fixed !important;
            top: 56px !important;
            left: 12px !important;
            right: 12px !important;
            height: auto !important;
            min-height: 0 !important;
            padding: 0 !important;
            margin: 0 !important;
            background: transparent !important;
            border: none !important;
            overflow: visible !important;
            z-index: 55 !important;
            pointer-events: none !important;
        }
        html.scanruler-embedded header.top > :not(.modeswitch) {
            display: none !important;
        }
        html.scanruler-embedded header.top > .modeswitch {
            display: flex !important;
            box-sizing: border-box !important;
            width: 100% !important;
            max-width: 100% !important;
            margin: 0 !important;
            padding: 5px !important;
            gap: 5px !important;
            background: rgba(18, 21, 25, 0.94) !important;
            border: 1px solid rgba(255, 255, 255, 0.16) !important;
            border-radius: 16px !important;
            box-shadow: 0 6px 24px rgba(0, 0, 0, 0.38) !important;
            overflow-x: auto !important;
            overflow-y: hidden !important;
            pointer-events: auto !important;
            -webkit-overflow-scrolling: touch !important;
            scrollbar-width: none !important;
        }
        html.scanruler-embedded header.top > .modeswitch::-webkit-scrollbar {
            display: none !important;
        }
        html.scanruler-embedded .modeswitch button {
            flex: none !important;
            display: inline-flex !important;
            align-items: center !important;
            gap: 7px !important;
            min-height: 46px !important;
            padding: 10px 14px !important;
            border: none !important;
            border-radius: 12px !important;
            background: transparent !important;
            color: #e7e6e1 !important;
            font-family: var(--ui) !important;
            font-size: 14.5px !important;
            font-weight: 600 !important;
            letter-spacing: 0 !important;
            text-transform: none !important;
            white-space: nowrap !important;
        }
        html.scanruler-embedded .modeswitch button + button {
            border-left: none !important;
        }
        html.scanruler-embedded .modeswitch button.on {
            background: #2f8bd6 !important;
            color: #fff !important;
            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.25) !important;
        }
        html.scanruler-embedded .modeswitch button svg.icon {
            display: block !important;
            width: 18px !important;
            height: 18px !important;
        }
        html.scanruler-embedded .modeswitch button .wtxt {
            display: inline !important;
        }

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

        /* ---- the notes panel: touch-sized, lower-left ---- */
        /* Only the reverse workspace renders the notes group. Any other workspace's
           panel is empty here - hide it rather than leave a fixed strip (and its
           scrollbar) at the bottom. */
        html.scanruler-embedded aside.panel:not(:has(.group.notes)) {
            display: none !important;
        }
        html.scanruler-embedded aside.panel {
            position: fixed !important;
            left: 12px !important;
            bottom: 12px !important;
            top: auto !important;
            right: auto !important;
            width: min(480px, 82vw) !important;
            max-height: min(420px, calc(var(--sr-vh, 824px) * 0.5)) !important;
            overflow: auto !important;
            z-index: 40 !important;
            padding: 14px 16px !important;
            border-radius: 18px !important;
            background: rgba(18, 21, 25, 0.94) !important;
            box-shadow: 0 8px 28px rgba(0, 0, 0, 0.42) !important;
        }
        html.scanruler-embedded aside.panel > .group.notes {
            display: block !important;
            margin: 0 !important;
        }
        html.scanruler-embedded aside.panel > .group.notes .toolrow {
            display: flex !important;
            gap: 8px !important;
            flex-wrap: wrap !important;
        }
        html.scanruler-embedded aside.panel > .group.notes .toolrow button {
            flex: 1 1 auto !important;
            min-height: 46px !important;
            padding: 10px 14px !important;
            font-size: 15px !important;
            border-radius: 12px !important;
        }
        html.scanruler-embedded aside.panel > .group.notes .hint {
            font-size: 14px !important;
            line-height: 1.45 !important;
        }
        html.scanruler-embedded .noterow {
            padding: 9px 2px !important;
            gap: 10px !important;
            color: #e7e6e1 !important;
        }
        html.scanruler-embedded .noterow .notetext {
            color: #f1f0eb !important;
        }
        html.scanruler-embedded .noterow.done .notetext {
            color: #a9adb1 !important;
        }
        html.scanruler-embedded .noterow small {
            color: #b9bec3 !important;
        }
        html.scanruler-embedded .noterow .noteask {
            color: #6cb6ee !important;
        }
        html.scanruler-embedded .noterow .notebody:hover,
        html.scanruler-embedded .noterow.editing .notebody {
            background: rgba(255, 255, 255, 0.08) !important;
            border-color: rgba(255, 255, 255, 0.18) !important;
        }
        html.scanruler-embedded .noterow .notekeys .x {
            color: #d6d9dc !important;
        }
        html.scanruler-embedded aside.panel > .group.notes .hint {
            color: #b9bec3 !important;
        }
        html.scanruler-embedded .noterow .notebody {
            padding: 4px 6px !important;
            gap: 2px !important;
        }
        html.scanruler-embedded .noterow .notetext {
            font-size: 14.5px !important;
            line-height: 1.4 !important;
            -webkit-line-clamp: 4 !important;
        }
        html.scanruler-embedded .noterow small {
            font-size: 12px !important;
            line-height: 1.35 !important;
        }
        html.scanruler-embedded .notebadge {
            min-width: 24px !important;
            height: 24px !important;
            padding: 0 5px !important;
            border-radius: 12px !important;
            font-size: 12px !important;
        }
        html.scanruler-embedded .noterow > .notebadge { margin-top: 1px !important; }
        html.scanruler-embedded .noterow .notekeys .x {
            min-width: 34px !important;
            min-height: 34px !important;
            font-size: 15px !important;
        }

        /* ---- the note editor: above the panel and the viewport ---- */
        html.scanruler-embedded .chipbar.notecard {
            display: flex !important;
            flex-direction: column !important;
            position: fixed !important;
            left: 50% !important;
            top: calc(var(--sr-vh, 824px) * 0.5) !important;
            transform: translate(-50%, -50%) !important;
            width: min(420px, 80vw) !important;
            max-width: calc(100vw - 24px) !important;
            max-height: calc(var(--sr-vh, 824px) * 0.72) !important;
            overflow-y: auto !important;
            padding: 14px !important;
            gap: 10px !important;
            border-radius: 16px !important;
            z-index: 120 !important;
            box-sizing: border-box !important;
        }
        html.scanruler-embedded .notecard-head {
            display: flex !important;
            font-size: 14px !important;
            gap: 8px !important;
        }
        html.scanruler-embedded .notecard-head .notebadge {
            min-width: 24px !important;
            height: 24px !important;
            border-radius: 12px !important;
            font-size: 12px !important;
        }
        html.scanruler-embedded .notecard textarea {
            min-height: 80px !important;
            padding: 10px 12px !important;
            font-size: 15px !important;
            line-height: 1.4 !important;
        }
        html.scanruler-embedded .notecard-question {
            font-size: 14px !important;
            line-height: 1.45 !important;
        }
        html.scanruler-embedded .notecard-reply { font-size: 13px !important; }
        html.scanruler-embedded .notecard-keys {
            display: flex !important;
            gap: 10px !important;
            flex-wrap: wrap !important;
        }
        html.scanruler-embedded .notecard-keys button {
            flex: 1 1 auto !important;
            min-height: 44px !important;
            padding: 8px 16px !important;
            font-size: 15px !important;
            border-radius: 12px !important;
        }

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
        html.scanruler-embedded .viewport-label.note-label {
            display: block !important;
            font-size: 14px !important;
            padding: 4px 9px !important;
            max-width: 280px !important;
            border-left-width: 4px !important;
        }
    """.trimIndent()

    fun installScript(): String = """
        (function () {
          try {
            var root = document.documentElement;
            if (!root) return;
            root.classList.add('$EMBEDDED_CLASS');
            if (!window.__scanRulerViewportHook) {
              window.__scanRulerViewportHook = function () {
                root.style.setProperty('--sr-vh', (window.innerHeight || 0) + 'px');
              };
              window.__scanRulerViewportHook();
              window.addEventListener('resize', window.__scanRulerViewportHook);
              window.addEventListener('orientationchange', window.__scanRulerViewportHook);
            }
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