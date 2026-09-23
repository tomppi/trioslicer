  // Keep the JavaScript air-network feedback on the exact same engine-bay
  // coordinate system used by Rust boundary-face zoning. The first zoned
  // runtime divided a small printed part across all 12 zones by its own bounds,
  // which erased enclosure offsets and overstated local part-to-air coupling.
  // Engine-bay axes are X = left/right width, Y = front/rear depth, Z = height.
  // Zone order remains longitudinal front/middle/rear fastest, then lateral
  // left/right, then lower/upper.
  let activeZonedMappingOptions = null;

  function engineBayZoneForActiveCell(x, y, z, activeBounds, options, hMm) {
    const partCenterCell = [
      (activeBounds.minX + activeBounds.maxX + 1) * 0.5,
      (activeBounds.minY + activeBounds.maxY + 1) * 0.5,
      (activeBounds.minZ + activeBounds.maxZ + 1) * 0.5,
    ];
    const relativeMm = [
      (Number(x) + 0.5 - partCenterCell[0]) * Number(hMm),
      (Number(y) + 0.5 - partCenterCell[1]) * Number(hMm),
      (Number(z) + 0.5 - partCenterCell[2]) * Number(hMm),
    ];
    const sizeMm = [
      Number(options.enclosureWidthMm),
      Number(options.enclosureDepthMm),
      Number(options.enclosureHeightMm),
    ];
    const offsetMm = [
      Number(options.enclosureOffsetXmm),
      Number(options.enclosureOffsetYmm),
      Number(options.enclosureOffsetZmm),
    ];
    if (sizeMm.some((value) => !Number.isFinite(value) || value <= 0)
        || offsetMm.some((value) => !Number.isFinite(value))
        || !Number.isFinite(Number(hMm)) || Number(hMm) <= 0) {
      return null;
    }
    const normalized = relativeMm.map((value, axis) => Math.min(
      0.999_999_999,
      Math.max(0, (value - offsetMm[axis] + sizeMm[axis] * 0.5) / sizeMm[axis]),
    ));
    // Y/depth runs front -> rear. X/width runs left -> right.
    const longitudinal = Math.min(2, Math.floor(normalized[1] * 3));
    const lateral = normalized[0] >= 0.5 ? 1 : 0;
    const vertical = normalized[2] >= 0.5 ? 1 : 0;
    return longitudinal + 3 * lateral + 6 * vertical;
  }

  partZoneMeanTemperatures = function partZoneMeanTemperaturesInEngineBayCoordinates(
    data,
    fallbackC,
  ) {
    const temperatures = data?.temperatures;
    const material = data?.materialFraction;
    const nx = Number(data?.stats?.nx) | 0;
    const ny = Number(data?.stats?.ny) | 0;
    const nz = Number(data?.stats?.nz) | 0;
    const hMm = Number(data?.stats?.h);
    const options = activeZonedMappingOptions;
    const global = Number(data?.stats?.meanTemperatureC ?? fallbackC);
    if (!(temperatures instanceof Float32Array)
        || !(material instanceof Float32Array)
        || nx <= 0 || ny <= 0 || nz <= 0
        || temperatures.length !== material.length
        || temperatures.length !== nx * ny * nz
        || !options) {
      return zoneArray(global);
    }
    const bounds = {
      minX: nx, minY: ny, minZ: nz,
      maxX: -1, maxY: -1, maxZ: -1,
    };
    for (let index = 0; index < material.length; index += 1) {
      if (!(material[index] > 1e-7)) continue;
      const x = index % nx;
      const y = Math.floor(index / nx) % ny;
      const z = Math.floor(index / (nx * ny));
      bounds.minX = Math.min(bounds.minX, x); bounds.maxX = Math.max(bounds.maxX, x);
      bounds.minY = Math.min(bounds.minY, y); bounds.maxY = Math.max(bounds.maxY, y);
      bounds.minZ = Math.min(bounds.minZ, z); bounds.maxZ = Math.max(bounds.maxZ, z);
    }
    if (bounds.maxX < bounds.minX || bounds.maxY < bounds.minY || bounds.maxZ < bounds.minZ) {
      return zoneArray(global);
    }
    const sums = Array(ENGINE_BAY_ZONE_COUNT).fill(0);
    const weights = Array(ENGINE_BAY_ZONE_COUNT).fill(0);
    for (let index = 0; index < material.length; index += 1) {
      const weight = Number(material[index]);
      const temperature = Number(temperatures[index]);
      if (!(weight > 1e-7) || !Number.isFinite(temperature)) continue;
      const x = index % nx;
      const y = Math.floor(index / nx) % ny;
      const z = Math.floor(index / (nx * ny));
      const zone = engineBayZoneForActiveCell(x, y, z, bounds, options, hMm);
      if (zone === null) return zoneArray(global);
      sums[zone] += temperature * weight;
      weights[zone] += weight;
    }
    return sums.map((sum, zone) => weights[zone] > 0 ? sum / weights[zone] : global);
  };

  const zonedTransientMappingBase = runTransientZonedEngineBay;
  runTransientZonedEngineBay = async function runTransientZonedEngineBayWithAlignedMapping(
    options,
    ...args
  ) {
    activeZonedMappingOptions = options;
    try {
      return await zonedTransientMappingBase(options, ...args);
    } finally {
      activeZonedMappingOptions = null;
    }
  };

  const zonedSteadyMappingBase = runSteadyZonedEngineBay;
  runSteadyZonedEngineBay = async function runSteadyZonedEngineBayWithAlignedMapping(
    options,
    ...args
  ) {
    activeZonedMappingOptions = options;
    try {
      return await zonedSteadyMappingBase(options, ...args);
    } finally {
      activeZonedMappingOptions = null;
    }
  };

  window.EnderSlicerZonedMappingTestApi = Object.freeze({
    engineBayZoneForActiveCell,
  });
