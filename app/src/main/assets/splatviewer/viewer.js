import {
    Color,
    BackgroundMode,
    PerspectiveCamera,
    SplatLoader,
    SplatUtils,
    ToneMapping,
    Vector3,
    createViewer,
    setViewerConfig,
} from './aholo/index.js';

const params = new URLSearchParams(location.search);
const splatUrl = params.get('splat');
const name = params.get('name') || (splatUrl ? splatUrl.split('/').pop() : 'splat');
const statusEl = document.getElementById('status');
const titleEl = document.getElementById('title');
const panelEl = document.getElementById('panel');
const panelBody = panelEl.querySelector('.panel-body');
titleEl.textContent = name;

const host = window.SplatHost;
function report(message) { try { host && host.log(message); } catch (e) { /* no host in a browser */ } }
window.addEventListener('error', (event) => report('window error: ' + (event.message || event.error)));
report('module loaded; webgl2=' + !!document.createElement('canvas').getContext('webgl2'));

function fail(message) {
    statusEl.textContent = message;
    statusEl.classList.add('error');
    report('failed: ' + message);
}

const FILE_TYPES = {
    sog: SplatLoader.SplatFileType.SOG,
    ply: SplatLoader.SplatFileType.PLY,
    spz: SplatLoader.SplatFileType.SPZ,
    splat: SplatLoader.SplatFileType.SPLAT,
};

async function inflateRaw(raw) {
    const stream = new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    return new Uint8Array(await new Response(stream).arrayBuffer());
}

/** Bounds of a binary little-endian PLY's x/y/z, for framing the camera. */
function boundsOfPly(buffer) {
    const bytes = new Uint8Array(buffer);
    const headBytes = bytes.subarray(0, Math.min(bytes.length, 65536));
    let head = '';
    for (let i = 0; i < headBytes.length; i++) head += String.fromCharCode(headBytes[i]);
    const headerEnd = head.indexOf('end_header');
    if (headerEnd < 0 || !/format\s+binary_little_endian/.test(head.slice(0, headerEnd))) return null;
    const lines = head.slice(0, headerEnd).split('\n').map((line) => line.trim());
    const vertexLine = lines.find((line) => line.startsWith('element vertex '));
    if (!vertexLine) return null;
    const count = parseInt(vertexLine.split(' ').pop(), 10);
    if (!isFinite(count) || count <= 0) return null;
    const start = lines.findIndex((line) => line.startsWith('element vertex '));
    const props = [];
    for (let i = start + 1; i < lines.length; i++) {
        const line = lines[i];
        if (!line) continue;
        if (!line.startsWith('property float ')) break;
        props.push(line.split(' ')[2]);
    }
    const ix = props.indexOf('x');
    const iy = props.indexOf('y');
    const iz = props.indexOf('z');
    if (ix < 0 || iy < 0 || iz < 0) return null;
    const stride = props.length * 4;
    let offset = headerEnd + 'end_header'.length;
    while (offset < bytes.length && (bytes[offset] === 13 || bytes[offset] === 10)) offset++;
    const view = new DataView(buffer);
    const maxSamples = 20000;
    const step = Math.max(1, Math.floor(count / maxSamples));
    const xs = [];
    const ys = [];
    const zs = [];
    for (let i = 0; i < count; i += step) {
        const base = offset + i * stride;
        if (base + stride > buffer.byteLength) break;
        const x = view.getFloat32(base + ix * 4, true);
        const y = view.getFloat32(base + iy * 4, true);
        const z = view.getFloat32(base + iz * 4, true);
        if (!isFinite(x) || !isFinite(y) || !isFinite(z)) continue;
        xs.push(x); ys.push(y); zs.push(z);
    }
    const n = xs.length;
    if (n === 0) return null;
    xs.sort((a, b) => a - b);
    ys.sort((a, b) => a - b);
    zs.sort((a, b) => a - b);
    // Use 5th–95th percentile bounds so sparse background/floater splats do not
    // inflate the framing radius and block close-up zoom.
    const lo = Math.floor(n * 0.05);
    const hi = Math.min(n - 1, Math.ceil(n * 0.95));
    let minX = xs[lo], maxX = xs[hi];
    let minY = ys[lo], maxY = ys[hi];
    let minZ = zs[lo], maxZ = zs[hi];
    if (maxX - minX < 1e-5 && maxY - minY < 1e-5 && maxZ - minZ < 1e-5) {
        minX = xs[0]; maxX = xs[n - 1];
        minY = ys[0]; maxY = ys[n - 1];
        minZ = zs[0]; maxZ = zs[n - 1];
    }
    return {
        cx: (minX + maxX) / 2, cy: (minY + maxY) / 2, cz: (minZ + maxZ) / 2,
        radius: Math.max(1e-4, Math.max(maxX - minX, maxY - minY, maxZ - minZ) / 2),
    };
}

