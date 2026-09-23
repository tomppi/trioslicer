// EnderSlicerCura UI/UX redesign — mockup generator.
// Regenerates the HTML screens into ./html; then screenshot with Edge
// (see scripts/screenshot-mockups.ps1). Edit + rerun: node generate.mjs
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT = join(dirname(fileURLToPath(import.meta.url)), 'html');
mkdirSync(OUT, { recursive: true });

// ---------------- icon set (24x24, stroke) ----------------
const I = {
  cube: '<path d="M12 2.5l8 4.5v9.5L12 21.5l-8-4.5V7z"/><path d="M12 2.5V12M4 7l8 5 8-5M12 12v9.5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  dots: '<circle cx="5" cy="12" r="1.55"/><circle cx="12" cy="12" r="1.55"/><circle cx="19" cy="12" r="1.55"/>',
  back: '<path d="M15 5l-7 7 7 7"/>',
  gear: '<circle cx="12" cy="12" r="3.1"/><path d="M12 2.8v2.6M12 18.6v2.6M2.8 12h2.6M18.6 12h2.6M5.5 5.5l1.8 1.8M16.7 16.7l1.8 1.8M18.5 5.5l-1.8 1.8M7.3 16.7l-1.8 1.8"/>',
  printer: '<path d="M7 8V3.5h10V8M7 17H4.5A1.5 1.5 0 0 1 3 15.5v-5A2.5 2.5 0 0 1 5.5 8h13A2.5 2.5 0 0 1 21 10.5v5a1.5 1.5 0 0 1-1.5 1.5H17"/><rect x="7" y="14.5" width="10" height="7" rx="1.2"/>',
  wrench: '<path d="M14.6 6.3a4.2 4.2 0 0 0-5.3 5.4L4 17l3 3 5.3-5.3a4.2 4.2 0 0 0 5.4-5.3l-2.9 2.9-2.4-.6-.6-2.4z"/>',
  layers: '<path d="M12 3.2l9 5-9 5-9-5z"/><path d="M3.4 13.4L12 18l8.6-4.6M3.4 17l8.6 4.6L20.6 17"/>',
  wave: '<path d="M2.5 12c2.4-5.6 5.4-5.6 7.8 0s5.4 5.6 7.8 0c1.9-4.4 3.4-5 3.4-5"/>',
  search: '<circle cx="11" cy="11" r="6.2"/><path d="M20 20l-4.2-4.2"/>',
  refresh: '<path d="M20 12a8 8 0 1 1-2.3-5.6M20 3.5V8h-4.5"/>',
  power: '<path d="M12 3.5V11M6.6 6.2a8 8 0 1 0 10.8 0"/>',
  cam: '<rect x="3" y="7" width="18" height="13" rx="2.4"/><circle cx="12" cy="13.3" r="3.6"/><path d="M8.5 7l1.4-2.4h4.2L15.5 7"/>',
  pause: '<path d="M7 5.5v13M17 5.5v13"/>',
  stop: '<rect x="6.5" y="6.5" width="11" height="11" rx="2"/>',
  check: '<path d="M4.5 12.6l4.8 4.8L19.5 6.8"/>',
  chev: '<path d="M9.5 5.5l6.5 6.5-6.5 6.5"/>',
  bolt: '<path d="M13 2.5L4.5 14H10l-1 7.5L17.5 10H12z" fill="currentColor" stroke="none"/>',
  star: '<path d="M12 3l2.7 5.7 6.3.8-4.6 4.3 1.2 6.2-5.6-3.1-5.6 3.1 1.2-6.2L3 9.5l6.3-.8z"/>',
  chart: '<path d="M4 20V5M4 20h16M8 15l3.5-4 3 2.5L18 9"/>',
  filter: '<path d="M3.5 5h17l-7 8.2V19l-3 1.8v-7.6z"/>',
  swap: '<path d="M7.5 3.5L4 7l3.5 3.5M4 7h15M16.5 20.5L20 17l-3.5-3.5M20 17H5"/>',
  info: '<circle cx="12" cy="12" r="8.4"/><path d="M12 11v5.4"/><circle cx="12" cy="8" r="0.9" fill="currentColor"/>',
  shield: '<path d="M12 3l7 2.8v5.4c0 4.8-3.4 8.1-7 9.8-3.6-1.7-7-5-7-9.8V5.8z"/><path d="M9 12l2.2 2.2 4-4.4"/>',
  clock: '<circle cx="12" cy="12" r="8.4"/><path d="M12 7.4V12l3.4 2"/>',
  alert: '<path d="M12 3.5l9.5 16.5H2.5z"/><path d="M12 9.5v4.5"/><circle cx="12" cy="16.8" r="0.9" fill="currentColor"/>',
  code: '<path d="M5 8.5h14M5 15.5h14M8 8.5l2 3.5-2 3.5M16 8.5l-2 3.5 2 3.5"/>',
  signal: '<path d="M3 20h2.6M8.2 20h2.6v-4H8.2zM13.4 20h2.6v-8h-2.6zM18.6 20h2.6V8h-2.6z" fill="currentColor" stroke="none"/>',
  wifi: '<path d="M2.5 9.2a14.5 14.5 0 0 1 19 0M6 12.7a9.6 9.6 0 0 1 12 0M9.5 16.2a4.8 4.8 0 0 1 5 0"/><circle cx="12" cy="19" r="1.1" fill="currentColor" stroke="none"/>',
  batt: '<rect x="2" y="8.4" width="17" height="7.2" rx="1.8"/><path d="M21.5 11v2M5.5 10.6v2.8M9.5 10.6v2.8M13.5 10.6v2.8"/>',
  sliders: '<path d="M4 8h16M4 16h16"/><circle cx="9.5" cy="8" r="2.4"/><circle cx="15" cy="16" r="2.4"/>',
  file: '<path d="M6.5 2.5h8L18 6v15.5H6.5z"/><path d="M14 2.5V6h3.5M9.5 12h5M9.5 15.5h5"/>',
  expand: '<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>',
  play: '<path d="M8.5 5.5l10 6.5-10 6.5z"/>'
};
const icon = (name, size = 20, color = 'currentColor', sw = 1.7) =>
  '<svg width="' + size + '" height="' + size + '" viewBox="0 0 24 24" fill="none" stroke="' + color + '" stroke-width="' + sw + '" stroke-linecap="round" stroke-linejoin="round">' + I[name] + '</svg>';

