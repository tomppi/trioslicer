/* Prevent Nearby Hot Object MutationObserver feedback loops on Android. */
(() => {
  "use strict";

  const MOUNT_ID = "enderslicer-thermal-integrity-mount";
  const CALLBACK_NAME = "installUi";

  function mountFromNode(node) {
    if (!node || typeof node !== "object") return null;
    if (node.id === MOUNT_ID) return node;
    return typeof node.querySelector === "function" ? node.querySelector(`#${MOUNT_ID}`) : null;
  }

  function mountFromRecords(records) {
    for (const record of Array.from(records || [])) {
      for (const node of Array.from(record?.addedNodes || [])) {
        const mount = mountFromNode(node);
        if (mount) return mount;
      }
    }
    return null;
  }

  function recordsAddThermalMount(records) {
    return Boolean(mountFromRecords(records));
  }

  function installObserverGuard() {
    const NativeMutationObserver = window.MutationObserver;
    if (!NativeMutationObserver || NativeMutationObserver.__enderSlicerNearbyObserverGuard) return;

    const WrappedMutationObserver = new Proxy(NativeMutationObserver, {
      construct(Target, args) {
        const callback = args[0];
        const protectsInstaller = typeof callback === "function" && callback.name === CALLBACK_NAME;
        // installUi is called once directly before its observer is created. Keep
        // the mount identity that was already installed so mutations made by
        // that installation cannot immediately trigger a second installation.
        let installedMount = protectsInstaller ? document.getElementById(MOUNT_ID) : null;
        const guardedCallback = protectsInstaller
          ? (records, observer) => {
              const currentMount = document.getElementById(MOUNT_ID) || mountFromRecords(records);
              if (!currentMount) {
                // React reuses the same panel element for T and A. Reset when
                // the thermal id disappears so that the same DOM node can be
                // installed again when the user switches back to T.
                installedMount = null;
                return;
              }
              if (currentMount === installedMount) return;
              installedMount = currentMount;
              callback(records, observer);
            }
          : callback;
        const observer = Reflect.construct(Target, [guardedCallback]);
        if (protectsInstaller && typeof observer.observe === "function") {
          const nativeObserve = observer.observe.bind(observer);
          observer.observe = (target, options = {}) => {
            // T <-> A can be reconciled by changing only the reused div's id,
            // which produces no childList record. Observe just id attributes in
            // addition to the caller's existing child-list configuration.
            const attributeFilter = Array.from(new Set([...(options.attributeFilter || []), "id"]));
            nativeObserve(target, { ...options, attributes: true, attributeFilter });
          };
        }
        return observer;
      },
    });

    Object.defineProperty(WrappedMutationObserver, "__enderSlicerNearbyObserverGuard", {
      value: true,
    });
    window.MutationObserver = WrappedMutationObserver;
  }

  window.EnderSlicerNearbyObserverTestApi = Object.freeze({
    recordsAddThermalMount,
  });

  installObserverGuard();
})();