/** Bounds of a standard 32-byte-per-splat .splat buffer (x,y,z float32 at byte 0..11). */
function boundsOfSplat(buffer) {
    const count = Math.floor(buffer.byteLength / 32);
    if (count <= 0) return null;
    const view = new DataView(buffer);
    const maxSamples = 20000;
    const step = Math.max(1, Math.floor(count / maxSamples));
    const xs = [];
    const ys = [];
    const zs = [];
    for (let i = 0; i < count; i += step) {
        const base = i * 32;
        const x = view.getFloat32(base, true);
        const y = view.getFloat32(base + 4, true);
        const z = view.getFloat32(base + 8, true);
        if (!isFinite(x) || !isFinite(y) || !isFinite(z)) continue;
        xs.push(x); ys.push(y); zs.push(z);
    }
    const n = xs.length;
    if (n === 0) return null;
    xs.sort((a, b) => a - b);
    ys.sort((a, b) => a - b);
    zs.sort((a, b) => a - b);
    const lo = Math.floor(n * 0.05);
    const hi = Math.min(n - 1, Math.ceil(n * 0.95));
    let minX = xs[lo], maxX = xs[hi];
    let minY = ys[lo], maxY = ys[hi];
    let minZ = zs[lo], maxZ = zs[hi];
    if (maxX - minX < 1e-5 && maxY - minY < 1e-5 && maxZ - minZ < 1e-5) {
        minX = xs[0]; maxX = xs[n - 1];
        minY = ys[0]; maxY = ys[n - 1];
        minZ = zs[0]; maxZ = zs[n - 1];
    }
    return {
        cx: (minX + maxX) / 2, cy: (minY + maxY) / 2, cz: (minZ + maxZ) / 2,
        radius: Math.max(1e-4, Math.max(maxX - minX, maxY - minY, maxZ - minZ) / 2),
    };
}

/** Bounds from a SOG's meta.json, whose means min/max is the cloud's extent. */
async function boundsOfSog(buffer) {
    const bytes = new Uint8Array(buffer);
    const view = new DataView(buffer);
    if (bytes.length < 22) return null;
    let eocd = -1;
    for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 70000); i--) {
        if (view.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) return null;
    const count = view.getUint16(eocd + 10, true);
    let offset = view.getUint32(eocd + 16, true);
    for (let i = 0; i < count; i++) {
        if (offset + 46 > bytes.length || view.getUint32(offset, true) !== 0x02014b50) return null;
        const method = view.getUint16(offset + 10, true);
        const size = view.getUint32(offset + 20, true);
        const nameLength = view.getUint16(offset + 28, true);
        const extraLength = view.getUint16(offset + 30, true);
        const commentLength = view.getUint16(offset + 32, true);
        const localOffset = view.getUint32(offset + 42, true);
        const entryName = new TextDecoder().decode(bytes.subarray(offset + 46, offset + 46 + nameLength));
        if (entryName === 'meta.json' && (method === 0 || method === 8) && localOffset + 30 < bytes.length) {
            const localNameLength = view.getUint16(localOffset + 26, true);
            const localExtraLength = view.getUint16(localOffset + 28, true);
            const start = localOffset + 30 + localNameLength + localExtraLength;
            try {
                let raw = bytes.subarray(start, start + size);
                if (method === 8) raw = await inflateRaw(raw);
                const meta = JSON.parse(new TextDecoder().decode(raw));
                const mins = meta && meta.means && meta.means.mins;
                const maxs = meta && meta.means && meta.means.maxs;
                if (!mins || !maxs) return null;
                return {
                    cx: (mins[0] + maxs[0]) / 2,
                    cy: (mins[1] + maxs[1]) / 2,
                    cz: (mins[2] + maxs[2]) / 2,
                    radius: Math.max(1e-4, Math.max(maxs[0] - mins[0], maxs[1] - mins[1], maxs[2] - mins[2]) / 2),
                };
            } catch (e) {
                return null;
            }
        }
        offset += 46 + nameLength + extraLength + commentLength;
    }
    return null;
}

