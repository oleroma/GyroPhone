// The Overlays page's settings column (OverlaysPage.cs), drawn from its parts (Parts.cs): cards, switches, segmented
// buttons, chips, slider rows (name 150 px, slider at most 260 px, value 70 px), ButtonPick rows and colour swatches.
// A picture shows the column of one picked overlay inside the companion's window frame.
const OV_STYLE = `<style id="ovstyle">
.ovw{width:var(--w,700px)}.ovw .main{padding:20px 26px}
.ovw .wcard{margin-bottom:12px}.ovw .wcard .ct{font-size:14px;color:#E6E9F0;margin-bottom:6px}
.ovw .lbl{display:inline-block;min-width:220px;border:1px solid #3A4357;border-radius:5px;background:#171B24;padding:3px 9px;font-size:18px;margin-bottom:10px}
.ovw .tabs{display:flex;gap:22px;border-bottom:1px solid #2F3748;margin-bottom:14px;font-size:14.5px;color:#9AA3B2}.ovw .tabs span{padding:6px 0 9px}.ovw .tabs span.on{color:#E6E9F0;box-shadow:inset 0 -2.5px 0 #83CFFF}
.ovw .t{font-size:14px;margin:8px 0 2px}.ovw .sb{font-size:13px;color:#8D96A8;margin:10px 0 2px}
.ovw .in{margin-left:20px}
.ovw .wtog{align-items:flex-start;margin:7px 0}.ovw .wtog i{margin-top:1px}.ovw .wtog .tx{flex:1;font-size:14px}.ovw .wtog .tx .wsub{margin-top:2px;font-size:12.5px;line-height:17px}
.ovw .wseg{margin:6px 0 2px}.ovw .wseg span{padding:5px 11px}
.ovw .ovch{display:inline-flex;max-width:100%;flex-wrap:wrap;margin:6px 0 0}.ovw .ovch span{border:1px solid #2F3748;background:#14171F;border-radius:14px;padding:4px 12px;margin:0 6px 6px 0;font-size:13px}.ovw .ovch span.on{border-color:#83CFFF;background:#2B3446}
.ovw .sr{display:grid;grid-template-columns:150px minmax(0,260px) 70px;align-items:center;margin:4px 0;font-size:14px}.ovw .sr>span:first-child{white-space:normal;padding-right:10px;min-width:0}.ovw .sr .v{padding-left:12px;color:#C9CFDA;font-size:13px}
.ovw .sl{height:4px;border-radius:2px;background:#3A4357;position:relative}.ovw .sl b{position:absolute;left:0;top:0;height:4px;background:#83CFFF;border-radius:2px}.ovw .sl b::after{content:"";position:absolute;right:-7px;top:-5px;width:14px;height:14px;border-radius:50%;background:#83CFFF}
.ovw .srs{margin:4px 0;font-size:14px}.ovw .srs .hd{display:flex;justify-content:space-between}.ovw .srs .hd .v{padding-right:8px;color:#C9CFDA;font-size:13px}.ovw .srs .sl{margin:9px 0 6px}
.ovw .bp{display:grid;grid-template-columns:150px auto;align-items:center;font-size:14px;margin:4px 0}.ovw .bp .wbtn{min-width:140px;justify-content:flex-start}
.ovw .ovsw{display:flex;flex-wrap:wrap}.ovw .ovsw span{display:flex;align-items:center;gap:10px;min-width:176px;background:#2F384B;border-radius:6px;padding:5px 12px 5px 8px;margin:0 8px 8px 0;font-size:13px}.ovw .ovsw i{width:34px;height:22px;border-radius:5px;border:1px solid #3A4357;flex:none}
.ovw .wbtn .g{margin-right:8px}
</style>`;
const ov = {
  tog: (t, h, on, id) => `<div class="wtog"${id ? ` id="${id}"` : ''}><i${on ? ' class="on"' : ''}></i><div class="tx"><span class="tl">${t}</span>${h ? `<div class="wsub">${h}</div>` : ''}</div></div>`,
  seg: (opts, on, id) => `<div><div class="wseg"${id ? ` id="${id}"` : ''}>${opts.map((o, i) => `<span${i === on ? ' class="on"' : ''}>${o}</span>`).join('')}</div></div>`,
  chips: (opts, on, id) => `<div><div class="ovch"${id ? ` id="${id}"` : ''}>${opts.map((o, i) => `<span${(Array.isArray(on) ? on.includes(i) : i === on) ? ' class="on"' : ''}>${o}</span>`).join('')}</div></div>`,
  sl: (name, f, v, id) => `<div class="sr"${id ? ` id="${id}"` : ''}><span>${name}</span><div class="sl"><b style="width:${Math.round(f * 100)}%"></b></div><span class="v">${v}</span></div>`,
  sls: (name, f, v, id) => `<div class="srs"${id ? ` id="${id}"` : ''}><div class="hd"><span>${name}</span><span class="v">${v}</span></div><div class="sl"><b style="width:${Math.round(f * 100)}%"></b></div></div>`,
  t: s => `<div class="t">${s}</div>`,
  sub: s => `<div class="sb">${s}</div>`,
  hint: s => `<div class="whint">${s}</div>`,
  div: () => '<div class="wdiv"></div>',
  btn: (s, id, glyph) => `<div class="wrow"><span class="wbtn"${id ? ` id="${id}"` : ''}>${glyph ? `<span class="g">${glyph}</span>` : ''}${s}</span></div>`,
  pick: (title, value, hint, id) => `<div class="bp"${id ? ` id="${id}"` : ''}><span>${title}</span><span><span class="wbtn">${value}</span></span></div>${hint ? `<div class="whint">${hint}</div>` : ''}`,
  card: (title, body, id) => `<div class="wcard"${id ? ` id="${id}"` : ''}>${title ? `<div class="ct">${title}</div>` : ''}${body}</div>`,
  in: s => `<div class="in">${s}</div>`,
  swatches: list => `<div class="ovsw">${list.map(([n, c]) => `<span><i style="background:${c}"></i>${n}</span>`).join('')}</div>`,
};
// The picked overlay's column: its label box, the tabs its kind shows, then the tab's cards.
function ovCol(stage, label, tabs, on, body, width = 700) {
  if (!document.getElementById('pcstyle')) document.head.insertAdjacentHTML('beforeend', PC_STYLE.replace('<style>', '<style id="pcstyle">'));
  if (!document.getElementById('ovstyle')) document.head.insertAdjacentHTML('beforeend', OV_STYLE);
  return el(stage, `<div class="win ovw" style="--w:${width}px"><div class="tbar">GyroPhone · Overlays<span class="wc"><span>—</span><span>☐</span><span>✕</span></span></div>
  <div class="main"><span class="lbl">${label}</span>
  <div class="tabs">${tabs.map(t => `<span${t === on ? ' class="on"' : ''}>${t}</span>`).join('')}</div>${body}</div></div>`);
}
// A dot right after an element's text, for a mark's leader to end on.
function textBox(stage, e) {
  const r = document.createRange(); r.selectNodeContents(e); const b = r.getBoundingClientRect(), o = stage.getBoundingClientRect();
  return { x: b.left - o.left, y: b.top - o.top, w: b.width + 8, h: b.height };
}
// An overlay colour (0xAARRGGBB, as OverlaySettings keeps it) as CSS.
const argb = n => `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${(((n >>> 24) & 255) / 255).toFixed(2)})`;
