  // Isolate the imperative Nearby Hot Object UI from React's managed light DOM.
  // React owns only the stable mount element; all EnderSlicer controls live in
  // that element's ShadowRoot. This prevents React reconciliation from ever
  // seeing or trying to position nodes that were created imperatively, which
  // eliminates stale insertBefore sibling failures during worker/state updates.
  const REACT_SAFE_THERMAL_MOUNT_ID = "enderslicer-thermal-integrity-mount";
  const REACT_SAFE_SHADOW_STYLE_MARKER = "enderslicer-nearby-shadow-styles-v1";
  let reactSafeShadowRoot = null;

  const reactSafeNativeGetElementById = document.getElementById.bind(document);
  const reactSafeNativeQuerySelectorAll = document.querySelectorAll.bind(document);

  function isThermalShadowId(id) {
    return id === GROUP_ID || String(id).startsWith("ti-");
  }

  // Existing runtime fragments intentionally use document.getElementById and
  // document.querySelectorAll. Keep those APIs working without rewriting every
  // thermal fragment: native document lookup always wins, then thermal-only IDs
  // may fall back into the currently active ShadowRoot.
  if (!document.getElementById.__enderSlicerThermalShadowLookup) {
    const shadowAwareGetElementById = function shadowAwareGetElementById(id) {
      const native = reactSafeNativeGetElementById(id);
      if (native || !isThermalShadowId(id) || !reactSafeShadowRoot) return native;
      return reactSafeShadowRoot.querySelector(`#${id}`);
    };
    Object.defineProperty(shadowAwareGetElementById, "__enderSlicerThermalShadowLookup", {
      value: true,
    });
    document.getElementById = shadowAwareGetElementById;
  }

  if (!document.querySelectorAll.__enderSlicerThermalShadowLookup) {
    const shadowAwareQuerySelectorAll = function shadowAwareQuerySelectorAll(selector) {
      const native = reactSafeNativeQuerySelectorAll(selector);
      if (native.length || typeof selector !== "string" || !selector.startsWith("#ti-")
          || !reactSafeShadowRoot) {
        return native;
      }
      return reactSafeShadowRoot.querySelectorAll(selector);
    };
    Object.defineProperty(shadowAwareQuerySelectorAll, "__enderSlicerThermalShadowLookup", {
      value: true,
    });
    document.querySelectorAll = shadowAwareQuerySelectorAll;
  }

  function ensureThermalShadowRoot(mount) {
    const shadow = mount.shadowRoot || mount.attachShadow({ mode: "open" });
    reactSafeShadowRoot = shadow;
    // StepPanel's document-level click handler treats this host as an imperative
    // tool surface. A click inside Shadow DOM is retargeted to the host outside
    // the shadow boundary, so data-keeptool prevents an unrelated setTool()
    // React update while Calculate/placement controls are being used.
    mount.setAttribute("data-keeptool", "true");
    return shadow;
  }

  function ensureThermalShadowStyles(shadow) {
    if (shadow.querySelector(`[data-${REACT_SAFE_SHADOW_STYLE_MARKER}]`)) return;
    for (const source of reactSafeNativeQuerySelectorAll('link[rel="stylesheet"], style')) {
      const clone = source.cloneNode(true);
      clone.setAttribute(`data-${REACT_SAFE_SHADOW_STYLE_MARKER}`, "true");
      shadow.appendChild(clone);
    }
  }

  const reactSafeInstallUiBase = installUi;
  installUi = function installUiInReactIsolatedShadowRoot() {
    const mount = reactSafeNativeGetElementById(REACT_SAFE_THERMAL_MOUNT_ID);
    if (!mount) {
      reactSafeShadowRoot = null;
      return false;
    }
    const shadow = ensureThermalShadowRoot(mount);

    // The composed legacy installer has exactly one mount.appendChild(group)
    // operation. Redirect that operation into Shadow DOM for the duration of
    // installation; never put custom children into React's light DOM.
    const nativeAppendChild = mount.appendChild;
    mount.appendChild = function appendNearbyHotObjectIntoShadow(node) {
      return shadow.appendChild(node);
    };
    try {
      const installed = reactSafeInstallUiBase();
      ensureThermalShadowStyles(shadow);
      return installed;
    } finally {
      mount.appendChild = nativeAppendChild;
    }
  };

  window.EnderSlicerNearbyReactSafeMountTestApi = Object.freeze({
    ensureThermalShadowRoot,
    isThermalShadowId,
  });