// ---------------------------------------------------------------- settings ---

const STORAGE_KEY = 'trio.splat.settings.v2';

const RENDER_DEFAULTS = {
    precalculateEnabled: true,
    repackEnabled: false,
    detailCullingAlphaEnabled: false,
    preBlurAmount: 0.3,
    blurAmount: 0,
    focalAdjustment: 2,
    detailCullingThreshold: 1,
    radial: false,
    sortHighPrecisionEnabled: false,
    compositeEnabled: false,
    compositeHighPrecisionEnabled: true,
    toneMapping: 'Neutral',
    exposure: 1,
};

const CONTROL_DEFAULTS = {
    orbitSensitivity: 1,
    zoomSensitivity: 1,
    panSensitivity: 1,
    rollSensitivity: 1,
    damping: 0.2,
    autoRotate: false,
    autoRotateSpeed: 0.25,
};

const renderSettings = Object.assign({}, RENDER_DEFAULTS);
const controlSettings = Object.assign({}, CONTROL_DEFAULTS);

const TONE_MAPPINGS = {
    Linear: ToneMapping.Linear,
    Reinhard: ToneMapping.Reinhard,
    ACES: ToneMapping.ACES,
    ACESFilmic: ToneMapping.ACESFilmic,
    Neutral: ToneMapping.Neutral,
};

function loadSettings() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (!raw) return;
        const saved = JSON.parse(raw);
        Object.assign(renderSettings, saved.render || {});
        Object.assign(controlSettings, saved.control || {});
    } catch (e) {
        report('settings load failed: ' + e);
    }
}

function saveSettings() {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify({ render: renderSettings, control: controlSettings }));
    } catch (e) {
        report('settings save failed: ' + e);
    }
}

function applyRenderSettings(viewer) {
    setViewerConfig(viewer, {
        pipeline: {
            Splatting: {
                enabled: true,
                pack: {
                    precalculateEnabled: renderSettings.precalculateEnabled,
                    repackEnabled: renderSettings.repackEnabled,
                },
                sort: {
                    radial: renderSettings.radial,
                    highPrecisionEnabled: renderSettings.sortHighPrecisionEnabled,
                },
                raster: {
                    detailCullingAlphaEnabled: renderSettings.detailCullingAlphaEnabled,
                    preBlurAmount: renderSettings.preBlurAmount,
                    blurAmount: renderSettings.blurAmount,
                    focalAdjustment: renderSettings.focalAdjustment,
                    detailCullingThreshold: renderSettings.detailCullingThreshold,
                },
                composite: {
                    enabled: renderSettings.compositeEnabled,
                    highPrecisionEnabled: renderSettings.compositeHighPrecisionEnabled,
                    toneMapping: TONE_MAPPINGS[renderSettings.toneMapping] || ToneMapping.Neutral,
                    exposure: renderSettings.exposure,
                },
            },
        },
    });
}

// ---------------------------------------------------------------- controls ---

/**
 * Unlocked 360° quaternion orbit controls for a splat scene.
 *
 * One finger orbits freely in any direction (no pitch lock or pole flip), two fingers
 * pinch-zoom, pan, and twist to roll the camera, a wheel zooms on desktop, and double-tap
 * returns to the framing the model was loaded with. All axes ease toward their target with a
 * damping factor.
 */