// ---------------- shared chrome ----------------
const statusbar = (time = '9:41') =>
  '<div class="statusbar"><span>' + time + '</span><div class="sb-right">' + icon('signal', 17) + icon('wifi', 17) + icon('batt', 20) + '</div></div>';

const brand = (size = 34) =>
  '<div class="brand" style="width:' + size + 'px;height:' + size + 'px">' + icon('cube', size * .58, '#1A1206', 1.9) + '</div>';

const navItem = (iconName, label, on = false) =>
  '<div class="nav-item' + (on ? ' on' : '') + '">' + icon(iconName, 21) + '<span>' + label + '</span><div class="nav-pip"></div></div>';

const nav = (active) =>
  '<div class="nav">' +
  navItem('printer', 'Plate', active === 'plate') +
  navItem('sliders', 'Settings', active === 'settings') +
  navItem('power', 'Print', active === 'print') +
  navItem('dots', 'More', active === 'more') +
  '</div>';

const benchy = (w = 168, stripes = false) => {
  const stripe = stripes
    ? '<clipPath id="hullclip"><path d="M14 118 Q10 96 40 84 L130 84 Q160 96 156 118 Q140 128 85 128 Q30 128 14 118 Z"/></clipPath>' +
      '<g clip-path="url(#hullclip)"><rect x="0" y="84" width="170" height="6" fill="rgba(255,232,205,.35)"/><rect x="0" y="96" width="170" height="6" fill="rgba(255,232,205,.35)"/><rect x="0" y="108" width="170" height="6" fill="rgba(255,232,205,.35)"/><rect x="0" y="120" width="170" height="6" fill="rgba(255,232,205,.35)"/></g>'
    : '';
  return '<svg width="' + w + '" height="' + Math.round(w * .88) + '" viewBox="0 0 170 150">' +
    '<defs><linearGradient id="hull" x1="0" y1="0" x2="0" y2="1">' +
    '<stop offset="0" stop-color="#FFC77D"/><stop offset="1" stop-color="#F5924A"/></linearGradient></defs>' +
    '<ellipse cx="85" cy="139" rx="72" ry="8" fill="rgba(0,0,0,.5)"/>' +
    '<path d="M14 118 Q10 96 40 84 L130 84 Q160 96 156 118 Q140 128 85 128 Q30 128 14 118 Z" fill="url(#hull)"/>' +
    stripe +
    '<path d="M40 84 L52 62 L118 62 L130 84 Z" fill="#E8883F"/>' +
    '<path d="M46 72 L106 72 L106 84 L46 84 Z" fill="#F5A358"/>' +
    '<rect x="56" y="42" width="44" height="22" rx="4" fill="#FFB454"/>' +
    '<rect x="62" y="46" width="13" height="11" rx="2" fill="#23303C"/><rect x="81" y="46" width="13" height="11" rx="2" fill="#23303C"/>' +
    '<path d="M54 42 L78 32 L102 42 Z" fill="#F5A358"/>' +
    '<rect x="93" y="19" width="12" height="25" rx="3" fill="#FFB454"/>' +
    '<rect x="91" y="15" width="16" height="6" rx="3" fill="#E8883F"/>' +
    '</svg>';
};

const scene = (opts = {}) =>
  '<div class="scene">' +
  '<div class="grid"></div><div class="plate"></div>' +
  '<div class="model">' + benchy(150, !!opts.stripes) + '</div>' +
  '<div class="vignette"></div>' +
  (opts.hud || '') +
  '</div>';

const seg = (active) =>
  '<div class="segmented">' +
  '<div class="seg' + (active === 'model' ? ' on' : '') + '">' + icon('cube', 15) + 'Model</div>' +
  '<div class="seg' + (active === 'layers' ? ' on' : '') + '">' + icon('layers', 15) + 'Layers</div>' +
  '<div class="seg' + (active === 'path' ? ' on' : '') + '">' + icon('wave', 15) + 'Path</div>' +
  '</div>';

