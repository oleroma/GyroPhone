// Helpers for the guide's mockups. Layouts are copied from the app's code: BuiltinProfiles.kt
// (the Controller, WASD and Keyboard workspaces), NavCorners.kt (the ← / → buttons) and
// CapsuleRow.kt (the top row). Positions are centres as fractions of the pad, sizes fractions of its short side.

const ICON = {
  // EditIcons.kt
  gear: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><path d="M9.81,4.93L10.09,2.59L13.91,2.59L14.19,4.93A7.4,7.4 0 0,1 15.45,5.45L17.30,4.00L20.00,6.70L18.55,8.55A7.4,7.4 0 0,1 19.07,9.81L21.41,10.09L21.41,13.91L19.07,14.19A7.4,7.4 0 0,1 18.55,15.45L20.00,17.30L17.30,20.00L15.45,18.55A7.4,7.4 0 0,1 14.19,19.07L13.91,21.41L10.09,21.41L9.81,19.07A7.4,7.4 0 0,1 8.55,18.55L6.70,20.00L4.00,17.30L5.45,15.45A7.4,7.4 0 0,1 4.93,14.19L2.59,13.91L2.59,10.09L4.93,9.81A7.4,7.4 0 0,1 5.45,8.55L4.00,6.70L6.70,4.00L8.55,5.45A7.4,7.4 0 0,1 9.81,4.93ZM15.2,12a3.2,3.2 0 1,1 -6.4,0a3.2,3.2 0 1,1 6.4,0Z"/></svg>',
  tune: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M3,6H12.8M17.2,6H21M12.8,6a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0M3,12H5.8M10.2,12H21M5.8,12a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0M3,18H14.8M19.2,18H21M14.8,18a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0"/></svg>',
  undo: '<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 14L4 9l5-5"/><path d="M4 9h10a6 6 0 0 1 0 12h-3"/></svg>',
  redo: '<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 14l5-5-5-5"/><path d="M20 9H10a6 6 0 0 0 0 12h3"/></svg>',
  lockOpen: '<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 7.5-2"/></svg>',
  lock: '<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg>',
  // CapsuleRow.kt DeviceIcons: a game pad, a keyboard and a mouse.
  devices: '<svg width="66" height="20" viewBox="0 0 66 20" fill="none" stroke="#fff" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M6 5L18 5C22 5 24 12 23.5 15C23 17.5 20 17.5 18.5 15L17 13L7 13L5.5 15C4 17.5 1 17.5 .5 15C0 12 2 5 6 5Z"/><path d="M5 9H9M7 7V11"/><circle cx="16" cy="8" r="1.1" fill="#fff" stroke="none"/><circle cx="18.5" cy="10.5" r="1.1" fill="#fff" stroke="none"/><rect x="28" y="4" width="22" height="13" rx="2"/><g fill="#fff" stroke="none"><circle cx="31.5" cy="7.5" r=".9"/><circle cx="35.25" cy="7.5" r=".9"/><circle cx="39" cy="7.5" r=".9"/><circle cx="42.75" cy="7.5" r=".9"/><circle cx="46.5" cy="7.5" r=".9"/><circle cx="31.5" cy="10.5" r=".9"/><circle cx="35.25" cy="10.5" r=".9"/><circle cx="39" cy="10.5" r=".9"/><circle cx="42.75" cy="10.5" r=".9"/><circle cx="46.5" cy="10.5" r=".9"/></g><path d="M34 13.8H44"/><rect x="56" y="2" width="10" height="16" rx="5"/><path d="M61 2.5V8M56.5 8H65.5"/></svg>',
};

const C = 'circle', S = 'square';