class OrbitRig {
    constructor(camera, container, initial) {
        this.camera = camera;
        this.container = container;
        this.initial = initial;
        this.initialQ = orientationFromYawPitch(initial.yaw, initial.pitch, camera.up);
        this.target = initial.target.clone();
        this.q = this.initialQ.slice();
        this.distance = initial.distance;
        this.wantedTarget = initial.target.clone();
        this.wantedQ = this.initialQ.slice();
        this.wantedDistance = initial.distance;
        this.radius = initial.radius;
        this.pointers = new Map();
        this.gesture = null;
        this.lastTap = { time: 0, x: 0, y: 0 };
        this.bind();
    }

    reset() {
        this.wantedTarget.copy(this.initial.target);
        this.wantedQ = this.initialQ.slice();
        this.wantedDistance = this.initial.distance;
    }

    /** The camera basis for pan math: right and up in world space. */
    basis() {
        const r = qRotateVec(this.q, 1, 0, 0);
        const u = qRotateVec(this.q, 0, 1, 0);
        return {
            right: new Vector3(r[0], r[1], r[2]),
            up: new Vector3(u[0], u[1], u[2]),
        };
    }

    /** True when the eased camera has reached the values it was asked for. */
    settled() {
        const targetDelta = this.wantedTarget.clone().sub(this.target);
        return !controlSettings.autoRotate &&
            qAngleBetween(this.wantedQ, this.q) < 1e-4 &&
            Math.abs(this.wantedDistance - this.distance) < 1e-4 &&
            targetDelta.x * targetDelta.x + targetDelta.y * targetDelta.y + targetDelta.z * targetDelta.z < 1e-8;
    }

    update(dt) {
        const damping = Math.min(0.95, Math.max(0.02, controlSettings.damping));
        const alpha = 1 - Math.pow(1 - damping, dt * 60);
        if (controlSettings.autoRotate && this.pointers.size === 0) {
            const spin = qFromAxisAngle(0, 1, 0, -controlSettings.autoRotateSpeed * dt);
            this.wantedQ = qNormalize(qMul(this.wantedQ, spin));
        }
        this.q = qSlerp(this.q, this.wantedQ, alpha);
        this.distance += (this.wantedDistance - this.distance) * alpha;
        this.target.addScaledVector(this.wantedTarget.clone().sub(this.target), alpha);

        const up = qRotateVec(this.q, 0, 1, 0);
        const back = qRotateVec(this.q, 0, 0, 1);
        this.camera.position.set(
            this.target.x + back[0] * this.distance,
            this.target.y + back[1] * this.distance,
            this.target.z + back[2] * this.distance,
        );
        this.camera.up.set(up[0], up[1], up[2]);
        this.camera.lookAt(this.target);
    }

    orbit(dx, dy) {
        const speed = 0.005 * controlSettings.orbitSensitivity;
        // Free trackball orbit around the camera's current local up/right axes:
        // dragging right turns the model right, dragging down tilts over the top
        // with no pitch lock or pole flip.
        const qYaw = qFromAxisAngle(0, 1, 0, -dx * speed);
        const qPitch = qFromAxisAngle(1, 0, 0, -dy * speed);
        this.wantedQ = qNormalize(qMul(this.wantedQ, qMul(qYaw, qPitch)));
    }

    roll(dAngle) {
        if (!dAngle || controlSettings.rollSensitivity <= 0) return;
        const qRoll = qFromAxisAngle(0, 0, 1, dAngle * controlSettings.rollSensitivity);
        this.wantedQ = qNormalize(qMul(this.wantedQ, qRoll));
    }

    pan(dx, dy) {
        const { right, up } = this.basis();
        const fov = (this.camera.fov || 60) * Math.PI / 180;
        const perPixel = 2 * this.distance * Math.tan(fov / 2) / Math.max(1, this.container.clientHeight);
        const scale = perPixel * controlSettings.panSensitivity;
        this.wantedTarget.addScaledVector(right, -dx * scale);
        this.wantedTarget.addScaledVector(up, dy * scale);
    }

    zoomBy(factor) {
        const minDist = Math.max(0.005, this.radius * 0.005);
        const maxDist = Math.max(100, this.radius * 80);
        this.wantedDistance = clamp(this.wantedDistance * factor, minDist, maxDist);
    }

