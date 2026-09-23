/* Prevent Anneal workspace MutationObserver feedback loops on Android. */
(() => {
  "use strict";

  const MOUNT_ID = "enderslicer-annealing-calculator-mount";
  const CALLBACK_NAMES = new Set(["installUi", "installFilaSimMaterialUi"]);

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

  function recordsAddAnnealingMount(records) {
    return Boolean(mountFromRecords(records));
  }

  function installObserverGuard() {
    const NativeMutationObserver = window.MutationObserver;
    if (!NativeMutationObserver || NativeMutationObserver.__enderSlicerAnnealingObserverGuard) return;

    const WrappedMutationObserver = new Proxy(NativeMutationObserver, {
      construct(Target, args) {
        const callback = args[0];
        const protectsInstaller = typeof callback === "function" && CALLBACK_NAMES.has(callback.name);
        // Both Anneal installers run once before creating their observers. Save
        // the mount that was already handled so their own DOM updates do not
        // recursively trigger installation again.
        let installedMount = protectsInstaller ? document.getElementById(MOUNT_ID) : null;
        const guardedCallback = protectsInstaller
          ? (records, observer) => {
              const currentMount = document.getElementById(MOUNT_ID) || mountFromRecords(records);
              if (!currentMount) {
                // T and A are reconciled onto the same React DOM element. The
                // id disappearing means Anneal is inactive; clear the identity
                // so the reused node is installed when A becomes active again.
                installedMount = null;
                return;
              }
              // Mutations produced by the installed UI must not call installUi again.
              if (currentMount === installedMount) return;
              installedMount = currentMount;
              callback(records, observer);
            }
          : callback;
        const observer = Reflect.construct(Target, [guardedCallback]);
        if (protectsInstaller && typeof observer.observe === "function") {
          const nativeObserve = observer.observe.bind(observer);
          observer.observe = (target, options = {}) => {
            // React may switch T <-> A by changing only this reused div's id.
            // Watch just id attributes in addition to the caller's child-list
            // settings; mount identity filtering still blocks feedback loops.
            const attributeFilter = Array.from(new Set([...(options.attributeFilter || []), "id"]));
            nativeObserve(target, { ...options, attributes: true, attributeFilter });
          };
        }
        return observer;
      },
    });

    Object.defineProperty(WrappedMutationObserver, "__enderSlicerAnnealingObserverGuard", {
      value: true,
    });
    window.MutationObserver = WrappedMutationObserver;
  }

  window.EnderSlicerAnnealingObserverTestApi = Object.freeze({
    recordsAddAnnealingMount,
  });

  installObserverGuard();
})();
