#!/usr/bin/env node
import fs from "node:fs";

const [,, runtimePath, transformPath] = process.argv;
if (!runtimePath || !transformPath) throw new Error("Expected runtime and transform paths");
const runtime = fs.readFileSync(runtimePath, "utf8");
const transform = fs.readFileSync(transformPath, "utf8");
for (const contract of [
  "Show source markers and calculation-gap guides",
  "Mark sources in 3D",
  "Drag primary source",
  "Drag turbo / exhaust source",
  "enderslicer-nearby-hot-object-drag-mode",
  "enderslicer-nearby-hot-object-drag",
  "renderCombinedHeatSourceMarkers",
]) {
  if (!runtime.includes(contract)) throw new Error(`Runtime missing ${contract}`);
}
for (const contract of [
  "setNearbyHotObjectDragMode",
  "nearbyHotObjectDragPlane",
  "gapGuide",
  "onNearbyHotObjectDrag",
  "NEARBY_DRAG_MODE_EVENT",
  "document.addEventListener(\"pointermove\", this.onNearbyHotObjectDragMove, true)",
]) {
  if (!transform.includes(contract)) throw new Error(`Viewer transform missing ${contract}`);
}
console.log("Nearby hot object marker/drag contracts verified.");