    bind() {
        const el = this.container;
        el.addEventListener('pointerdown', (event) => {
            el.setPointerCapture(event.pointerId);
            this.pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
            if (this.pointers.size === 2) {
                const [a, b] = [...this.pointers.values()];
                this.gesture = {
                    kind: 'pinch',
                    distance: Math.hypot(a.x - b.x, a.y - b.y),
                    angle: Math.atan2(b.y - a.y, b.x - a.x),
                    midpoint: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
                };
            }
        });
        el.addEventListener('pointermove', (event) => {
            const previous = this.pointers.get(event.pointerId);
            if (!previous) return;
            const current = { x: event.clientX, y: event.clientY };
            if (this.pointers.size === 1) {
                this.orbit(current.x - previous.x, current.y - previous.y);
                this.pointers.set(event.pointerId, current);
                return;
            }
            this.pointers.set(event.pointerId, current);
            if (this.pointers.size !== 2 || !this.gesture) return;
            const [a, b] = [...this.pointers.values()];
            const distance = Math.hypot(a.x - b.x, a.y - b.y);
            const angle = Math.atan2(b.y - a.y, b.x - a.x);
            const midpoint = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
            if (this.gesture.distance > 0 && distance > 0) {
                const ratio = this.gesture.distance / distance;
                this.zoomBy(Math.pow(ratio, controlSettings.zoomSensitivity));
            }
            this.roll(angleDelta(angle, this.gesture.angle));
            this.pan(midpoint.x - this.gesture.midpoint.x, midpoint.y - this.gesture.midpoint.y);
            this.gesture.distance = distance;
            this.gesture.angle = angle;
            this.gesture.midpoint = midpoint;
        });
        const release = (event) => {
            this.pointers.delete(event.pointerId);
            if (this.pointers.size < 2) this.gesture = null;
            if (this.pointers.size === 0) {
                const now = performance.now();
                const dx = event.clientX - this.lastTap.x;
                const dy = event.clientY - this.lastTap.y;
                if (now - this.lastTap.time < 320 && Math.hypot(dx, dy) < 48) {
                    this.reset();
                    this.lastTap.time = 0;
                } else {
                    this.lastTap = { time: now, x: event.clientX, y: event.clientY };
                }
            }
        };
        el.addEventListener('pointerup', release);
        el.addEventListener('pointercancel', release);
        el.addEventListener('wheel', (event) => {
            event.preventDefault();
            this.zoomBy(Math.exp(event.deltaY * 0.0012 * controlSettings.zoomSensitivity));
        }, { passive: false });
    }
}

function clamp(value, low, high) { return Math.max(low, Math.min(high, value)); }
function angleDelta(wanted, current) {
    let delta = (wanted - current) % (Math.PI * 2);
    if (delta > Math.PI) delta -= Math.PI * 2;
    if (delta < -Math.PI) delta += Math.PI * 2;
    return delta;
}

function qNormalize(q) {
    const len = Math.hypot(q[0], q[1], q[2], q[3]);
    if (len < 1e-12) return [0, 0, 0, 1];
    return [q[0] / len, q[1] / len, q[2] / len, q[3] / len];
}

function qFromAxisAngle(ax, ay, az, angle) {
    const half = angle * 0.5;
    const s = Math.sin(half);
    return [ax * s, ay * s, az * s, Math.cos(half)];
}

function qMul(a, b) {
    const [ax, ay, az, aw] = a;
    const [bx, by, bz, bw] = b;
    return [
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz,
    ];
}

function qRotateVec(q, vx, vy, vz) {
    const [qx, qy, qz, qw] = q;
    const tx = 2 * (qy * vz - qz * vy);
    const ty = 2 * (qz * vx - qx * vz);
    const tz = 2 * (qx * vy - qy * vx);
    return [
        vx + qw * tx + (qy * tz - qz * ty),
        vy + qw * ty + (qz * tx - qx * tz),
        vz + qw * tz + (qx * ty - qy * tx),
    ];
}

function qAngleBetween(a, b) {
    const dot = clamp(Math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]), 0, 1);
    return 2 * Math.acos(dot);
}

