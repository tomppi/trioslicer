  // Preserve installUi callback name for MutationObserver guard. The guard
  // deliberately identifies the imperative Thermal installer by this stable
  // name so mutations produced by mounting the UI cannot recursively reinstall
  // it and freeze the T workspace.
  const enclosureObserverSafeInstallUiBase = installUi;
  installUi = function installUi() {
    return enclosureObserverSafeInstallUiBase();
  };

