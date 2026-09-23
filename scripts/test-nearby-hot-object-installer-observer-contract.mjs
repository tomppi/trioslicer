#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const contractPath = process.argv[2];
if (!contractPath) throw new Error("Thermal installer observer-contract path is required");
const source = fs.readFileSync(contractPath, "utf8");
const context = vm.createContext({ calls: 0 });
vm.runInContext(`
"use strict";
function installUi() {
  calls += 1;
  return "installed";
}
${source}
this.contractResult = {
  callbackName: installUi.name,
  value: installUi(),
  calls,
};
`, context);

assert.equal(
  context.contractResult.callbackName,
  "installUi",
  "Thermal installer callback name must remain compatible with the MutationObserver guard",
);
assert.equal(context.contractResult.value, "installed", "Observer-safe wrapper must delegate to the enclosure installer");
assert.equal(context.contractResult.calls, 1, "Observer-safe wrapper must invoke its base installer exactly once");
console.log("Nearby Hot Object installer MutationObserver contract passed");