const page = (inner, cls = '') =>
  '<!doctype html><html><head><meta charset="utf-8"><title>mockup</title><link rel="stylesheet" href="../style.css"></head><body>' +
  '<div class="page' + (cls ? ' ' + cls : '') + '">' + inner + '</div></body></html>';

const write = (name, html) => writeFileSync(join(OUT, name), html);

// ============ 01 — Plate (model) ============
write('01-plate.html', page(
  statusbar() +
  '<div class="topbar"><div class="tb-left">' + brand() +
  '<div class="tb-title"><span class="t1">Plate</span><span class="t2">benchy_lowpoly.stl · 12,482 tri</span></div></div>' +
  '<div class="tb-right"><span class="btn outline small">' + icon('plus', 15) + 'Import</span>' +
  '<span class="iconbtn">' + icon('dots', 20) + '</span></div></div>' +
  scene({ hud:
    '<div class="hud tl"><div class="hudcard">' +
    '<div class="hc-title">' + icon('cube', 15, 'var(--accent)') + 'bench_lowpoly.stl</div>' +
    '<div class="hc-line">58.6 × 25.1 × 48.2 mm · 12,482 tri</div>' +
    '<div class="hc-line flex"><span>Centered · Z 0.00</span><span class="accent">OK</span></div>' +
    '</div></div>' +
    '<div class="hud tr">' + seg('model') + '</div>'
  }) +
  '<div class="statusstrip"><span class="dot ok"></span><span>Ready</span><span style="flex:1"></span><span>Ender 3 V2 · 220×220×250 · 0.40 nozzle</span></div>' +
  '<div class="actionbar">' +
  '<span class="btn ghost">' + icon('wrench', 18) + 'Tools</span>' +
  '<span class="btn filled wide">' + icon('bolt', 17) + 'Slice</span>' +
  '<span class="btn outline wide">Export</span>' +
  '</div>' + nav('plate')
));

// ============ 02 — Plate (layers preview) ============
write('02-plate-layers.html', page(
  statusbar() +
  '<div class="topbar"><div class="tb-left">' + brand() +
  '<div class="tb-title"><span class="t1">Plate</span><span class="t2">bench_lowpoly.stl · sliced 2:34</span></div></div>' +
  '<div class="tb-right"><span class="btn outline small">' + icon('plus', 15) + 'Import</span>' +
  '<span class="iconbtn">' + icon('dots', 20) + '</span></div></div>' +
  scene({ stripes: true, hud:
    '<div class="hud tl"><div class="hudcard">' +
    '<div class="hc-title">' + icon('cube', 15, 'var(--accent)') + 'bench_lowpoly.stl</div>' +
    '<div class="hc-line flex"><span>Layer 42 of 118</span><span class="accent">Z 3.42 mm</span></div>' +
    '</div></div>' +
    '<div class="hud tr">' + seg('layers') + '</div>'
  }) +
  '<div style="flex:0 0 auto;background:var(--surface);border-top:1px solid var(--border);padding:8px 14px">' +
  '<div style="display:flex;align-items:center;gap:12px">' +
  '<div class="legend">' +
  '<span class="lg"><span class="dot" style="background:var(--accent)"></span>walls</span>' +
  '<span class="lg"><span class="dot" style="background:var(--violet)"></span>infill</span>' +
  '<span class="lg"><span class="dot" style="background:var(--ok)"></span>supports</span>' +
  '<span class="lg"><span class="dot" style="background:var(--info)"></span>travel</span>' +
  '</div><span style="flex:1"></span>' +
  '<span class="chip" style="border-color:rgba(232,180,76,.4);color:var(--warn)">' + icon('bolt', 13) + 'Events · 3</span>' +
  '</div>' +
  '<div class="layerslider"><span class="layer-chip">L42</span>' +
  '<div class="sl-track"><div class="bar"></div><div class="thumb" style="left:36%"></div></div>' +
  '<span class="tiny">118</span></div>' +
  '</div>' +
  '<div class="statusstrip"><span class="dot ok"></span><span>Sliced</span><span style="flex:1"></span><span>118 layers · est. 2 h 34 m · 26.4 g</span></div>' +
  '<div class="actionbar">' +
  '<span class="btn ghost">' + icon('wrench', 18) + 'Tools</span>' +
  '<span class="btn filled wide">' + icon('bolt', 17) + 'Slice</span>' +
  '<span class="btn outline wide">Export</span>' +
  '</div>' + nav('plate')
));

// ============ 03 — Print settings ============
const field = (label, value, unit, badge, sub, extra) =>
  '<div class="field">' +
  '<div class="flabel"><span class="fl">' + label + '</span>' + (sub ? '<span class="fs">' + sub + '</span>' : '') + '</div>' +
  '<div class="fvalue">' + (extra || '') +
  (value ? '<span class="v">' + value + '</span>' + (unit ? '<span class="u">' + unit + '</span>' : '') : '') +
  (badge ? '<span class="badge ' + badge[0] + '">' + badge[1] + '</span>' : '') +
  '</div></div>';

