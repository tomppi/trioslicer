#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const [runtimePath] = process.argv.slice(2);
if (!runtimePath) throw new Error("Expected React-safe Nearby Hot Object runtime path");
const source = fs.readFileSync(runtimePath, "utf8");
for (const token of [
  "installUiInReactIsolatedShadowRoot",
  "attachShadow({ mode: \"open\" })",
  "appendNearbyHotObjectIntoShadow",
  "data-keeptool",
  "__enderSlicerThermalShadowLookup",
  "EnderSlicerNearbyReactSafeMountTestApi",
]) {
  assert.ok(source.includes(token), `React-safe Shadow DOM runtime is missing ${token}`);
}

class FakeShadowRoot {
  constructor() {
    this.children = [];
  }
  appendChild(node) {
    if (!this.children.includes(node)) this.children.push(node);
    node.parentElement = null;
    node.parentNode = this;
    return node;
  }
  querySelector(selector) {
    if (selector.startsWith("#")) {
      const id = selector.slice(1);
      return this.children.find((child) => child.id === id) || null;
    }
    if (selector.startsWith("[data-")) {
      return this.children.find((child) => child.shadowStyleMarker === true) || null;
    }
    return null;
  }
  querySelectorAll(selector) {
    const found = this.querySelector(selector);
    return found ? [found] : [];
  }
}

const shadow = new FakeShadowRoot();
let lightDomAppends = 0;
const mount = {
  id: "enderslicer-thermal-integrity-mount",
  shadowRoot: null,
  attributes: {},
  appendChild(node) {
    lightDomAppends += 1;
    node.parentElement = this;
    return node;
  },
  attachShadow(init) {
    assert.equal(init.mode, "open");
    this.shadowRoot = shadow;
    return shadow;
  },
  setAttribute(name, value) {
    this.attributes[name] = String(value);
  },
};

let group = null;
const nativeGetElementById = (id) => {
  if (id === "enderslicer-thermal-integrity-mount") return mount;
  return null;
};
const document = {
  getElementById: nativeGetElementById,
  querySelectorAll() { return []; },
};
let baseCalls = 0;
const context = {
  console,
  GROUP_ID: "enderslicer-thermal-integrity",
  document,
  window: {},
  installUi() {
    baseCalls += 1;
    const target = document.getElementById("enderslicer-thermal-integrity-mount");
    let existing = document.getElementById("enderslicer-thermal-integrity");
    if (!existing) {
      existing = { id: "enderslicer-thermal-integrity", parentElement: null, parentNode: null };
      group = existing;
      target.appendChild(existing);
    } else if (existing.parentElement !== target) {
      target.appendChild(existing);
    }
    return true;
  },
  Object,
};
vm.createContext(context);
vm.runInContext(source, context, { filename: runtimePath });

assert.equal(context.installUi(), true, "Shadow-isolated installer must complete");
assert.equal(baseCalls, 1, "Composed installer must run once");
assert.equal(lightDomAppends, 0, "React-owned mount light DOM must remain untouched");
assert.equal(shadow.children.includes(group), true, "Custom panel must be mounted in Shadow DOM");
assert.equal(mount.attributes["data-keeptool"], "true", "Shadow host must suppress StepPanel tool rerenders");
assert.equal(document.getElementById("enderslicer-thermal-integrity"), group,
  "Thermal-only getElementById fallback must resolve the shadow panel");

assert.equal(context.installUi(), true, "Repeated observer install must stay valid");
assert.equal(baseCalls, 2, "Observer may invoke the composed installer again safely");
assert.equal(lightDomAppends, 0, "Repeated install must never put custom nodes into React light DOM");
assert.equal(shadow.children.filter((child) => child.id === "enderslicer-thermal-integrity").length, 1,
  "Repeated install must reuse one Shadow DOM panel");

console.log("Nearby Hot Object stays outside React light DOM and remains ID-compatible through Shadow DOM.");
