// ../../external/egs-core/packages/utils/splat-utils/worker.ts
var DEPTH_INFINITY = 31743;
var buckets = new Uint32Array(65536);
function sortSplats(counts, sorting, order) {
  buckets.fill(0);
  for (let i = 0; i < counts; i++) {
    buckets[sorting[i]]++;
  }
  let activeCount = 0;
  for (let i = DEPTH_INFINITY - 1; i >= 0; i--) {
    const v = buckets[i];
    buckets[i] = activeCount;
    activeCount += v;
  }
  for (let i = 0; i < counts; i++) {
    const v = sorting[i];
    if (v < DEPTH_INFINITY) {
      order[buckets[v]++] = i;
    }
  }
  return activeCount;
}
var DEPTH_INFINITY_F32 = 2139095040 - 1;
var KEY_SHIFT = 8;
var RADIX_BITS = 8;
var RADIX = 1 << RADIX_BITS;
var RADIX_MASK = RADIX - 1;
var HIGH_RADIX = 1 << 24 - RADIX_BITS;
var HI_OFFSET = RADIX;
var radixBuckets;
var scratch;
var depthBuckets;
function sort24Splats(counts, sorting, order) {
  if (counts < 8 * 1024 * 1024) {
    return radixSort24Splats(counts, sorting, order);
  }
  let minKey = DEPTH_INFINITY_F32;
  let maxKey = -1;
  for (let i = 0; i < counts; i++) {
    const key = sorting[i];
    if (key < DEPTH_INFINITY_F32) {
      if (key < minKey) {
        minKey = key;
      }
      if (key > maxKey) {
        maxKey = key;
      }
    }
  }
  if (maxKey < minKey) {
    return 0;
  }
  const minDepth = minKey >>> KEY_SHIFT;
  const bucketCount = (maxKey >>> KEY_SHIFT) - minDepth + 1;
  if (bucketCount > counts / 4) {
    return radixSort24Splats(counts, sorting, order);
  }
  if (!depthBuckets || depthBuckets.length < bucketCount) {
    depthBuckets = new Uint32Array(bucketCount);
  }
  const buckets2 = depthBuckets;
  buckets2.fill(0, 0, bucketCount);
  for (let i = 0; i < counts; i++) {
    const key = sorting[i];
    if (key < DEPTH_INFINITY_F32) {
      buckets2[(key >>> KEY_SHIFT) - minDepth]++;
    }
  }
  let activeCount = 0;
  for (let i = bucketCount - 1; i >= 0; i--) {
    const count = buckets2[i];
    buckets2[i] = activeCount;
    activeCount += count;
  }
  for (let i = 0; i < counts; i++) {
    const key = sorting[i];
    if (key < DEPTH_INFINITY_F32) {
      order[buckets2[(key >>> KEY_SHIFT) - minDepth]++] = i;
    }
  }
  return activeCount;
}
function radixSort24Splats(counts, sorting, order) {
  if (!radixBuckets) {
    radixBuckets = new Uint32Array(RADIX + HIGH_RADIX);
  }
  if (!scratch || scratch.indices.length < counts) {
    scratch = { indices: new Uint32Array(counts), digits: new Uint16Array(counts) };
  }
  const { indices, digits } = scratch;
  const buckets2 = radixBuckets;
  buckets2.fill(0);
  let activeCount = 0;
  for (let i = 0; i < counts; ++i) {
    const key = sorting[i];
    if (key >= DEPTH_INFINITY_F32) {
      continue;
    }
    const inv = ~key >>> KEY_SHIFT;
    buckets2[inv & RADIX_MASK] += 1;
    buckets2[HI_OFFSET + (inv >>> RADIX_BITS)] += 1;
    activeCount++;
  }
  let offset = 0;
  for (let b = 0; b < RADIX; ++b) {
    const count = buckets2[b];
    buckets2[b] = offset;
    offset += count;
  }
  for (let i = 0; i < counts; ++i) {
    const key = sorting[i];
    if (key < DEPTH_INFINITY_F32) {
      const inv = ~key >>> KEY_SHIFT;
      const position = buckets2[inv & RADIX_MASK]++;
      indices[position] = i;
      digits[position] = inv >>> RADIX_BITS;
    }
  }
  offset = 0;
  for (let b = 0; b < HIGH_RADIX; ++b) {
    const p = HI_OFFSET + b;
    const count = buckets2[p];
    buckets2[p] = offset;
    offset += count;
  }
  for (let i = 0; i < activeCount; ++i) {
    order[buckets2[HI_OFFSET + digits[i]]++] = indices[i];
  }
  return activeCount;
}
self.onmessage = async (event) => {
  try {
    const message = event.data;
    switch (message.taskType) {
      case "SortSplats" /* SortSplats */: {
        const { count, sorting, ordering } = event.data.payload;
        const activeCount = sorting instanceof Uint32Array ? sort24Splats(count, sorting, ordering) : sortSplats(count, sorting, ordering);
        const payload = {
          status: 0 /* Success */,
          payload: { activeCount, sorting, ordering }
        };
        postMessage(payload, [sorting.buffer, ordering.buffer]);
        return;
      }
      default: {
        const check = message.taskType;
        throw new Error(`Unsupported task type: ${check}.`);
      }
    }
  } catch (e) {
    console.error(e);
    postMessage({ status: 1 /* Fail */, payload: e.toString() });
  }
};
//# sourceMappingURL=splat-sort-worker.js.map
