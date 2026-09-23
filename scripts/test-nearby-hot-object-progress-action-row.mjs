#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";

const [workspacePath] = process.argv.slice(2);
if (!workspacePath) throw new Error("Expected thermal-integrity-workspace.js path");
const source = fs.readFileSync(workspacePath, "utf8");

for (const token of [
  "function solverActionRow(group)",
  'const run = document.getElementById("ti-run")',
  'const save = document.getElementById("ti-save")',
  "save.parentElement !== actions",
  'actions.classList.add("ti-run-actions")',
  "actions.insertBefore(cancel, save)",
  "status?.parentElement === container",
]) {
  assert.ok(source.includes(token), `Progress workspace is missing ${token}`);
}
assert.ok(
  !source.includes('const actions = group.querySelector(".ti-actions")'),
  "Progress workspace must never select the first generic .ti-actions row",
);

// This is the exact DOM shape that regressed when plastic-object placement
// added an earlier .ti-actions row: generic selection chooses placementActions,
// while #ti-save belongs to solverActions and insertBefore must reject it.
function makeParent(name) {
  return {
    name,
    children: [],
    insertBefore(node, reference) {
      if (reference !== null && !this.children.includes(reference)) {
        throw new Error(`reference ${reference.id} is not a child of ${name}`);
      }
      const existing = this.children.indexOf(node);
      if (existing >= 0) this.children.splice(existing, 1);
      const index = reference === null ? this.children.length : this.children.indexOf(reference);
      this.children.splice(index, 0, node);
      node.parentElement = this;
      return node;
    },
  };
}

const placementActions = makeParent("placement actions");
placementActions.className = "ti-actions";
const solverActions = makeParent("solver actions");
solverActions.className = "ti-actions";
const run = { id: "ti-run", parentElement: solverActions };
const save = { id: "ti-save", parentElement: solverActions };
solverActions.children.push(run, save);
const cancel = { id: "ti-cancel", parentElement: null };

assert.throws(
  () => placementActions.insertBefore(cancel, save),
  /not a child/,
  "The old first-.ti-actions algorithm must reproduce the browser failure",
);
assert.doesNotThrow(
  () => run.parentElement.insertBefore(cancel, save),
  "Anchoring through #ti-run.parentElement must be valid",
);
assert.equal(cancel.parentElement, solverActions);
assert.deepEqual(solverActions.children.map((node) => node.id), ["ti-run", "ti-cancel", "ti-save"]);

console.log("Nearby Hot Object progress UI anchors to the Calculate/Save action row, not the first generic action row.");