function qSlerp(a, b, t) {
    let bx = b[0], by = b[1], bz = b[2], bw = b[3];
    let dot = a[0] * bx + a[1] * by + a[2] * bz + a[3] * bw;
    if (dot < 0) {
        dot = -dot;
        bx = -bx; by = -by; bz = -bz; bw = -bw;
    }
    if (dot > 0.9995) {
        return qNormalize([
            a[0] + (bx - a[0]) * t,
            a[1] + (by - a[1]) * t,
            a[2] + (bz - a[2]) * t,
            a[3] + (bw - a[3]) * t,
        ]);
    }
    const theta = Math.acos(clamp(dot, -1, 1));
    const sinTheta = Math.sin(theta);
    const w0 = Math.sin((1 - t) * theta) / sinTheta;
    const w1 = Math.sin(t * theta) / sinTheta;
    return [
        a[0] * w0 + bx * w1,
        a[1] * w0 + by * w1,
        a[2] * w0 + bz * w1,
        a[3] * w0 + bw * w1,
    ];
}

function qFromBasis(r, u, b) {
    const m00 = r.x, m01 = u.x, m02 = b.x;
    const m10 = r.y, m11 = u.y, m12 = b.y;
    const m20 = r.z, m21 = u.z, m22 = b.z;
    const trace = m00 + m11 + m22;
    let x, y, z, w;
    if (trace > 0) {
        const s = 2 * Math.sqrt(trace + 1);
        w = 0.25 * s;
        x = (m21 - m12) / s;
        y = (m02 - m20) / s;
        z = (m10 - m01) / s;
    } else if (m00 > m11 && m00 > m22) {
        const s = 2 * Math.sqrt(1 + m00 - m11 - m22);
        w = (m21 - m12) / s;
        x = 0.25 * s;
        y = (m01 + m10) / s;
        z = (m02 + m20) / s;
    } else if (m11 > m22) {
        const s = 2 * Math.sqrt(1 + m11 - m00 - m22);
        w = (m02 - m20) / s;
        x = (m01 + m10) / s;
        y = 0.25 * s;
        z = (m12 + m21) / s;
    } else {
        const s = 2 * Math.sqrt(1 + m22 - m00 - m11);
        w = (m10 - m01) / s;
        x = (m02 + m20) / s;
        y = (m12 + m21) / s;
        z = 0.25 * s;
    }
    return qNormalize([x, y, z, w]);
}

function orientationFromYawPitch(yaw, pitch, worldUp) {
    const cp = Math.cos(pitch);
    const back = new Vector3(Math.sin(yaw) * cp, Math.sin(pitch), Math.cos(yaw) * cp).normalize();
    const forward = back.clone().multiplyScalar(-1);
    const right = new Vector3().crossVectors(forward, worldUp).normalize();
    const up = new Vector3().crossVectors(right, forward).normalize();
    return qFromBasis(right, up, back);
}

// ------------------------------------------------------------ settings UI ---

function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

function group(parent, title) {
    const details = element('details', 'group');
    details.open = true;
    const summary = element('summary', null, title);
    details.appendChild(summary);
    parent.appendChild(details);
    return details;
}

function row(parent, label) {
    const line = element('div', 'row');
    line.appendChild(element('span', null, label));
    parent.appendChild(line);
    return line;
}

function checkbox(parent, label, object, key, onChange) {
    const line = row(parent, label);
    const input = document.createElement('input');
    input.type = 'checkbox';
    input.checked = !!object[key];
    input.addEventListener('change', () => {
        object[key] = input.checked;
        saveSettings();
        onChange();
    });
    line.appendChild(input);
}

function slider(parent, label, object, key, min, max, step, onChange) {
    const line = row(parent, label);
    const input = document.createElement('input');
    input.type = 'range';
    input.min = String(min);
    input.max = String(max);
    input.step = String(step);
    input.value = String(object[key]);
    const value = element('span', 'value', Number(object[key]).toFixed(step < 0.1 ? 2 : 1));
    input.addEventListener('input', () => {
        object[key] = Number(input.value);
        value.textContent = Number(input.value).toFixed(step < 0.1 ? 2 : 1);
        saveSettings();
        onChange();
    });
    line.appendChild(input);
    line.appendChild(value);
}