// BuiltinProfiles.kt: landscape placements [id, kind, label, x, y, size, shape].
const LAYOUTS = {
  wasd: [
    ['move', 'stick', 'WASD', .14, .64, .38, C], ['esc', 'b', 'Esc', .05, .17, .10, S], ['tab', 'b', 'Tab', .12, .17, .10, S],
    ['shift', 'b', 'Shift', .05, .31, .12, S], ['q', 'b', 'Q', .13, .31, .12, S], ['e', 'b', 'E', .21, .31, .12, S],
    ['k1', 'b', '1', .38, .12, .11, S], ['k2', 'b', '2', .46, .12, .11, S], ['k3', 'b', '3', .54, .12, .11, S], ['k4', 'b', '4', .62, .12, .11, S],
    ['r', 'b', 'R', .91, .32, .12, S], ['f', 'b', 'F', .80, .38, .12, S], ['lmb', 'b', 'LMB', .78, .64, .20, C],
    ['rmb', 'b', 'RMB', .91, .54, .14, C], ['space', 'b', 'Space', .91, .80, .16, C], ['ctrl', 'b', 'Ctrl', .78, .90, .12, S],
    ['ratchet', 'b', 'Ratchet', .64, .74, .14, C],
  ],
  controller: [
    ['ls', 'stick', 'LS', .14, .62, .38, C], ['rs', 'stick', 'RS', .66, .74, .30, C],
    ['a', 'b', 'A', .88, .70, .16, C], ['b', 'b', 'B', .95, .52, .16, C], ['x', 'b', 'X', .81, .52, .16, C], ['y', 'b', 'Y', .88, .34, .16, C],
    ['up', 'b', '↑', .34, .62, .12, S], ['down', 'b', '↓', .34, .86, .12, S], ['left', 'b', '←', .28, .74, .12, S], ['right', 'b', '→', .40, .74, .12, S],
    ['lb', 'b', 'LB', .10, .14, .13, S], ['lt', 'b', 'LT', .22, .14, .13, S], ['rb', 'b', 'RB', .90, .14, .13, S], ['rt', 'b', 'RT', .78, .14, .13, S],
    ['view', 'b', 'View', .42, .30, .10, S], ['menu', 'b', 'Menu', .58, .30, .10, S], ['gyro', 'b', 'Gyro', .50, .52, .12, C],
  ],
  // NavCorners.kt defaults(): ← top left, → top right, 8 % of the short side.
  nav: [['nav_prev', 'b', '←', .03, .06, .08, C], ['nav_next', 'b', '→', .97, .06, .08, C]],
};

/** Draws controls into a screen element of size w × h. opts.state maps id → 'pressed' | 'selected' | ... */
function drawControls(screen, list, w, h, opts = {}) {
  const short = Math.min(w, h);
  for (const [id, kind, label, x, y, size, shape] of list) {
    const d = size * short;
    const el = document.createElement('div');
    el.className = 'ctl ' + (shape === S ? 'square' : 'circle') + (opts.state && opts.state[id] ? ' ' + opts.state[id] : '');
    el.style.cssText = `left:${x * w - d / 2}px;top:${y * h - d / 2}px;width:${d}px;height:${d}px;` + (opts.dim && opts.dim[id] ? `opacity:${opts.dim[id]};` : '');
    el.dataset.id = id;
    if (kind === 'stick') {
      const k = document.createElement('div');
      k.className = 'knob';
      const kd = d / 2.5, off = opts.knob && opts.knob[id] ? opts.knob[id] : [0, 0];
      k.style.cssText = `width:${kd}px;height:${kd}px;left:${d / 2 - kd / 2 + off[0] * d * .3 - 2}px;top:${d / 2 - kd / 2 + off[1] * d * .3 - 2}px`;
      el.appendChild(k);
    }
    const t = document.createElement('span');
    const shown = opts.labels && opts.labels[id] != null ? opts.labels[id] : label;
    t.innerHTML = shown;
    if (opts.labels && opts.labels[id] != null && opts.liveIds && opts.liveIds.includes(id)) t.className = 'live';
    if (kind !== 'stick') el.appendChild(t); else { t.style.position = 'relative'; el.appendChild(t); }
    // Shrink long labels to fit, as the app's autoSize does (down to 7 sp).
    screen.appendChild(el);
    const inner = shape === S ? d - 8 : d * .7 - 4;
    let fs = 14;
    while (t.scrollWidth > inner && fs > 7) { fs -= .5; t.style.fontSize = fs + 'px'; }
  }
}