const switchField = (label, on, badge, sub) =>
  '<div class="field"><div class="flabel"><span class="fl">' + label + '</span>' + (sub ? '<span class="fs">' + sub + '</span>' : '') + '</div>' +
  '<div class="fvalue"><span class="switch' + (on ? ' on' : '') + '"></span>' +
  (badge ? '<span class="badge ' + badge[0] + '">' + badge[1] + '</span>' : '') + '</div></div>';

write('03-settings.html', page(
  statusbar() +
  '<div class="topbar"><div class="tb-left">' +
  '<span class="iconbtn on">' + icon('back', 20) + '</span>' +
  '<div class="tb-title"><span class="t1">Print settings</span><span class="t2">Apply immediately · no save step</span></div></div>' +
  '<div class="tb-right"><span class="iconbtn">' + icon('refresh', 19) + '</span><span class="iconbtn">' + icon('dots', 20) + '</span></div></div>' +
  '<div class="content"><div class="pad">' +
  '<div class="card">' +
  '<div class="card-head"><div><div class="card-title">PLA · 0.20 mm · 15% infill</div>' +
  '<div class="card-sub">Imported profile “PLA Ultra 0.20” · Cura 5.14.0-alpha.0</div></div>' +
  '<span class="badge b-override">6 overrides</span></div>' +
  '<div class="wrap mt8"><span class="chip" style="border-color:rgba(232,180,76,.4);color:var(--warn)">' + icon('alert', 13) + '2 warnings</span>' +
  '<span class="chip">Reset overrides</span></div></div>' +
  '<div class="search">' + icon('search', 17) + 'Search 60 settings…</div>' +
  '<div class="chiprow">' +
  '<span class="chip on">All</span><span class="chip on">Quality</span><span class="chip">Walls &amp; top</span><span class="chip">Infill</span>' +
  '<span class="chip">Speed</span><span class="chip">Material</span><span class="chip">Cooling</span><span class="chip">Supports</span>' +
  '<span class="chip">Travel</span><span class="chip">Adhesion</span><span class="chip">Experimental</span></div>' +
  '<div class="fgroup">' +
  field('Layer height', '0.20', 'mm', ['b-profile', 'PROFILE']) +
  field('Initial layer height', '0.28', 'mm', ['b-override', 'APP'], 'Only the first layer') +
  switchField('Adaptive layer height', true, ['b-override', 'APP'], '±0.04 mm around 0.20') +
  field('Maximum variation', '0.04', 'mm', ['b-profile', 'PROFILE']) +
  field('Variation step', '0.02', 'mm', ['b-profile', 'PROFILE']) +
  '</div>' +
  '<div class="fgroup">' +
  '<div class="field"><div class="flabel"><span class="fl">Walls and top/bottom</span><span class="fs">2 walls · 0.80 mm · 6 top · 6 bottom</span></div>' +
  '<div class="fvalue"><span class="badge b-profile">PROFILE</span>' + icon('chev', 16) + '</div></div></div>' +
  '<div class="fgroup">' +
  '<div class="field"><div class="flabel"><span class="fl">Infill</span><span class="fs">15% grid · 45° · 2 mm</span></div>' +
  '<div class="fvalue"><span class="badge b-profile">PROFILE</span>' + icon('chev', 16) + '</div></div></div>' +
  '<div class="fgroup">' +
  '<div class="field"><div class="flabel"><span class="fl">Speed</span><span class="fs">50 mm/s · walls 25 · travel 120</span></div>' +
  '<div class="fvalue"><span class="badge b-imported">IMPORTED</span>' + icon('chev', 16) + '</div></div></div>' +
  '</div></div>' +
  '<div class="actionbar"><span class="btn ghost wide">Close</span><span class="btn filled wide">Done</span></div>' +
  nav('settings')
));