function picker(parent, label, object, key, options, onChange) {
    const line = row(parent, label);
    const select = document.createElement('select');
    for (const [value, text] of options) {
        const option = document.createElement('option');
        option.value = value;
        option.textContent = text;
        select.appendChild(option);
    }
    select.value = object[key];
    select.addEventListener('change', () => {
        object[key] = select.value;
        saveSettings();
        onChange();
    });
    line.appendChild(select);
}

function buildSettingsPanel(viewer, rig) {
    panelBody.innerHTML = '';
    const rerender = () => { applyRenderSettings(viewer); requestRedraw(); };

    const splatting = group(panelBody, 'Splatting');
    checkbox(splatting, 'Precalculate SH', renderSettings, 'precalculateEnabled', rerender);
    checkbox(splatting, 'Repack textures', renderSettings, 'repackEnabled', rerender);
    checkbox(splatting, 'Cull by alpha', renderSettings, 'detailCullingAlphaEnabled', rerender);
    slider(splatting, 'Pre blur', renderSettings, 'preBlurAmount', 0, 1, 0.1, rerender);
    slider(splatting, 'Blur', renderSettings, 'blurAmount', 0, 1, 0.1, rerender);
    slider(splatting, 'Focal adjustment', renderSettings, 'focalAdjustment', 0.5, 2, 0.1, rerender);
    slider(splatting, 'Detail culling', renderSettings, 'detailCullingThreshold', 0, 4, 1, rerender);

    const sorting = group(panelBody, 'Sorting');
    checkbox(sorting, 'Radial sort', renderSettings, 'radial', rerender);
    checkbox(sorting, 'High precision', renderSettings, 'sortHighPrecisionEnabled', rerender);

    const composite = group(panelBody, 'Composite');
    checkbox(composite, 'Enabled', renderSettings, 'compositeEnabled', rerender);
    checkbox(composite, 'High precision', renderSettings, 'compositeHighPrecisionEnabled', rerender);
    picker(composite, 'Tone curve', renderSettings, 'toneMapping', [
        ['Linear', 'Linear'], ['Reinhard', 'Reinhard'], ['ACES', 'ACES'],
        ['ACESFilmic', 'ACES Film'], ['Neutral', 'Neutral'],
    ], rerender);
    slider(composite, 'Exposure', renderSettings, 'exposure', 0.1, 2, 0.1, rerender);

    const controls = group(panelBody, 'Controls');
    slider(controls, 'Orbit speed', controlSettings, 'orbitSensitivity', 0.2, 2.5, 0.1, () => {});
    slider(controls, 'Zoom speed', controlSettings, 'zoomSensitivity', 0.2, 2.5, 0.1, () => {});
    slider(controls, 'Pan speed', controlSettings, 'panSensitivity', 0.2, 2.5, 0.1, () => {});
    slider(controls, 'Twist speed', controlSettings, 'rollSensitivity', 0, 2.5, 0.1, () => {});
    slider(controls, 'Damping', controlSettings, 'damping', 0.02, 0.6, 0.02, () => {});
    checkbox(controls, 'Auto-rotate', controlSettings, 'autoRotate', () => {});
    slider(controls, 'Rotate speed', controlSettings, 'autoRotateSpeed', 0.05, 1, 0.05, () => {});

    const resetLine = element('div', 'row');
    const resetButton = element('button', 'hud-button', 'Reset all settings');
    resetButton.addEventListener('click', () => {
        Object.assign(renderSettings, RENDER_DEFAULTS);
        Object.assign(controlSettings, CONTROL_DEFAULTS);
        saveSettings();
        buildSettingsPanel(viewer, rig);
        applyRenderSettings(viewer);
        rig.reset();
    });
    resetLine.appendChild(resetButton);
    panelBody.appendChild(resetLine);
}

// ------------------------------------------------------------------- boot ---

let requestRedraw = () => {};
let needsRender = true;
let warmupFrames = 0;