/** The capsule row: [label, kind] where kind is 'ws', 'wsOn', 'devices', 'app', 'defaults', 'conn'. */
function capsules(screen, items, at = { left: 8, top: 8 }) {
  const row = document.createElement('div');
  row.className = 'caps';
  row.style.left = at.left + 'px';
  row.style.top = at.top + 'px';
  for (const [label, kind] of items) {
    const c = document.createElement('div');
    c.className = 'cap' + (kind === 'wsOn' ? ' on' : '') + (kind === 'app' || kind === 'defaults' ? ' icon' : '');
    if (kind === 'devices') c.innerHTML = ICON.devices + (label ? `<span>${label}</span>` : '');
    else if (kind === 'app') c.innerHTML = ICON.gear + label;
    else if (kind === 'defaults') c.innerHTML = ICON.tune + label;
    else c.textContent = label;
    row.appendChild(c);
  }
  screen.appendChild(row);
  return row;
}

/** A numbered callout at (x, y) in the screen's (or any positioned parent's) px. */
function callout(parent, n, x, y) {
  const el = document.createElement('div');
  el.className = 'co';
  el.textContent = n;
  el.style.left = (x - 13) + 'px';
  el.style.top = (y - 13) + 'px';
  parent.appendChild(el);
}

/** A dashed ring around a box (parent px). */
function ring(parent, x, y, w, h, round = false) {
  const el = document.createElement('div');
  el.className = 'ring' + (round ? ' round' : '');
  el.style.cssText = `left:${x - 4}px;top:${y - 4}px;width:${w + 8}px;height:${h + 8}px`;
  parent.appendChild(el);
}

/** A dashed ring around an element. */
function ringEl(parent, el, round = false, pad = 0) {
  const p = parent.getBoundingClientRect(), r = el.getBoundingClientRect();
  ring(parent, r.left - p.left - pad, r.top - p.top - pad, r.width + 2 * pad, r.height + 2 * pad, round);
  return { x: r.left - p.left, y: r.top - p.top, w: r.width, h: r.height };
}

/** Where an element sits inside parent. */
function boxOf(parent, el) {
  const p = parent.getBoundingClientRect(), r = el.getBoundingClientRect();
  return { x: r.left - p.left, y: r.top - p.top, w: r.width, h: r.height, cx: r.left - p.left + r.width / 2, cy: r.top - p.top + r.height / 2 };
}

function finger(parent, x, y, hold = false) {
  const el = document.createElement('div');
  el.className = 'finger' + (hold ? ' hold' : '');
  el.style.left = (x - 17) + 'px';
  el.style.top = (y - 17) + 'px';
  parent.appendChild(el);
}

function arrow(parent, x1, y1, x2, y2) {
  const el = document.createElement('div');
  el.className = 'arrow';
  const len = Math.hypot(x2 - x1, y2 - y1) - 10;
  el.style.cssText = `left:${x1}px;top:${y1 - 1.5}px;width:${len}px;transform:rotate(${Math.atan2(y2 - y1, x2 - x1)}rad)`;
  parent.appendChild(el);
}

/** A phone frame; returns its screen element. */
function phone(host, orientation = 'land') {
  const p = document.createElement('div');
  p.className = 'phone ' + orientation;
  p.innerHTML = '<div class="screen"></div><div class="cam"></div>';
  host.appendChild(p);
  return p.querySelector('.screen');
}

function el(parent, html) {
  const t = document.createElement('template');
  t.innerHTML = html.trim();
  const n = t.content.firstChild;
  parent.appendChild(n);
  return n;
}

let _lead = null;
/** A numbered tag at (tx, ty) in stage px with a leader line to (x, y), both relative to the stage. */
function tag(stage, n, x, y, tx, ty, via = []) {
  if (!_lead || _lead.parentNode !== stage) {
    _lead = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    _lead.setAttribute('class', 'lead');
    _lead.setAttribute('width', stage.offsetWidth);
    _lead.setAttribute('height', stage.offsetHeight);
    stage.appendChild(_lead);
  }
  // A leader from the badge to the dot, bending at the [via] points (listed from the dot's end).
  const l = document.createElementNS('http://www.w3.org/2000/svg', 'polyline');
  const pts = [[x, y], ...via, [tx, ty]].map(p => p.join(',')).join(' ');
  Object.entries({ points: pts, fill: 'none', stroke: '#FF6B57', 'stroke-width': 2.5, 'stroke-linejoin': 'round' }).forEach(([k, v]) => l.setAttribute(k, v));
  _lead.appendChild(l);
  const d = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
  Object.entries({ cx: x, cy: y, r: 3.5, fill: '#FF6B57', stroke: '#fff', 'stroke-width': 1.5 }).forEach(([k, v]) => d.setAttribute(k, v));
  _lead.appendChild(d);
  callout(stage, n, tx, ty);
}

