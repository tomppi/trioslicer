#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const guardPath = new URL("../app/src/main/filasim/thermal-integrity-guard.js", import.meta.url);
const source = fs.readFileSync(guardPath, "utf8");

class FakeElement {
  constructor(id = "", descendants = []) {
    this.id = id;
    this.descendants = descendants;
  }

  querySelector(selector) {
    if (!selector.startsWith("#")) return null;
    const id = selector.slice(1);
    return this.descendants.find((entry) => entry.id === id) || null;
  }

  closest() {
    return null;
  }
}

class FakeMutationObserver {
  static instances = [];

  constructor(callback) {
    this.callback = callback;
    FakeMutationObserver.instances.push(this);
  }

  observe() {}
  disconnect() {}
}

const document = {
  documentElement: new FakeElement("root"),
  addEventListener() {},
  getElementById() {
    return null;
  },
};

const context = {
  console,
  queueMicrotask,
  Array,
  Atomics,
  Boolean,
  CustomEvent: class CustomEvent {
    constructor(type, init = {}) {
      this.type = type;
      this.detail = init.detail;
    }
  },
  Element: FakeElement,
  Int32Array,
  MessageEvent: class MessageEvent {
    constructor(type, init = {}) {
      this.type = type;
      this.data = init.data;
    }
  },
  MutationObserver: FakeMutationObserver,
  Number,
  Proxy,
  Reflect,
  Set,
  SharedArrayBuffer,
  String,
  document,
};
context.window = {
  MutationObserver: FakeMutationObserver,
  Worker: undefined,
  dispatchEvent() {},
};
context.window.window = context.window;
context.globalThis = context;

vm.runInNewContext(source, context, { filename: guardPath.pathname });

let calls = 0;
function ensureUi() {
  calls += 1;
}

const GuardedObserver = context.window.MutationObserver;
const observer = new GuardedObserver(ensureUi);
const native = FakeMutationObserver.instances.at(-1);
assert.ok(native, "guarded observer was not constructed");

native.callback([{ addedNodes: [{ nodeType: 3 }] }], observer);
assert.equal(calls, 0, "text-node progress updates must not retrigger Thermal UI installation");

native.callback([{ addedNodes: [new FakeElement("ti-progress-phase")] }], observer);
assert.equal(calls, 0, "ordinary Thermal progress elements must not retrigger installation");

native.callback([{ addedNodes: [new FakeElement("enderslicer-thermal-integrity")] }], observer);
assert.equal(calls, 1, "the Thermal group itself must trigger installation");

native.callback([
  {
    addedNodes: [
      new FakeElement("wrapper", [new FakeElement("enderslicer-thermal-integrity")]),
    ],
  },
], observer);
assert.equal(calls, 2, "a remounted subtree containing the Thermal group must trigger installation");

console.log("Thermal MutationObserver feedback-loop guard passed");