// ============ 04 — Print (OctoPrint live) ============
write('04-print.html', page(
  statusbar() +
  '<div class="topbar"><div class="tb-left">' +
  '<span class="iconbtn on">' + icon('back', 20) + '</span>' +
  '<div class="tb-title"><span class="t1">Print</span><span class="t2">octopi.local · 192.168.1.50</span></div></div>' +
  '<div class="tb-right"><span class="iconbtn">' + icon('gear', 19) + '</span><span class="iconbtn">' + icon('dots', 20) + '</span></div></div>' +
  '<div class="content"><div class="pad">' +
  '<div class="card tight"><div class="space">' +
  '<span style="display:inline-flex;align-items:center;gap:8px"><span class="dot ok"></span><b>Connected</b></span>' +
  '<span class="tiny">OctoPrint 1.9.0 · API v1</span></div></div>' +
  '<div class="card">' +
  '<div class="card-head"><div><div class="card-title">benchy_top.gcode</div><div class="card-sub">64% · started 1 h 23 m ago</div></div>' +
  '<span class="chip" style="border-color:rgba(62,207,122,.35);color:var(--ok)">Printing</span></div>' +
  '<div class="pbar alt mt8"><i style="width:64%"></i></div>' +
  '<div class="space mt8"><span class="tiny">elapsed 1:23:04</span><b style="font-size:13px">2:34 total</b><span class="tiny">remaining 0:47:12</span></div>' +
  '<div class="wrap mt8"><span class="btn outline wide">' + icon('pause', 15) + 'Pause</span>' +
  '<span class="btn danger wide">' + icon('stop', 15) + 'Cancel</span></div></div>' +
  '<div class="temprow">' +
  '<div class="temp"><div class="t-label">Hotend</div><div class="t-now ready">218°</div><div class="t-set">set 210° · holding</div></div>' +
  '<div class="temp"><div class="t-label">Bed</div><div class="t-now ready">62°</div><div class="t-set">set 60° · holding</div></div>' +
  '<div class="temp"><div class="t-label">Fan</div><div class="t-now">40%</div><div class="t-set">part cooling</div></div></div>' +
  '<div class="card"><div class="card-head"><div class="card-title">Camera</div><span class="live"><span class="pulse"></span>LIVE</span></div>' +
  '<div class="webcam mt8"><div class="cam-ph okless">' + icon('cam', 64) + '</div><div class="fake-print"></div>' +
  '<div class="c1 tiny">octopi/webcam</div><div class="c2"><span class="iconbtn" style="background:rgba(17,22,29,.8)">' + icon('expand', 15, 'var(--text)') + '</span></div></div></div>' +
  '<div class="card"><div class="card-head"><div class="card-title">Files</div><span class="tiny">4 items</span></div>' +
  '<div class="row"><div class="row-ico">' + icon('file', 17) + '</div><div class="row-body"><span class="row-title">benchy_top.gcode</span><span class="row-sub">4.2 MB · printed 2 h ago</span></div><div class="row-end"><span style="color:var(--ok)">' + icon('play', 17) + '</span></div></div>' +
  '<div class="row"><div class="row-ico">' + icon('file', 17) + '</div><div class="row-body"><span class="row-title">mini_benchy.gcode</span><span class="row-sub">3.1 MB · printed yesterday</span></div><div class="row-end">' + icon('chev', 16) + '</div></div></div>' +
  '</div></div>' + nav('print')
));

// ============ 05 — More (hub) ============
const menuRow = (ico, title, sub, end, badge) =>
  '<div class="row"><div class="row-ico">' + icon(ico, 17) + '</div>' +
  '<div class="row-body"><span class="row-title">' + title + '</span><span class="row-sub">' + sub + '</span></div>' +
  '<div class="row-end">' + (badge || '') + '<span>' + (end || icon('chev', 16)) + '</span></div></div>';

write('05-more.html', page(
  statusbar() +
  '<div class="topbar"><div class="tb-left">' + brand() +
  '<div class="tb-title"><span class="t1">More</span><span class="t2">Everything outside the plate</span></div></div>' +
  '<div class="tb-right"><span class="iconbtn">' + icon('dots', 20) + '</span></div></div>' +
  '<div class="content"><div class="pad">' +
  '<div class="sec-label">Configuration</div>' +
  '<div class="fgroup">' +
  menuRow('star', 'Profiles &amp; filament', '3 profiles · 2 filaments') +
  menuRow('gear', 'Printer &amp; G-code', 'Ender 3 V2 · 220×220×250 mm') +
  menuRow('swap', 'Configuration snapshot', 'Import or export the full setup') +
  '</div>' +
  '<div class="sec-label">Experimental</div>' +
  '<div class="fgroup">' +
  menuRow('cam', 'BumpMesh texturizer', 'Planar / cylindrical displacement', '', '<span class="badge b-exp">EXP</span>') +
  menuRow('chart', 'Smart Infill (filaSim)', 'Load-optimized density modifiers', '', '<span class="badge b-exp">EXP</span>') +
  menuRow('layers', 'Non-planar slicing', 'CurviSlicer relief-field print', 'Off') +
  menuRow('bolt', 'Conical slicing', 'Cone-warped geometry modifier', 'Off') +
  menuRow('filter', 'Mesh triangle limit', '4.0M triangles') +
  '</div>' +
  '<div class="sec-label">About</div>' +
  '<div class="fgroup">' +
  menuRow('info', 'EnderSlicerCura', 'Version 1.0.0 · AGPL-3.0-or-later') +
  menuRow('shield', 'Safety notes', 'Before printing: inspect, verify, confirm') +
  '</div></div></div>' + nav('more')
));

// ============ 06 — Printer ============
const check = (done, title, sub) =>
  '<div class="check"><div class="c-ico ' + (done ? 'done' : 'todo') + '">' +
  (done ? icon('check', 14) : icon('clock', 14)) + '</div>' +
  '<div class="c-body"><div class="c-title">' + title + '</div><div class="c-sub">' + sub + '</div></div></div>';