/** Centre of an element in stage px, nudged by (dx, dy). */
function at(stage, elm, dx = 0, dy = 0) {
  const b = boxOf(stage, elm);
  return [b.cx + dx, b.cy + dy];
}

/** The control with this id inside screen. */
function ctl(screen, id) { return screen.querySelector(`[data-id="${id}"]`); }

/** A numbered badge sitting on an element's edge: side 'tl', 'tr', 'bl', 'br', 'l', 'r', 't', 'b' (stage px). */
function badge(stage, n, elm, side = 'tr', gap = 4) {
  const b = boxOf(stage, elm);
  const pos = {
    tl: [b.x - gap, b.y - gap], tr: [b.x + b.w + gap, b.y - gap], bl: [b.x - gap, b.y + b.h + gap], br: [b.x + b.w + gap, b.y + b.h + gap],
    l: [b.x - 13 - gap, b.cy], r: [b.x + b.w + 13 + gap, b.cy], t: [b.cx, b.y - 13 - gap], b: [b.cx, b.y + b.h + 13 + gap],
  }[side];
  callout(stage, n, pos[0], pos[1]);
}

/**
 * Numbered marks kept off the app: each badge sits in the stage's margin outside the device
 * ('t' above, 'b' below, 'l' left, 'r' right of it), with a leader line to the edge of what it
 * points at (that side's edge, so the dot never covers a label). Badges on one side keep their
 * order and are pushed apart so none overlap. items: [n, element, side, {dx, dy, at: 'c'}].
 */
function marks(stage, items, opt = {}) {
  const W = stage.offsetWidth, H = stage.offsetHeight, gapMin = 34;
  const top = opt.top ?? 22, bottom = opt.bottom ?? H - 26, left = opt.left ?? 30, right = opt.right ?? W - 30;
  const by = { t: [], b: [], l: [], r: [] };
  for (const [n, e, side, o = {}] of items) {
    const b = typeof e === 'object' && 'x' in e && !e.tagName ? e : boxOf(stage, e);
    const end = o.at === 'c' ? [b.cx ?? b.x + b.w / 2, b.cy ?? b.y + b.h / 2] : {
      t: [b.x + b.w / 2, b.y], b: [b.x + b.w / 2, b.y + b.h], l: [b.x, b.y + b.h / 2], r: [b.x + b.w, b.y + b.h / 2],
    }[side];
    end[0] += o.dx || 0; end[1] += o.dy || 0;
    const last = o.via?.length ? o.via[o.via.length - 1] : end;
    by[side].push({ n, end, via: o.via || [], pos: side === 't' || side === 'b' ? last[0] + (o.shift || 0) : last[1] + (o.shift || 0) });
  }
  for (const side of 'tblr') {
    const list = by[side].sort((a, b) => a.pos - b.pos);
    const lo = side === 't' || side === 'b' ? 24 : 24, hi = side === 't' || side === 'b' ? W - 24 : H - 24;
    for (let i = 1; i < list.length; i++) list[i].pos = Math.max(list[i].pos, list[i - 1].pos + gapMin);
    if (list.length && list[list.length - 1].pos > hi) {
      list[list.length - 1].pos = hi;
      for (let i = list.length - 2; i >= 0; i--) list[i].pos = Math.min(list[i].pos, list[i + 1].pos - gapMin);
    }
    for (const m of list) {
      m.pos = Math.max(lo, m.pos);
      const [x, y] = side === 't' ? [m.pos, top] : side === 'b' ? [m.pos, bottom] : side === 'l' ? [left, m.pos] : [right, m.pos];
      tag(stage, m.n, m.end[0], m.end[1], x, y, m.via);
    }
  }
}
