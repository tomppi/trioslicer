#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";

const path = process.argv[2];
if (!path) throw new Error("Engine scenario order-fix path is required");
const source = fs.readFileSync(path, "utf8");
const environment = source.indexOf("applyEnvironmentPreset(preset.environmentMode)");
const primary = source.indexOf('applySourceType("source", preset.sourceType)');
const overrides = source.indexOf("Object.entries(preset).forEach");
assert.ok(environment >= 0 && primary > environment && overrides > primary,
  "Broad defaults must be applied before scenario-specific overrides");
assert.ok(source.includes("enabled.checked = Boolean(preset.source2Enabled)"));
console.log("Engine scenario preset precedence test passed");
