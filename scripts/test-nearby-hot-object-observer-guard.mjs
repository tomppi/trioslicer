#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const guardPath = process.argv[2];
if (!guardPath) throw new Error("observer guard path is required");

class FakeMutationObserver {
  constructor(callback) {
    this.callback = callback;
  }
  observe() {}
  disconnect() {}
}

let currentThermalMount = null;
const document = {
  getElementById(id) {
    return id === "enderslicer-thermal-integrity-mount" ? currentThermalMount : null;
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
const ordinaryNode = {
  id: "ti-status",
  querySelector() {
    return null;
  },
};
observer.callback([{ addedNodes: [ordinaryNode] }], observer);
assert.equal(installs, 0, "mutations without a thermal mount must not install the UI");

const reusedPanelNode = {
  id: "enderslicer-thermal-integrity-mount",
  querySelector() {
    return null;
  },
};
currentThermalMount = reusedPanelNode;
observer.callback([{ addedNodes: [reusedPanelNode] }], observer);
assert.equal(installs, 1, "opening T must install the Nearby Hot Object UI once");

observer.callback([{ addedNodes: [ordinaryNode] }], observer);
assert.equal(installs, 1, "status and result mutations inside T must not reinstall the UI");

// React switches T -> A by reusing the same div and changing its id. No new
// thermal mount node is added, so the guard must notice that T disappeared and
// clear its remembered mount identity.
currentThermalMount = null;
observer.callback([{ addedNodes: [ordinaryNode] }], observer);
assert.equal(installs, 1, "switching away from T must not run the installer");

// React then switches A -> T by assigning the thermal id back to that same DOM
// node. This must be treated as a fresh mount even though object identity is the
// same as the first T panel.
currentThermalMount = reusedPanelNode;
observer.callback([{ addedNodes: [ordinaryNode] }], observer);
assert.equal(installs, 2, "returning from A to T on the reused panel must reinstall once");

observer.callback([{ addedNodes: [ordinaryNode] }], observer);
assert.equal(installs, 2, "later internal T mutations must remain ignored");

const replacementMount = {
  id: "enderslicer-thermal-integrity-mount",
  querySelector() {
    return null;
  },
};
currentThermalMount = replacementMount;
observer.callback([{ addedNodes: [replacementMount] }], observer);
assert.equal(installs, 3, "a genuinely replaced thermal mount must install once");

console.log("Nearby Hot Object observer guard and T/A remount regression passed");