write('06-printer.html', page(
  statusbar() +
  '<div class="topbar"><div class="tb-left">' +
  '<span class="iconbtn on">' + icon('back', 20) + '</span>' +
  '<div class="tb-title"><span class="t1">Printer</span><span class="t2">Machine profile &amp; safety</span></div></div>' +
  '<div class="tb-right"><span class="iconbtn">' + icon('refresh', 19) + '</span><span class="iconbtn">' + icon('dots', 20) + '</span></div></div>' +
  '<div class="content"><div class="pad">' +
  '<div class="card">' +
  '<div class="card-head"><div class="card-title">Safety checklist</div><span class="chip" style="border-color:rgba(62,207,122,.35);color:var(--ok)">' + icon('check', 12) + '5 / 5</span></div>' +
  '<div class="mt8">' +
  check(true, 'Build volume matches your printer', '220 × 220 × 250 mm') +
  check(true, 'Nozzle 0.40 mm installed', 'Matches the profile') +
  check(true, 'Max hotend 260 °C', 'Below firmware limit') +
  check(true, 'Start &amp; end G-code verified', 'M140 bed wait · G28 home') +
  check(true, 'OctoPrint tested', 'Live at octopi.local') +
  '</div></div>' +
  '<div class="card"><div class="card-head"><div class="card-title">Machine profile</div><span class="badge b-override">3 app overrides</span></div>' +
  '<div class="mt8 fgroup" style="border:none;background:transparent">' +
  field('Printer name', 'Ender 3 V2', '', ['b-profile', 'PROFILE']) +
  field('Build width X', '220', 'mm', ['b-profile', 'PROFILE']) +
  field('Build depth Y', '220', 'mm', ['b-profile', 'PROFILE']) +
  field('Build height Z', '250', 'mm', ['b-profile', 'PROFILE']) +
  field('Nozzle diameter', '0.40', 'mm', ['b-override', 'APP']) +
  field('Bed default temp', '60', '°C', ['b-override', 'APP']) +
  '</div></div>' +
  '<div class="card"><div class="card-head"><div class="card-title">Start &amp; end G-code</div><span class="textaction">' + icon('code', 15) + 'Edit</span></div>' +
  '<div class="mono mt8"><span class="gcode">M140 S</span><span class="val">60</span> ; bed target<br>' +
  '<span class="gcode">M190 S</span><span class="val">60</span> ; wait bed<br>' +
  '<span class="gcode">G28</span> ; home all<br>' +
  '<span class="gcode">G1 Z</span><span class="val">2.8</span> ; raise …</div></div>' +
  '</div></div>' + nav('more')
));

// ============ 07 — Onboarding (first run) ============
write('07-onboarding.html', page(
  statusbar() +
  '<div style="flex:1;display:flex;flex-direction:column;padding:12px 18px">' +
  '<div style="display:flex;flex-direction:column;align-items:center;gap:12px;padding:24px 0 4px">' +
  '<div class="brand" style="width:58px;height:58px;border-radius:16px">' + icon('cube', 34, '#1A1206', 1.9) + '</div>' +
  '<div style="font-size:21px;font-weight:800">EnderSlicerCura</div>' +
  '<div style="font-size:13px;color:var(--text2);text-align:center;line-height:1.55">One app for slicing, previewing and printing.<br>Start by telling us about your machine.</div></div>' +
  '<div class="card" style="margin-top:16px"><div class="card-head"><div class="card-title">Your printer</div><span class="badge b-imported">STEP 1 OF 3</span></div>' +
  '<div class="mt8 fgroup" style="border:none;background:transparent">' +
  field('Printer name', 'Ender 3 V2', '') +
  field('Build width X', '220', 'mm') +
  field('Build depth Y', '220', 'mm') +
  field('Build height Z', '250', 'mm') +
  field('Nozzle diameter', '0.40', 'mm') +
  field('Bed default temp', '60', '°C') +
  '</div></div>' +
  '<div class="card tight mt8" style="display:flex;gap:10px;align-items:center">' +
  icon('info', 18, 'var(--info)') +
  '<span class="small" style="flex:1">Importing a Cura project (<b>.3mf</b>) fills machine + print settings from your desktop setup.</span></div>' +
  '<div style="flex:1"></div>' +
  '<div class="space" style="padding:10px 2px"><div class="dots"><div class="d on"></div><div class="d"></div><div class="d"></div></div>' +
  '<span class="tiny">skip &amp; keep defaults</span></div>' +
  '<div class="actionbar" style="border-top:1px solid var(--border);padding:10px 2px 12px">' +
  '<span class="btn ghost wide">Skip for now</span><span class="btn filled wide">Continue →</span></div>' +
  '</div>'
));

