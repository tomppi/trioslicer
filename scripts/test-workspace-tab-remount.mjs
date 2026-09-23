#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const [nearbyGuardPath, annealingGuardPath, workspaceTransformPath] = process.argv.slice(2);
if (!nearbyGuardPath || !annealingGuardPath || !workspaceTransformPath) {
  throw new Error("Nearby guard, Anneal guard and workspace transform paths are required");
}

class FakeMutationObserver {
  constructor(callback) {
    this.callback = callback;
    this.observeTarget = null;
    this.observeOptions = null;
  }
  observe(target, options) {
    this.observeTarget = target;
    this.observeOptions = options;
  }
  disconnect() {}
}

function exerciseGuard(guardPath, mountId, label) {
  let currentMount = null;
  const document = {
    getElementById(id) {
      return id === mountId ? currentMount : null;
    },
  };
  const window = { MutationObserver: FakeMutationObserver };
  const context = vm.createContext({ window, document, console });
  vm.runInContext(fs.readFileSync(guardPath, "utf8"), context, { filename: guardPath });

  let installs = 0;
  function installUi() {
    installs += 1;
  }

  const observer = new window.MutationObserver(installUi);
  const observedRoot = {};
  observer.observe(observedRoot, { childList: true, subtree: true });
  assert.equal(observer.observeTarget, observedRoot, `${label}: observer target must be preserved`);
  assert.equal(observer.observeOptions.childList, true, `${label}: child-list observation must be preserved`);
  assert.equal(observer.observeOptions.subtree, true, `${label}: subtree observation must be preserved`);
  assert.equal(observer.observeOptions.attributes, true, `${label}: reused panel id changes must be observed`);
  assert.ok(observer.observeOptions.attributeFilter.includes("id"), `${label}: id must be in attributeFilter`);

  const internalMutation = [{ addedNodes: [{ id: `${label}-status`, querySelector() { return null; } }] }];
  const idMutation = [{ type: "attributes", attributeName: "id", addedNodes: [] }];
  const reusedPanel = { id: mountId, querySelector() { return null; } };

  observer.callback(internalMutation, observer);
  assert.equal(installs, 0, `${label}: an inactive workspace must not install`);

  currentMount = reusedPanel;
  observer.callback(idMutation, observer);
  assert.equal(installs, 1, `${label}: opening through a reused panel id change must install once`);

  observer.callback(internalMutation, observer);
  assert.equal(installs, 1, `${label}: internal mutations must not recursively reinstall`);

  // The guards remain compatible with a reused node even though the React fix
  // now gives T and A distinct keyed roots and normally replaces the subtree.
  currentMount = null;
  observer.callback(idMutation, observer);
  assert.equal(installs, 1, `${label}: switching away must only reset mount tracking`);

  currentMount = reusedPanel;
  observer.callback(idMutation, observer);
  assert.equal(installs, 2, `${label}: returning on the reused panel must reinstall once`);

  observer.callback(internalMutation, observer);
  assert.equal(installs, 2, `${label}: the remounted workspace must remain loop-free`);
}

function verifyKeyedWorkspaceRoots(transformPath) {
  const source = fs.readFileSync(transformPath, "utf8");
  const thermalKey = 'key="enderslicer-thermal-workspace"';
  const annealingKey = 'key="enderslicer-annealing-workspace"';
  assert.equal(
    source.split(thermalKey).length - 1,
    2,
    "Thermal root key must exist in the replacement and generated-source contract",
  );
  assert.equal(
    source.split(annealingKey).length - 1,
    2,
    "Anneal root key must exist in the replacement and generated-source contract",
  );
  assert.match(source, /\.enderslicer-workspace-tab-state-fix-v2/);
  assert.notEqual(thermalKey, annealingKey, "T and A must never share a React reconciliation key");
}

exerciseGuard(
  nearbyGuardPath,
  "enderslicer-thermal-integrity-mount",
  "Nearby Hot Object T tab",
);
exerciseGuard(
  annealingGuardPath,
  "enderslicer-annealing-calculator-mount",
  "Annealing A tab",
);
verifyKeyedWorkspaceRoots(workspaceTransformPath);

console.log("T/A observer and keyed-root remount regression passed");