async function main() {
    if (!splatUrl) { fail('No splat file given.'); return; }
    const ext = (splatUrl.split('.').pop() || '').toLowerCase();
    const type = FILE_TYPES[ext];
    if (type === undefined) { fail('Unsupported splat type: .' + ext); return; }

    loadSettings();
    statusEl.textContent = 'Loading ' + name + '…';
    const response = await fetch(splatUrl);
    if (!response.ok) { fail('Could not read the splat (' + response.status + ').'); return; }
    const buffer = await response.arrayBuffer();
    const bounds = ext === 'ply'
        ? boundsOfPly(buffer)
        : ext === 'sog'
            ? await boundsOfSog(buffer)
            : ext === 'splat'
                ? boundsOfSplat(buffer)
                : null;
    report('bounds of ' + ext + ': ' + JSON.stringify(bounds));

    const data = await SplatLoader.parseSplatData(type, new Uint8Array(buffer));
    const splat = await SplatUtils.createSplat(data);
    report('splat created');

    const container = document.getElementById('view');
    const viewWidth = Math.max(container.clientWidth, window.innerWidth);
    const viewHeight = Math.max(container.clientHeight, window.innerHeight);
    container.style.width = viewWidth + 'px';
    container.style.height = viewHeight + 'px';

    const viewer = createViewer('splat-viewer', container, {});
    const canvas = container.querySelector('canvas');
    if (canvas) {
        canvas.addEventListener('webglcontextlost', (event) => {
            event.preventDefault();
            report('webgl context lost');
        }, false);
    }
    const camera = new PerspectiveCamera(60, viewWidth / Math.max(1, viewHeight), 0.001, 100000);
    camera.up.set(0, -1, 0);

    const framed = bounds && isFinite(bounds.radius) && bounds.radius > 0;
    const center = framed ? new Vector3(bounds.cx, bounds.cy, bounds.cz) : new Vector3(0, 0, 0);
    const radius = framed ? bounds.radius : 1;

    viewer.getScene().add(splat);
    setViewerConfig(viewer, {
        pipeline: {
            Background: {
                background: { active: BackgroundMode.BasicBackground, basic: { color: new Color(0, 0, 0) } },
                ground: { enabled: false },
            },
            TAA: { enabled: false },
        },
    });
    applyRenderSettings(viewer);

    const rig = new OrbitRig(camera, container, {
        target: center,
        radius,
        yaw: 0.7,
        pitch: 0.25,
        distance: radius * 2.4,
    });
    // The rig moves this camera; tell the viewer which one it is.
    viewer.setCamera(camera);

    // Render on demand: a still scene costs no GPU time, which keeps a phone viewer cool
    // and avoids the GPU dropping a long-lived WebGL context under sustained load.
    requestRedraw = () => { needsRender = true; warmupFrames = 45; };
    let lastFrame = 0;
    function frame() {
        try {
            const now = performance.now();
            const dt = lastFrame ? Math.min(0.05, (now - lastFrame) / 1000) : 0.016;
            lastFrame = now;
            rig.update(dt);
            const settled = rig.settled();
            // A few extra frames after a change let the async splat sort and the GPU
            // present the frame that was just prepared; without them a single render can
            // be the only frame drawn and the canvas stays empty.
            if (!settled) warmupFrames = Math.max(warmupFrames, 40);
            if (needsRender || warmupFrames > 0) {
                viewer.render();
                needsRender = false;
                if (warmupFrames > 0) warmupFrames--;
            }
            requestAnimationFrame(frame);
        } catch (e) {
            fail('Render failed: ' + (e && e.message ? e.message : e));
        }
    }
    requestAnimationFrame(frame);

    document.getElementById('fit').addEventListener('click', () => rig.reset());
    document.getElementById('settings').addEventListener('click', () => panelEl.classList.toggle('open'));
    document.getElementById('panel-close').addEventListener('click', () => panelEl.classList.remove('open'));
    buildSettingsPanel(viewer, rig);

    statusEl.textContent = '';
    requestRedraw();
    report('first frame scheduled');
}

main().catch((e) => fail('Could not open the model: ' + (e && e.message ? e.message : e)));