// ============ 08 — Foldable (unfolded, landscape) ============
write('08-foldable.html', page(
  statusbar() +
  '<div class="topbar"><div class="tb-left">' + brand() +
  '<div class="tb-title"><span class="t1">Plate</span><span class="t2">bench_lowpoly.stl · 12,482 tri</span></div></div>' +
  '<div class="tb-right"><span class="btn outline small">' + icon('plus', 15) + 'Import</span>' +
  '<span class="iconbtn">' + icon('dots', 20) + '</span></div></div>' +
  '<div class="split">' +
  '<div class="pane-l">' +
  scene({ hud:
    '<div class="hud tl"><div class="hudcard">' +
    '<div class="hc-title">' + icon('cube', 15, 'var(--accent)') + 'bench_lowpoly.stl</div>' +
    '<div class="hc-line">Layer 42 of 118 · Z 3.42 mm · est. 2 h 34 m</div>' +
    '</div></div>' +
    '<div class="hud tr">' + seg('layers') + '</div>'
  }) +
  '<div style="flex:0 0 auto;background:var(--surface);border-top:1px solid var(--border);padding:6px 16px">' +
  '<div class="layerslider"><span class="layer-chip">L42</span>' +
  '<div class="sl-track"><div class="bar"></div><div class="thumb" style="left:36%"></div></div><span class="tiny">118</span></div></div>' +
  '</div>' +
  '<div class="pane-r">' +
  '<div class="card"><div class="card-head"><div class="card-title">Print session</div>' +
  '<span class="chip" style="border-color:rgba(62,207,122,.35);color:var(--ok)">' + icon('check', 12) + 'Ready</span></div>' +
  '<div class="chiprow mt8">' +
  '<span class="chip on">118 layers</span><span class="chip on">2 h 34 m</span><span class="chip on">26.4 g</span><span class="chip on">0 warnings</span></div>' +
  '<div class="temprow mt10">' +
  '<div class="temp"><div class="t-label">Layer</div><div class="t-now" style="color:var(--text)">0.20</div><div class="t-set">mm</div></div>' +
  '<div class="temp"><div class="t-label">Infill</div><div class="t-now" style="color:var(--text)">15%</div><div class="t-set">grid</div></div>' +
  '<div class="temp"><div class="t-label">Supports</div><div class="t-now" style="color:var(--ok)">ON</div><div class="t-set">touch build plate</div></div></div></div>' +
  '<div class="card"><div class="card-head"><div class="card-title">Quick settings</div><span class="tiny">tap to edit</span></div>' +
  '<div class="mt8">' +
  field('Layer height', '0.20', 'mm', ['b-override', 'APP']) +
  field('Infill', '15', '%', ['b-profile', 'PROFILE']) +
  switchField('Supports', true, ['b-profile', 'PROFILE']) +
  switchField('Brim', true, null) +
  '</div></div>' +
  '<div class="card"><div class="card-title">Actions</div>' +
  '<div class="mt8" style="display:flex;flex-direction:column;gap:8px">' +
  '<span class="btn filled">' + icon('bolt', 17) + 'Slice again</span>' +
  '<span class="btn outline">Export G-code</span>' +
  '<span class="btn outline">' + icon('wrench', 16) + 'Tools</span>' +
  '<span class="btn ghost">' + icon('cam', 16) + 'Send to OctoPrint</span>' +
  '</div></div>' +
  '</div></div>' +
  nav('plate'),
  'foldable'
));

// ============ 09 — Plate (nozzle path, high-quality shaded camera) ============
const npArt = () => {
  const loop = (d, w) =>
    '<g>' +
    '<path d="' + d + '" fill="none" stroke="#000" stroke-width="' + w + '" stroke-linecap="round" stroke-linejoin="round" opacity="0.35" transform="translate(0,7)" filter="blur(3px)"/>' +
    '<path d="' + d + '" fill="none" stroke="url(#npside)" stroke-width="' + w + '" stroke-linecap="round" stroke-linejoin="round" transform="translate(0,-6)"/>' +
    '<path d="' + d + '" fill="none" stroke="url(#nptop)" stroke-width="' + (w - 2) + '" stroke-linecap="round" stroke-linejoin="round" transform="translate(0,-6)"/>' +
    '</g>';
  const outer = 'M62 196 L62 128 Q62 94 96 94 L262 94 Q298 94 298 128 L298 196 Q298 214 274 214 L86 214 Q62 214 62 196 Z';
  const mid = 'M80 196 L80 128 Q80 108 100 108 L258 108 Q282 108 282 128 L282 196 Q282 202 268 202 L94 202 Q80 202 80 196 Z';
  const inner = 'M98 196 L98 130 Q98 118 106 118 L252 118 Q266 118 266 130 L266 196 Q266 194 258 194 L106 194 Q98 194 98 196 Z';
  const infill = 'M112 178 L244 128 M124 192 L252 144 M146 194 L262 158 M112 160 L220 120';
  const travel = 'M40 96 L62 112 M298 96 L318 90';
  const sel = 'M62 196 L62 128 Q62 94 96 94 L110 94';
  return '<svg width="330" height="248" viewBox="0 0 400 300">' +
    '<defs>' +
    '<linearGradient id="nptop" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFDFAE"/><stop offset="1" stop-color="#FFAF5C"/></linearGradient>' +
    '<linearGradient id="npside" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#8A5A28"/><stop offset="1" stop-color="#4A2F14"/></linearGradient>' +
    '<filter id="npblur" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="4"/></filter>' +
    '</defs>' +
    '<ellipse cx="180" cy="236" rx="150" ry="18" fill="rgba(0,0,0,.5)" filter="url(#npblur)"/>' +
    '<path d="' + travel + '" fill="none" stroke="#6FB8FF" stroke-width="5" stroke-dasharray="7 8" stroke-linecap="round" opacity=".75"/>' +
    '<path d="' + infill + '" fill="none" stroke="url(#nptop)" stroke-width="9" stroke-linecap="round" opacity=".65"/>' +
    '<path d="' + infill + '" fill="none" stroke="#5C3A16" stroke-width="9" stroke-linecap="round" opacity=".5" transform="translate(0,5)"/>' +
    loop(inner, 10) + loop(mid, 11) +
    '<path d="' + outer + '" fill="none" stroke="#000" stroke-width="13" stroke-linecap="round" stroke-linejoin="round" opacity=".35" transform="translate(0,8)" filter="url(#npblur)"/>' +
    '<path d="' + outer + '" fill="none" stroke="url(#npside)" stroke-width="13" stroke-linecap="round" stroke-linejoin="round" transform="translate(0,-6)"/>' +
    '<path d="' + outer + '" fill="none" stroke="url(#nptop)" stroke-width="10.5" stroke-linecap="round" stroke-linejoin="round" transform="translate(0,-6)"/>' +
    '<path d="' + sel + '" fill="none" stroke="#FFE2B8" stroke-width="14.5" stroke-linecap="round" stroke-linejoin="round"/>' +
    '<path d="' + sel + '" fill="none" stroke="#FFFFFF" stroke-width="9" stroke-linecap="round" stroke-linejoin="round" opacity=".85"/>' +
    '<circle cx="62" cy="196" r="7" fill="#fff" stroke="#1A1206" stroke-width="3"/>' +
    '</svg>';
};

write('09-nozzle-path.html', page(
  statusbar() +
  '<div class="topbar"><div class="tb-left">' + brand() +
  '<div class="tb-title"><span class="t1">Plate</span><span class="t2">bench_lowpoly.stl · nozzle path</span></div></div>' +
  '<div class="tb-right"><span class="btn outline small">' + icon('plus', 15) + 'Import</span>' +
  '<span class="iconbtn">' + icon('dots', 20) + '</span></div></div>' +
  '<div class="scene">' +
  '<div class="np-art">' + npArt() + '</div>' +
  '<div class="vignette"></div>' +
  '<div class="hud tl"><div class="hudcard">' +
  '<div class="hc-title">' + icon('wave', 15, 'var(--accent)') + 'Nozzle path</div>' +
  '<div class="hc-line">3 walls · grid infill · L42</div>' +
  '</div></div>' +
  '<div class="hud tr"><div class="np-hudcorner">' +
  '<div class="segmented">' +
  '<div class="seg">' + icon('cube', 15) + 'Model</div>' +
  '<div class="seg">' + icon('layers', 15) + 'Layers</div>' +
  '<div class="seg on">' + icon('wave', 15) + 'Path</div></div>' +
  '<div class="hudcard"><div class="hc-line flex">' +
  '<span>Perspective · 32° orbit · 1.6×</span><span class="accent">Fit</span></div>' +
  '</div></div></div>' +
  '<div class="hud bl"><div class="hudcard" style="border-color:rgba(255,194,119,.55)">' +
  '<div class="hc-title">' + icon('bolt', 14, 'var(--accent)') + 'Selected move</div>' +
  '<div class="hc-line">X 12.4 → 13.1 · width <b class="accent">0.44</b> mm · height 0.20 mm</div>' +
  '<div class="hc-line">flow <b class="accent">104%</b> · speed 50 mm/s · two-finger orbit to inspect</div>' +
  '</div></div>' +
  '</div>' +
  '<div style="flex:0 0 auto;background:var(--surface);border-top:1px solid var(--border);padding:8px 14px">' +
  '<div style="display:flex;align-items:center;gap:12px">' +
  '<div class="legend">' +
  '<span class="lg"><span class="dot" style="background:var(--accent)"></span>extruded</span>' +
  '<span class="lg"><span class="dot" style="background:var(--violet)"></span>infill</span>' +
  '<span class="lg"><span class="dot" style="background:var(--info)"></span>travel</span>' +
  '</div><span style="flex:1"></span>' +
  '<span class="chip" style="border-color:rgba(62,207,122,.35);color:var(--ok)">flow-based widths</span>' +
  '</div>' +
  '<div class="layerslider"><span class="layer-chip">L42</span>' +
  '<div class="sl-track"><div class="bar"></div><div class="thumb" style="left:36%"></div></div>' +
  '<span style="display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;color:var(--text)">' + icon('play', 16) + '</span></div>' +
  '</div>' +
  '<div class="statusstrip"><span class="dot info"></span><span>Path rendering · analytic normals</span><span style="flex:1"></span><span>118 layers · wall 0.44 mm</span></div>' +
  '<div class="actionbar">' +
  '<span class="btn ghost">' + icon('wrench', 18) + 'Tools</span>' +
  '<span class="btn filled wide">' + icon('bolt', 17) + 'Slice</span>' +
  '<span class="btn outline wide">Export</span>' +
  '</div>' + nav('plate')
));

console.log('Generated 9 mockup HTML files in', OUT);
