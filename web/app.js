import {
  Cat, OCCASIONS, WARMTH, WEATHER, COLORS, colorOf, weatherOf, warmthLabel, OutfitEngine, dayKey, daysBetween, normalizeColor,
} from './engine.js';
import { PROVIDERS, providerOf, missingOf, modelOf, recognize, recognizeFortune, testConnection, chat, VisionError } from './vision.js';
import { computeFortune } from './fortune.js';
import { computeDaily, loadLibs, themeOf } from './daily.js';
import { CITIES, cityOf } from './cities.js';
import { isAndroid, saveFile, legacyBackup, legacyDone } from './native.js';
import { store, imageUrl, putImage, revoke, exportBackup, importBackup, sweepImages } from './db.js';
import { decode, toDataUrl, toBlob, crop } from './images.js';

// =============================== helpers ===============================

/** h('div.a.b', {onclick, style, ...}, ...children). Text children are inserted as text, never as HTML. */
function h(sel, attrs, ...kids) {
  const [tag, ...cls] = sel.split('.');
  const el = document.createElement(tag || 'div');
  if (cls.length) el.className = cls.join(' ');
  if (attrs && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) { kids.unshift(attrs); attrs = null; }
  for (const [k, v] of Object.entries(attrs ?? {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'style') Object.assign(el.style, v);
    else if (k === 'class') el.className += ' ' + v;
    else if (k in el && k !== 'list') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  const add = k => { if (k == null || k === false) return; if (Array.isArray(k)) k.forEach(add); else el.append(k instanceof Node ? k : String(k)); };
  kids.forEach(add);
  return el;
}
const VERSION = 'dev'; // replaced with the commit id at deploy
const $ = id => document.getElementById(id);
/** replaceChildren that skips null/false (plain replaceChildren would print them as text). */
const put = (el, ...kids) => el.replaceChildren(...kids.flat(Infinity).filter(k => k != null && k !== false));
const uid = () => (crypto.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`);
const today = () => dayKey(new Date());

let toastTimer;
function toast(msg) {
  const t = $('toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 2000);
}

const settings = (() => {
  const KEY = 'closet.settings';
  let s;
  try { s = JSON.parse(localStorage.getItem(KEY) || '{}'); } catch { s = {}; }
  s.provider ??= 'DOUBAO'; s.key ??= {}; s.model ??= {}; s.endpoint ??= {};
  s.weather ??= 'WARM'; s.occasion ??= '日常';
  s.autoFortune ??= true;
  s.name ??= '';
  // Birth profile; older versions only stored a birthday.
  s.birth ??= { date: s.birthday ?? '', time: '', city: '北京' };
  s.birth.gender ??= '';
  delete s.birthday;
  if (!OCCASIONS.includes(s.occasion)) s.occasion = '日常';
  s.save = () => { try { localStorage.setItem(KEY, JSON.stringify({ ...s, save: undefined })); } catch { /* private mode */ } };
  return s;
})();

/** Bottom sheet with options [{title, note}]; resolves with the picked index (or nothing). */
function sheet(title, options, selected, onPick) {
  const close = () => scrim.remove();
  const scrim = h('div.scrim', { onclick: e => { if (e.target === scrim) close(); } },
    h('div.sheet',
      h('div.eyebrow', { style: { margin: '6px 0 6px' } }, title),
      options.map((o, i) => h('button.opt', { onclick: () => { close(); if (i !== selected) onPick(i); } },
        h('span', { style: { fontWeight: i === selected ? 500 : 400 } }, o.title),
        o.note ? h('span.sub.small', { style: { marginLeft: '10px' } }, o.note) : null,
        i === selected ? h('span.dot') : null))));
  document.body.append(scrim);
}

function confirmBox(title, msg, okLabel = '确定', danger = false) {
  return new Promise(resolve => {
    const done = v => { scrim.remove(); resolve(v); };
    const scrim = h('div.scrim.center', { onclick: e => { if (e.target === scrim) done(false); } },
      h('div.modal',
        h('div', { style: { fontSize: '17px', fontWeight: 500 } }, title),
        msg ? h('div.sub', { style: { fontSize: '14px', marginTop: '8px', lineHeight: 1.6 } }, msg) : null,
        h('div.btns',
          h('button.link.sub', { onclick: () => done(false) }, '取消'),
          h('button.link', { class: danger ? 'warn' : '', onclick: () => done(true) }, okLabel))));
    document.body.append(scrim);
  });
}

/** Garment on its backdrop. [src] is a URL, a Promise of one, or null (colour swatch). */
function tile(item, src, style = {}) {
  const t = h('div.tile', { style }, h('span.swatch', { style: { background: colorOf(item.color).hex } }));
  Promise.resolve(src).then(u => {
    if (!u) return;
    const img = h('img', { alt: '', src: u, onload: () => { t.replaceChildren(img); } });
  });
  return t;
}
const itemImage = it => (it.img ? imageUrl(it.id) : null);

function chipsSingle(options, value, render = o => o, onChange = () => {}) {
  let cur = value;
  const box = h('div.chips');
  const draw = () => put(box, options.map(o =>
    h('button.chip', { class: o === cur ? 'on' : '', onclick: () => { cur = o; draw(); onChange(o); } }, render(o))));
  draw();
  return { el: box, get: () => cur };
}
function chipsMulti(options, values, render = o => o) {
  const cur = new Set(values);
  const box = h('div.chips');
  const draw = () => put(box, options.map(o =>
    h('button.chip', { class: cur.has(o) ? 'on' : '', onclick: () => { cur.has(o) ? cur.delete(o) : cur.add(o); draw(); } }, render(o))));
  draw();
  return { el: box, get: () => options.filter(o => cur.has(o)) };
}

/** Item editor as a bottom sheet. [extras]: [{label, danger, run}] shown under the photo. */
function editor(title, f, src, extras, onSave) {
  const close = () => scrim.remove();
  const name = h('input', { value: f.name, maxLength: 20, autocomplete: 'off' });
  const material = h('input', { value: f.material ?? '', maxLength: 12, placeholder: '例如 粉晶、白水晶、黄金、925银、珍珠', autocomplete: 'off' });
  const materialField = h('label.field', h('span', '材质 / 宝石'), material);
  const cat = chipsSingle(Cat.ALL, f.cat, o => o, v => { materialField.hidden = v !== Cat.JEWEL; });
  materialField.hidden = f.cat !== Cat.JEWEL;
  const color = chipsSingle(COLORS.map(c => c.name), f.color, n => [h('i.d', { style: { background: colorOf(n).hex } }), n]);
  const warm = chipsSingle(WARMTH, warmthLabel(f.warmth));
  const occ = chipsMulti(OCCASIONS, f.occasions ?? []);
  const scrim = h('div.scrim', { onclick: e => { if (e.target === scrim) close(); } },
    h('div.sheet',
      h('div.row', h('div.grow', { style: { fontSize: '17px', fontWeight: 500 } }, title),
        h('button.link.sub', { onclick: close }, '取消'),
        h('button.link', { style: { marginLeft: '20px' }, onclick: () => {
          close();
          onSave({ name: name.value.trim() || f.name, cat: cat.get(), color: color.get(),
            warmth: WARMTH.indexOf(warm.get()) + 1, occasions: occ.get(),
            material: cat.get() === Cat.JEWEL ? material.value.trim() : '' });
        } }, '保存')),
      src ? tile(f, src, { height: '170px', marginTop: '12px' }) : null,
      extras?.length ? h('div.row', { style: { gap: '22px' } },
        extras.map(x => h('button.link.sm', { class: x.danger ? 'warn' : '', onclick: () => { close(); x.run(); } }, x.label))) : null,
      h('label.field', h('span', '名称'), name),
      h('div.lbl', '类别'), cat.el, materialField,
      h('div.lbl', '颜色'), color.el,
      h('div.lbl', '厚度'), warm.el,
      h('div.lbl', '适合场合 · 不选 = 都行'), occ.el,
      h('div', { style: { height: '10px' } })));
  document.body.append(scrim);
}

// =============================== navigation ===============================

let page = 'today';
function show(p) {
  page = p;
  for (const id of ['today', 'closet', 'settings']) $(`p-${id}`).hidden = id !== p;
  document.querySelectorAll('nav.tabs-bottom button').forEach(b => b.classList.toggle('on', b.dataset.p === p));
  ({ today: renderToday, closet: renderCloset, settings: renderSettings })[p]();
}
document.querySelectorAll('nav.tabs-bottom button').forEach(b => b.addEventListener('click', () => show(b.dataset.p)));

// =============================== 今天 ===============================

let outfit = null; // { pieces, reasons }
let outfitDay = today(); // the app can stay open past midnight
const engine = () => new OutfitEngine(store.items, store.lastWorn(), today());

function regenerate() {
  const avoid = new Set(outfit?.pieces.map(p => p.id) ?? []);
  const r = engine().generate(settings.weather, settings.occasion, avoid, fortuneForOutfit());
  outfit = r.missing ? null : r;
  renderToday(r.missing);
}

/** Keeps the outfit on screen in sync with edits/deletes, or restores today's recorded one. */
function syncOutfit() {
  const byId = new Map(store.items.map(i => [i.id, i]));
  if (outfit) {
    const fresh = outfit.pieces.map(p => byId.get(p.id));
    if (fresh.every(Boolean)) { outfit = { ...outfit, pieces: fresh }; return true; }
    outfit = null;
  }
  const rec = store.wornOn(today());
  if (rec) {
    const pieces = rec.map(id => byId.get(id)).filter(Boolean);
    if (pieces.length) { outfit = { pieces, reasons: [] }; return true; }
  }
  return false;
}

const RATIO = { [Cat.DRESS]: 1.45, [Cat.OUTER]: 1.25, [Cat.BOTTOM]: 1.0, [Cat.TOP]: 0.8, [Cat.BAG]: 0.62 };
const ORDER = [Cat.OUTER, Cat.DRESS, Cat.TOP, Cat.BOTTOM, Cat.SHOES, Cat.BAG, Cat.JEWEL, Cat.ACC];

/** 「早上好，Felix」 with a name, otherwise the plain title. */
function greeting(d = new Date()) {
  const n = (settings.name || '').trim();
  if (!n) return '今天穿什么';
  const hr = d.getHours();
  return `${hr < 5 ? '夜深了' : hr < 11 ? '早上好' : hr < 13 ? '中午好' : hr < 18 ? '下午好' : '晚上好'}，${n}`;
}

function renderToday(missing) {
  if (outfitDay !== today()) { outfitDay = today(); outfit = null; }
  if (!missing && !outfit && !syncOutfit()) {
    const r = engine().generate(settings.weather, settings.occasion, new Set(), fortuneForOutfit());
    if (r.missing) missing = r.missing; else outfit = r;
  }
  const d = new Date();
  const w = weatherOf(settings.weather);
  const head = [
    h('div.eyebrow', `${d.toLocaleDateString('en-US', { weekday: 'long' })}  ·  ${d.toLocaleDateString('en-US', { month: 'long' })}`),
    h('div.row', { style: { alignItems: 'flex-end', gap: '14px', marginTop: '4px' } },
      h('div.num', String(d.getDate()).padStart(2, '0')),
      h('div.serif.ell', { style: { fontSize: '24px', paddingBottom: '6px', minWidth: 0 } }, greeting())),
    h('div.selectors',
      h('button', { onclick: () => sheet('今天天气', WEATHER.map(x => ({ title: x.label, note: x.hint })), WEATHER.indexOf(w),
        i => { settings.weather = WEATHER[i].id; settings.save(); regenerate(); }) },
        h('div.eyebrow', { style: { fontSize: '10px' } }, '天气'), h('div.v.ell', `${w.label} ${w.hint} `, h('span.sub.small', '▾'))),
      h('button', { onclick: () => sheet('今天的场合', OCCASIONS.map(o => ({ title: o })), OCCASIONS.indexOf(settings.occasion),
        i => { settings.occasion = OCCASIONS[i]; settings.save(); regenerate(); }) },
        h('div.eyebrow', { style: { fontSize: '10px' } }, '场合'), h('div.v.ell', `${settings.occasion} `, h('span.sub.small', '▾')))),
    fortuneRow(),
  ];
  const body = [];
  if (missing || !outfit) {
    body.push(h('div.empty', { style: { paddingTop: '50px' } },
      h('div', { style: { fontSize: '15px' } }, missing ?? '还搭不出来'),
      h('button.link', { style: { marginTop: '10px' }, onclick: () => show('closet') }, '去导入')));
  } else {
    // Two-column masonry: biggest first, each into the shorter column.
    const cols = [h('div'), h('div')], hgt = [0, 0];
    outfit.pieces.map((p, i) => [p, i]).sort((a, b) => ORDER.indexOf(a[0].cat) - ORDER.indexOf(b[0].cat)).forEach(([p, i]) => {
      const r = RATIO[p.cat] ?? 0.5;
      const k = hgt[1] < hgt[0] - 0.05 ? 1 : 0;
      hgt[k] += r + 0.3;
      cols[k].append(h('button.piece', { onclick: () => swapAt(i) },
        tile(p, itemImage(p), { aspectRatio: `1 / ${r}` }),
        h('div.eyebrow', p.cat), h('div.nm.ell', p.name)));
    });
    const rec = store.wornOn(today());
    const ids = outfit.pieces.map(p => p.id);
    const isRec = !!rec && rec.length === ids.length && ids.every(id => rec.includes(id));
    body.push(
      h('div.masonry', cols),
      outfit.reasons.length ? h('div.reasons', outfit.reasons.map(r => h('div', `—  ${r}`))) : null,
      h('div.actions',
        h('button.link', { onclick: regenerate }, '换一套'),
        isRec ? h('button.pill.ghost', { onclick: recordWear }, '撤销记录') : h('button.pill', { onclick: recordWear }, '就穿这套')),
      isRec ? h('div.small.accent', { style: { textAlign: 'right', marginTop: '10px' } }, '✓ 已记下今天穿这套')
        : rec ? h('div.small.sub', { style: { textAlign: 'right', marginTop: '10px' } }, '今天已记录过另一套，点「就穿这套」会替换') : null,
      h('div.tiny.sub', { style: { textAlign: 'center', marginTop: '20px' } }, '点单品，可以只换那一件'));
  }
  put($('p-today'), ...head, ...body);
}

// ---------------- 今日运势 ----------------

const STD_COLORS = COLORS.map(c => c.name).filter(c => c !== '花色');
const dot = c => h('i', { style: { display: 'inline-block', width: '10px', height: '10px', borderRadius: '5px', background: colorOf(c).hex,
  boxShadow: 'inset 0 0 0 1px rgba(128,128,128,.35)', marginRight: '5px', verticalAlign: '-1px' } });

/** The fortune in effect today: one imported/entered by hand wins, otherwise the computed one (if enabled). */
let libs = null; // lunar + astronomy, loaded once a birth date is set
let libsState = 'idle'; // idle | loading | ok | fail
const PENDING = { pending: true };
const fortuneForOutfit = () => { const f = todayFortune(); return f?.pending ? null : f; };
/**
 * With a birth date the fortune needs the chart libraries. While they load we show 「推算中」 instead of the
 * day-only rule, which gives a different colour and made the result look random. Only if they fail to load do
 * we fall back to it, and say so.
 */
function todayFortune() {
  const own = store.fortune(today());
  if (own || !settings.autoFortune) return own;
  if (settings.birth.date && !libs) {
    if (libsState !== 'fail') { ensureLibs(); return PENDING; }
    return { ...computeFortune(today(), settings.birth.date), simple: true };
  }
  try { return computeDaily(today(), settings.birth, libs); }
  catch { return { ...computeFortune(today(), settings.birth.date), simple: true }; }
}
/** Starts loading the chart libraries; when they arrive the outfit is redone with the real fortune. */
function ensureLibs() {
  if (libs || !settings.birth.date) return Promise.resolve();
  if (libsState !== 'loading') {
    libsState = 'loading';
    loadLibs().then(l => { libs = l; libsState = 'ok'; })
      .catch(() => { libsState = 'fail'; })
      .finally(() => { outfit = null; if (page === 'today') renderToday(); });
  }
  // The first screen waits at most ~4 s; past that it shows 「推算中」 and updates itself.
  return Promise.race([loadLibs().catch(() => {}), new Promise(r => setTimeout(r, 4000))]);
}

// 「建议 / 避免」 for the computed fortune, written by the model once per day and cached. Never blocks the page.
const AI_LINE_KEY = 'closet.aiAdvice2'; // v2: grounded in the lucky element's theme; old generic lines are dropped
let aiLineTried = '';
function aiAdvice(f) {
  const k = `${today()}|${JSON.stringify(settings.birth)}|${f.colors.join()}|${f.stones.join()}`;
  try { const c = JSON.parse(localStorage.getItem(AI_LINE_KEY) || 'null'); if (c?.k === k) return c.v; } catch { /* ignore */ }
  if (aiLineTried === k || missingOf(settings, providerOf(settings.provider))) return null;
  aiLineTried = k;
  // Only today's result (and gender, if set) goes out — never the pillars or chart, which would reveal the birth date/time.
  const theme = f.theme ?? (f.master && f.element ? themeOf(f.master.slice(-1), f.element) : null);
  const gender = { male: '男', female: '女' }[settings.birth.gender] ?? '未填';
  const payload = { 日期: today(), 今日: f.today, 日主: f.master, 幸运五行: f.element, 幸运五行对你是: theme?.god, 主管: theme?.about,
    幸运色: f.colors, 幸运配饰: f.stones, 性别: gender };
  chat(settings, `今日推算结果：${JSON.stringify(payload)}`, {
    system: [
      '你是运势小助手。根据今日推算结果，写今天的「建议」和「避免」，各两个 2-4 字的短语，用顿号隔开。',
      '规则：1) 建议必须是具体可做的小事，并且落在「主管」写的那类事情上（例如主管是表达、创作，就写“发条动态、写点东西”）。',
      '2) 避免是和建议相反的做法（例如建议表达，就避免“憋着不说、敷衍回复”），只写行为，不写颜色、衣服、饰品，不能和幸运色、幸运配饰矛盾。',
      '3) 性别只用来让措辞自然，不写刻板印象；性别未填就写中性的话。4) 语气轻松，不恐吓、不承诺效果，不要“多喝水、早点睡”这类每天都通用的话。',
      '只输出 JSON：{"suggest":"…","avoid":"…"}，不要其他文字。',
    ].join(''),
    maxTokens: 300, timeoutMs: 30000,
  }).then(t => {
    const o = JSON.parse(t.slice(t.indexOf('{'), t.lastIndexOf('}') + 1));
    const v = { suggest: String(o.suggest ?? '').trim().slice(0, 20), avoid: String(o.avoid ?? '').trim().slice(0, 20) };
    if (!v.suggest && !v.avoid) return;
    try { localStorage.setItem(AI_LINE_KEY, JSON.stringify({ k, v })); } catch { /* ignore */ }
    if (page === 'today') renderToday();
  }).catch(() => { aiLineTried = ''; /* tries again on the next render; the colour and accessory still stand */ });
  return null;
}

const colorName = c => (c.length === 1 ? `${c}色` : c);

function luckCell(icon, name, label) {
  return h('div', { style: { textAlign: 'center', minWidth: 0 } },
    icon, h('div.ell', { style: { fontSize: '14px', marginTop: '8px' } }, name), h('div.tiny.sub', { style: { marginTop: '2px' } }, label));
}

function fortuneRow() {
  const f = todayFortune();
  const actions = h('div.row', { style: { gap: '18px' } },
    h('button.link.sm', { onclick: importFortune }, '导入测测截图'),
    h('button.link.sm.sub', { onclick: () => fortuneSheet(f) }, f ? '手动改' : '手动填'));
  if (!f || f.pending) {
    return h('div.row', { style: { padding: '12px 0', borderBottom: '1px solid var(--line)' } },
      h('div.grow.sub.small', f ? '今日运势 · 按你的八字和星盘推算中…' : '今日运势'), actions);
  }
  const calc = f.source === 'calc';
  const adv = calc ? aiAdvice(f) : (f.suggest || f.avoidDo ? { suggest: f.suggest, avoid: f.avoidDo } : null);
  const backToCalc = !calc && settings.autoFortune ? h('button.link.sm.sub', { style: { paddingBottom: 0 }, onclick: async () => {
    await store.setFortune(today(), null); outfit = null; regenerate();
  } }, '改用五行推算') : null;
  const cells = [
    ...f.colors.slice(0, 2).map(c => luckCell(
      h('div', { style: { width: '40px', height: '40px', borderRadius: '10px', margin: '0 auto', background: colorOf(c).hex, boxShadow: 'inset 0 0 0 1px rgba(128,128,128,.25)' } }),
      colorName(c), '幸运色')),
    ...f.stones.slice(0, 2).map(st => luckCell(
      h('div', { style: { width: '28px', height: '28px', margin: '6px auto 6px', transform: 'rotate(45deg)', borderRadius: '5px',
        background: colorOf(normalizeColor(st)).hex, boxShadow: 'inset 0 0 0 1px rgba(128,128,128,.25)' } }),
      st, '幸运配饰')),
  ];
  return h('div', { style: { padding: '12px 0 14px', borderBottom: '1px solid var(--line)' } },
    h('div.row', h('div.eyebrow.grow', { style: { fontSize: '10px' } },
      calc ? `今日运势 · ${f.today}` : '今日运势 · 来自截图 / 手动'), actions),
    h('div', { style: { display: 'grid', gridTemplateColumns: `repeat(${Math.max(2, cells.length)}, 1fr)`, gap: '8px', marginTop: '14px' } }, cells),
    adv ? h('div', { style: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px', marginTop: '16px' } },
      h('div', h('div.tiny.sub', '建议'), h('div', { style: { fontSize: '14px', marginTop: '3px' } }, adv.suggest || '—')),
      h('div', h('div.tiny.sub', '避免'), h('div', { style: { fontSize: '14px', marginTop: '3px' } }, adv.avoid || '—'))) : null,
    f.summary ? h('div.tiny.sub', { style: { marginTop: '14px', lineHeight: 1.6 } }, f.summary) : null,
    f.baziLine ? h('div.tiny.sub', { style: { lineHeight: 1.6 } }, f.baziLine) : null,
    f.astroLine ? h('div.tiny.sub', { style: { lineHeight: 1.6 } }, f.astroLine) : null,
    backToCalc,
    f.simple ? h('div.tiny.warn', { style: { lineHeight: 1.6, marginTop: '6px' } }, '星盘数据没加载上，今天先用简化算法（只看日柱），颜色可能和平时不同。联网后重新打开即可。') : null,
    calc && !settings.birth.date ? h('button.link.sm.sub', { style: { paddingBottom: 0 }, onclick: () => show('settings') }, '填出生信息，按你的八字和星盘来算') : null);
}

function importFortune() {
  const miss = missingOf(settings, providerOf(settings.provider));
  if (miss) { toast(miss); return; }
  pickFiles($('pick1'), async ([file]) => {
    const status = h('div.status.busy', h('i'), h('span', '正在读运势截图…'));
    const scrim = h('div.scrim', h('div.sheet', status));
    document.body.append(scrim);
    try {
      const c = await decode(file);
      const f = await recognizeFortune(settings, toDataUrl(c));
      scrim.remove();
      fortuneSheet(f, true);
    } catch (e) {
      const ve = e instanceof VisionError;
      put(scrim.firstChild,
        h('div.status.warn', h('i'), h('span', ve ? e.message : `读取失败：${e.message ?? e}`)),
        ve && e.raw ? h('div.raw', `服务返回：${e.raw.slice(0, 300)}`) : null,
        h('div.row', { style: { gap: '22px' } },
          h('button.link', { onclick: () => { scrim.remove(); importFortune(); } }, '换一张'),
          h('button.link', { onclick: () => { scrim.remove(); fortuneSheet(null); } }, '手动填'),
          h('button.link.sub', { onclick: () => scrim.remove() }, '关闭')));
    }
  });
}

/** Review/edit today's fortune. [fresh]: just recognised, so the sheet says so. */
function fortuneSheet(f, fresh = false) {
  const close = () => scrim.remove();
  const swatch = n => [h('i.d', { style: { background: colorOf(n).hex } }), n];
  const colors = chipsMulti(STD_COLORS, f?.colors ?? [], swatch);
  const avoid = chipsMulti(STD_COLORS, f?.avoid ?? [], swatch);
  const stones = h('input', { value: (f?.stones ?? []).join('、'), placeholder: '例如 粉晶、黄金（用顿号或逗号隔开）', autocomplete: 'off' });
  const summary = h('input', { value: f?.summary ?? '', maxLength: 40, placeholder: '可不填', autocomplete: 'off' });
  const save = async () => {
    const nf = {
      colors: colors.get(), avoid: avoid.get().filter(c => !colors.get().includes(c)),
      stones: stones.value.split(/[、,，\s]+/).map(x => x.trim()).filter(Boolean).slice(0, 4),
      summary: summary.value.trim(), colorText: f?.colorText ?? [], source: 'manual',
      suggest: f?.suggest ?? '', avoidDo: f?.avoidDo ?? '',
    };
    close();
    await store.setFortune(today(), nf.colors.length || nf.stones.length ? nf : null);
    outfit = null;
    regenerate();
  };
  const scrim = h('div.scrim', { onclick: e => { if (e.target === scrim) close(); } },
    h('div.sheet',
      h('div.row', h('div.grow', { style: { fontSize: '17px', fontWeight: 500 } }, fresh ? '核对一下运势' : '今日运势'),
        h('button.link.sub', { onclick: close }, '取消'),
        h('button.link', { style: { marginLeft: '20px' }, onclick: save }, '保存')),
      fresh && f?.colorText?.length ? h('div.sub.small', { style: { marginTop: '6px' } }, `截图原文幸运色：${f.colorText.join('、')}`) : null,
      h('div.lbl', '幸运色 · 每套搭配都会带上'), colors.el,
      h('label.field', h('span', '推荐饰品 / 水晶'), stones),
      h('div.lbl', '忌讳的颜色'), avoid.el,
      h('label.field', h('span', '一句话提示'), summary),
      store.fortune(today()) ? h('button.link.sm.warn', { style: { marginTop: '14px' }, onclick: async () => {
        close(); await store.setFortune(today(), null); outfit = null; regenerate();
      } }, settings.autoFortune ? '清除，改回五行推算' : '清除今日运势') : null,
      h('div', { style: { height: '8px' } })));
  document.body.append(scrim);
}

function swapAt(i) {
  const n = engine().swap(outfit, i, settings.weather, settings.occasion, fortuneForOutfit());
  if (!n) { toast('这一类没有别的可换了'); return; }
  outfit = n;
  renderToday();
}

async function recordWear() {
  const ids = outfit.pieces.map(p => p.id);
  const rec = store.wornOn(today());
  const same = rec && rec.length === ids.length && ids.every(id => rec.includes(id));
  await store.setWorn(today(), same ? null : ids);
  toast(same ? '已撤销' : '记下了');
  renderToday();
}

// =============================== 衣橱 ===============================

let filter = null, idleFirst = false;

function renderCloset() {
  const all = store.items;
  const last = store.lastWorn();
  const counts = {};
  all.forEach(i => { counts[i.cat] = (counts[i.cat] ?? 0) + 1; });
  const cats = [null, ...Cat.ALL.filter(c => counts[c])];
  if (!cats.includes(filter)) filter = null;

  const head = h('div.row', { style: { alignItems: 'flex-end' } },
    h('div.grow', h('div.eyebrow', all.length ? `Wardrobe  ·  ${all.length} pieces` : 'Wardrobe'),
      h('div.serif.h1', { style: { marginTop: '6px' } }, '衣橱')),
    h('button.pill.ghost.sm', { onclick: askImport }, '＋ 导入'));

  if (!all.length) {
    put($('p-closet'), head, h('div.empty',
      h('div.serif', { style: { fontSize: '22px' } }, '衣橱还是空的'),
      h('div.sub', { style: { fontSize: '13px', lineHeight: 1.7, marginTop: '14px' } },
        '选几张购物订单截图，淘宝、京东、拼多多都行，或者直接拍衣服。AI 会认出每一件，分好类，裁出商品图。'),
      h('button.pill', { style: { width: '200px', marginTop: '28px' }, onclick: askImport }, '导入第一批')));
    return;
  }
  const tabs = h('div.tabbar', h('div.in', cats.map(c =>
    h('button', { class: c === filter ? 'on' : '', onclick: () => { filter = c; renderCloset(); } },
      c ?? '全部', h('sup', String(c ? counts[c] : all.length))))));
  const sortBtn = h('div', { style: { textAlign: 'right' } },
    h('button.sub.small', { style: { padding: '10px 0' }, onclick: () =>
      sheet('排序', [{ title: '最近添加' }, { title: '最久没穿', note: '闲置的排前面' }], idleFirst ? 1 : 0,
        i => { idleFirst = i === 1; renderCloset(); }) }, `${idleFirst ? '最久没穿' : '最近添加'}  ▾`));

  let list = all.filter(i => !filter || i.cat === filter);
  list = idleFirst
    ? list.slice().sort((a, b) => (last[a.id] ?? '0000') < (last[b.id] ?? '0000') ? -1 : (last[a.id] ?? '0000') > (last[b.id] ?? '0000') ? 1 : a.added - b.added)
    : list.slice().sort((a, b) => b.added - a.added);
  const t = today();
  const grid = h('div.grid', list.map(it => {
    const d = last[it.id] ? daysBetween(last[it.id], t) : null;
    return h('button.cell', { onclick: () => editItem(it) },
      tile(it, itemImage(it)),
      h('div.nm.ell', it.name),
      h('div.tiny', { class: d === null ? 'accent' : 'sub' }, d === null ? '●  没穿过' : d <= 0 ? '今天' : `${d} 天前`));
  }));
  put($('p-closet'), head, tabs, sortBtn, grid);
}

function editItem(it) {
  const n = store.wearCounts()[it.id] ?? 0;
  editor(n ? `穿过 ${n} 次` : '还没穿过', it, itemImage(it), [
    { label: '换照片', run: () => changePhoto(it) },
    { label: '删除', danger: true, run: async () => {
      if (!(await confirmBox(`删除「${it.name}」？`, '这件和它的照片会从衣橱里删除，无法恢复。', '删除', true))) return;
      await store.remove(it.id);
      if (outfit?.pieces.some(p => p.id === it.id)) outfit = null;
      renderCloset();
    } },
  ], async f => {
    await store.update({ ...it, ...f });
    syncOutfit();
    renderCloset();
  });
}

function changePhoto(it) {
  pickFiles($('pick1'), async ([file]) => {
    let c;
    try { c = await decode(file); } catch { toast('读不出这张图'); return; }
    const src = await toBlob(c, 0.9);
    const box = await cropScreen(src, c.width, c.height, [0, 0, c.width, c.height], '框出这件衣服');
    if (!box) return;
    await putImage(it.id, await toBlob(crop(c, box)));
    revoke(it.id);
    await store.update({ ...it, img: true });
    renderCloset();
  });
}

function pickFiles(input, cb) {
  input.value = '';
  input.onchange = () => { const fs = [...input.files]; if (fs.length) cb(fs); };
  input.click();
}

async function askImport() {
  const miss = missingOf(settings, providerOf(settings.provider));
  if (!miss) { pickFiles($('pick'), fs => openImport(fs.slice(0, 10), false)); return; }
  const scrim = h('div.scrim.center', h('div.modal',
    h('div', { style: { fontSize: '17px', fontWeight: 500 } }, '还不能自动识别'),
    h('div.sub', { style: { fontSize: '14px', marginTop: '8px', lineHeight: 1.6 } },
      `${miss}。自动识别需要一个能看图的模型（豆包、通义千问、智谱等）。也可以先手动添加：选照片，自己框出衣服、填信息。`),
    h('div.btns',
      h('button.link.sub', { onclick: () => scrim.remove() }, '取消'),
      h('button.link', { onclick: () => { scrim.remove(); pickFiles($('pick'), fs => openImport(fs.slice(0, 10), true)); } }, '手动添加'),
      h('button.link', { onclick: () => { scrim.remove(); show('settings'); } }, '去设置'))));
  document.body.append(scrim);
}

// =============================== 导入 ===============================

function openImport(files, manual) {
  const sections = files.map((file, index) => ({ index, file, src: null, w: 0, h: 0, drafts: [], state: 'queued', status: '排队中…', raw: '', note: '' }));
  let closed = false;
  const body = h('div.body');
  const summary = h('div.grow.sub.small');
  const save = h('button.pill', { style: { width: '170px' }, onclick: commit }, '入库');
  const screen = h('div.screen', body, h('div.bar', summary, save));
  document.body.append(screen);
  history.pushState({ screen: 'import' }, '');
  const onPop = () => { tryClose(true); };
  addEventListener('popstate', onPop);

  const selected = () => sections.reduce((n, s) => n + s.drafts.filter(d => d.selected).length, 0);

  async function tryClose(fromPop = false) {
    const n = selected();
    if (n && !(await confirmBox(`放弃这 ${n} 件？`, '还没入库，退出后识别结果不会保存。', '放弃', true))) {
      if (fromPop) history.pushState({ screen: 'import' }, '');
      return;
    }
    finish(fromPop);
  }
  function finish(fromPop) {
    closed = true;
    removeEventListener('popstate', onPop);
    sections.forEach(s => s.drafts.forEach(d => d.url && URL.revokeObjectURL(d.url)));
    screen.remove();
    if (!fromPop) history.back();
    show(page);
  }

  function render() {
    if (closed) return;
    const secs = sections.map(s => {
      const head = h('div.section-head',
        h('div.eyebrow', s.drafts.length && !manual ? `第 ${s.index + 1} 张 · 认出 ${s.drafts.length} 件` : `第 ${s.index + 1} 张`),
        h('div.row', { style: { gap: '16px' } },
          s.state === 'error' && s.retry ? h('button.link.sm', { onclick: () => run(s) }, '重试') : null,
          s.src ? h('button.link.sm', { onclick: () => byHand(s) }, '手动框一件') : null));
      const status = s.status ? h('div.status', { class: `${s.state === 'error' ? 'warn' : ''} ${s.state === 'busy' ? 'busy' : ''}` }, h('i'), h('span', s.status)) : null;
      const raw = s.raw ? h('div.raw', `服务返回：${s.raw.slice(0, 300)}`) : null;
      const rows = s.drafts.map(d => draftRow(s, d));
      return h('section', { style: { marginBottom: '6px' } }, head, status, raw, rows);
    });
    put(body,
      h('button.back', { onclick: () => tryClose() }, '←'),
      h('div.serif', { style: { fontSize: '28px', marginTop: '12px' } }, manual ? '手动添加' : '确认识别结果'),
      h('div.sub', { style: { fontSize: '13px', marginTop: '8px' } }, manual
        ? '每张图先当成一件衣服。点「调整框选」框出衣服，点一行填名称和类别。'
        : '逐张识别，一张大约 5–30 秒。点一行可修改名称和类别，没认出来的可以手动框。'),
      ...secs);
    const n = selected();
    const running = sections.filter(s => s.state === 'queued' || s.state === 'busy').length;
    summary.textContent = running ? `还有 ${running} 张在处理` : `已选 ${n} 件`;
    save.textContent = n ? `入库 ${n} 件` : '入库';
    save.disabled = !n;
  }

  function draftRow(s, d) {
    const meta = `${d.cat} · ${d.color} · ${warmthLabel(d.warmth)}${d.occasions.length ? ' · ' + d.occasions.join('/') : ''}`;
    return h('div.list-row', { class: d.selected ? '' : 'off', onclick: e => {
      if (e.target.closest('button')) return;
      editor('修改', d, d.url, [], f => { Object.assign(d, f); render(); });
    } },
      tile(d, d.url),
      h('div.grow',
        h('div', { style: { fontSize: '15px' } }, d.name),
        h('div.sub.small', { style: { marginTop: '4px' } }, meta),
        d.note ? h('div.warn', { style: { fontSize: '11px', marginTop: '4px' } }, d.note) : null,
        d.box ? h('button.link.sm', { style: { paddingBottom: 0 }, onclick: () => rebox(s, d) }, '调整框选')
          : h('button.link.sm.warn', { style: { paddingBottom: 0 }, onclick: () => rebox(s, d) }, '没框到商品图，点这里框选')),
      h('button.check', { class: d.selected ? 'on' : '', onclick: () => { d.selected = !d.selected; render(); } }, h('i', d.selected ? '✓' : '')));
  }

  async function setThumb(d, canvas) {
    d.blob = await toBlob(crop(canvas, d.box));
    if (d.url) URL.revokeObjectURL(d.url);
    d.url = URL.createObjectURL(d.blob);
  }

  async function srcCanvas(s) {
    const bmp = await createImageBitmap(s.src);
    const c = document.createElement('canvas');
    c.width = s.w; c.height = s.h;
    c.getContext('2d').drawImage(bmp, 0, 0, s.w, s.h);
    bmp.close?.();
    return c;
  }

  async function rebox(s, d) {
    const box = await cropScreen(s.src, s.w, s.h, d.box, `框出「${d.name}」的图片`);
    if (!box) return;
    d.box = box;
    await setThumb(d, await srcCanvas(s));
    render();
  }

  async function byHand(s) {
    const box = await cropScreen(s.src, s.w, s.h, null, '框出一件衣服');
    if (!box) return;
    const d = { name: '新单品', cat: Cat.TOP, color: '灰', warmth: 2, occasions: [], box, selected: true, note: '' };
    await setThumb(d, await srcCanvas(s));
    s.drafts.push(d);
    render();
    editor('这是什么？', d, d.url, [], f => { Object.assign(d, f); render(); });
  }

  function markDuplicates(list) {
    const names = new Set([...store.items.map(i => i.name), ...sections.flatMap(s => s.drafts.map(d => d.name))]);
    for (const d of list) {
      if (names.has(d.name)) { d.selected = false; d.note = '可能重复：已有同名单品，默认不入库'; }
      names.add(d.name);
    }
  }

  async function run(s) {
    s.state = 'busy'; s.status = manual ? '读取中…' : '识别中…'; s.raw = ''; render();
    let c;
    try {
      c = await decode(s.file);
      s.w = c.width; s.h = c.height;
      s.src ??= await toBlob(c, 0.9);
    } catch (e) {
      Object.assign(s, { state: 'error', status: `读不出这张图（${e.message ?? e}）`, retry: false }); render(); return;
    }
    try {
      if (manual) {
        const d = { name: '新单品', cat: Cat.TOP, color: '灰', warmth: 2, occasions: [], box: [0, 0, s.w, s.h], selected: true, note: '' };
        await setThumb(d, c);
        s.drafts.push(d);
        Object.assign(s, { state: 'done', status: '' });
      } else {
        const { items, note } = await recognize(settings, toDataUrl(c), s.w, s.h);
        const drafts = [];
        for (const it of items) {
          const d = { ...it, selected: true, note: '' };
          if (d.box) await setThumb(d, c);
          drafts.push(d);
        }
        markDuplicates(drafts);
        s.drafts.push(...drafts);
        Object.assign(s, { state: 'done', status: drafts.length ? '' : `没认出服饰${note ? '：' + note : ''}` });
      }
    } catch (e) {
      const ve = e instanceof VisionError;
      Object.assign(s, { state: 'error', status: ve ? e.message : `识别出错：${e.message ?? e}`, raw: ve ? e.raw : '', retry: true });
    }
    render();
  }

  async function commit() {
    const chosen = sections.flatMap(s => s.drafts).filter(d => d.selected);
    if (!chosen.length) return;
    save.disabled = true;
    const now = Date.now();
    const items = [];
    for (const [i, d] of chosen.entries()) {
      const id = uid();
      if (d.blob) await putImage(id, d.blob);
      items.push({ id, name: d.name, cat: d.cat, color: d.color, warmth: d.warmth, occasions: d.occasions,
        material: d.cat === Cat.JEWEL ? (d.material ?? '') : '', img: !!d.blob, added: now + i });
    }
    await store.add(items);
    navigator.storage?.persist?.().catch(() => {});
    toast(`已入库 ${items.length} 件`);
    page = 'closet';
    finish(false);
  }

  render();
  (async () => { for (const s of sections) { if (closed) return; await run(s); } })();
}

// =============================== 框选 ===============================

/** Full-screen box picker over [srcBlob] (w×h). Resolves with [l,t,r,b] in image pixels, or null. */
function cropScreen(srcBlob, w, hgt, init, title) {
  return new Promise(resolve => {
    const url = URL.createObjectURL(srcBlob);
    let box = init?.slice() ?? (() => {
      const s = Math.min(w, hgt) * 0.5, top = Math.min(hgt * 0.1, hgt - s);
      return [(w - s) / 2, top, (w + s) / 2, top + s];
    })();
    const img = h('img', { src: url, alt: '' });
    const boxEl = h('div.crop-box', [0, 1, 2, 3].map(c => h('b', { 'data-c': String(c) })));
    const wrap = h('div.crop-wrap', img, boxEl);
    const scroll = h('div.crop-scroll', wrap);
    const done = v => { URL.revokeObjectURL(url); screen.remove(); resolve(v); };
    const screen = h('div.screen', { style: { zIndex: 30 } },
      h('div', { style: { padding: 'calc(var(--safeT) + 14px) 22px 14px' } },
        h('div', { style: { fontSize: '18px', fontWeight: 500 } }, title),
        h('div.sub', { style: { fontSize: '13px', marginTop: '6px' } }, '拖四角调整大小，拖框移动；点图上别处，框会移过去。长图可以上下滑。')),
      scroll,
      h('div.bar', h('button.link', { onclick: () => done(null) }, '取消'), h('div.grow'),
        h('button.pill', { style: { width: '170px' }, onclick: () => done(box.map(Math.round)) }, '用这个框')));
    document.body.append(screen);

    const scale = () => wrap.clientWidth / w;
    const draw = () => {
      const k = scale();
      Object.assign(boxEl.style, { left: `${box[0] * k}px`, top: `${box[1] * k}px`, width: `${(box[2] - box[0]) * k}px`, height: `${(box[3] - box[1]) * k}px` });
    };
    img.onload = () => {
      draw();
      scroll.scrollTop = Math.max(0, ((box[1] + box[3]) / 2) * scale() - scroll.clientHeight / 2);
    };
    addEventListener('resize', draw);

    let mode = null, corner = 0, last = null, down = null;
    const MIN = 24;
    const pt = e => { const r = wrap.getBoundingClientRect(); return [(e.clientX - r.left) / scale(), (e.clientY - r.top) / scale()]; };
    wrap.addEventListener('pointerdown', e => {
      const c = e.target.closest('b');
      const p = pt(e);
      down = [e.clientX, e.clientY]; last = p;
      if (c) { mode = 'corner'; corner = +c.dataset.c; }
      else if (e.target === boxEl) mode = 'move';
      else { mode = 'tap'; return; } // let the page scroll; a cancel means it was a scroll
      wrap.setPointerCapture(e.pointerId);
      e.preventDefault();
    });
    wrap.addEventListener('pointermove', e => {
      if (mode !== 'move' && mode !== 'corner') return;
      const p = pt(e);
      const dx = p[0] - last[0], dy = p[1] - last[1];
      last = p;
      if (mode === 'move') {
        const ox = Math.min(Math.max(dx, -box[0]), w - box[2]);
        const oy = Math.min(Math.max(dy, -box[1]), hgt - box[3]);
        box = [box[0] + ox, box[1] + oy, box[2] + ox, box[3] + oy];
      } else {
        const L = corner === 0 || corner === 2, T = corner === 0 || corner === 1;
        if (L) box[0] = Math.min(Math.max(0, box[0] + dx), box[2] - MIN); else box[2] = Math.max(Math.min(w, box[2] + dx), box[0] + MIN);
        if (T) box[1] = Math.min(Math.max(0, box[1] + dy), box[3] - MIN); else box[3] = Math.max(Math.min(hgt, box[3] + dy), box[1] + MIN);
      }
      draw();
    });
    const end = e => {
      if (mode === 'tap' && e.type === 'pointerup' && Math.hypot(e.clientX - down[0], e.clientY - down[1]) < 8) {
        const [cx, cy] = pt(e);
        const hw = (box[2] - box[0]) / 2, hh = (box[3] - box[1]) / 2;
        const nx = Math.min(Math.max(cx, hw), w - hw), ny = Math.min(Math.max(cy, hh), hgt - hh);
        box = [nx - hw, ny - hh, nx + hw, ny + hh];
        draw();
      }
      mode = null;
    };
    wrap.addEventListener('pointerup', end);
    wrap.addEventListener('pointercancel', end);
  });
}

// =============================== 设置 ===============================

/** A second-level page over the current one. [build] returns its content and is called again on redraw(). */
function subScreen(title, build, onClose = () => {}) {
  const body = h('div.body');
  const screen = h('div.screen.push', body);
  const close = () => { screen.remove(); onClose(); };
  const redraw = () => {
    const y = body.scrollTop;
    put(body, h('button.back', { onclick: close }, '←'),
      h('div', { style: { fontSize: '22px', fontWeight: 500, margin: '8px 0 6px' } }, title), ...[build(redraw, close)].flat());
    body.scrollTop = y;
  };
  document.body.append(screen);
  redraw();
}

const cell = (label, value, onclick, right = h('span.chev', '›')) =>
  h('button.cell', { onclick }, h('span.grow', label), value ? h('span.v', value) : null, right);

/** "丁火日主 · 天蝎 · 东城区" — needs the chart libraries; without them only the place. */
function profileSummary() {
  const b = settings.birth;
  if (!b.date) return '填出生信息，按八字和星盘推算运势';
  const place = (b.place?.name ?? cityOf(b.city).name).split(' ').pop();
  let f = null;
  if (libs) { try { f = computeDaily(today(), b, libs); } catch { /* summary only */ } }
  return [f?.master && `${f.master}日主`, f?.sun, place].filter(Boolean).join(' · ');
}

function renderSettings() {
  const p = providerOf(settings.provider);
  const hasKey = !missingOf(settings, p);
  const name = settings.name || '';
  put($('p-settings'),
    h('div.eyebrow', 'Settings'),
    h('div.serif.h1', { style: { marginTop: '6px', marginBottom: '24px' } }, '设置'),

    h('button.me', { onclick: () => subScreen('个人资料', profilePage, renderSettings) },
      h('div.av', (name.trim()[0] || '我').toUpperCase()),
      h('div.grow', { style: { minWidth: 0 } },
        h('div.ell', { style: { fontSize: '18px', fontWeight: 500 } }, name || '点这里填名字'),
        h('div.ell.sub.small', { style: { marginTop: '4px' } }, profileSummary())),
      h('span.chev.sub', { style: { fontSize: '18px' } }, '›')),

    h('div.group', h('div.eyebrow', 'AI 模型'),
      cell('识别模型', `${p.label.replace(/（.*）/, '')} · ${hasKey ? '已填 Key' : '未填 Key'}`, () => subScreen('识别模型', modelPage, renderSettings))),

    h('div.group', h('div.eyebrow', '每日运势'),
      cell('自动推算每日运势', '', () => { settings.autoFortune = !settings.autoFortune; settings.save(); outfit = null; renderSettings(); },
        h('span.check', { class: settings.autoFortune ? 'on' : '', style: { width: '28px', height: '28px' } }, h('i', settings.autoFortune ? '✓' : ''))),
      cell('运势怎么算', '', () => subScreen('运势怎么算', fortuneHelpPage))),

    h('div.group', h('div.eyebrow', '数据'),
      cell('备份与恢复', `${store.items.length} 件`, () => subScreen('备份与恢复', dataPage, renderSettings))),

    h('div.group', h('div.eyebrow', '关于'),
      cell('版本', VERSION, () => subScreen('关于', aboutPage))),
  );
}

function profilePage(redraw) {
  const nameInput = h('input', { value: settings.name || '', maxLength: 12, placeholder: '例如 Felix', autocomplete: 'off', autocapitalize: 'off', spellcheck: false,
    oninput: () => { settings.name = nameInput.value.trim(); settings.save(); } });
  return [
    h('label.field', h('span', '用户名'), nameInput, h('small', '显示在设置和今天页的问候里，只存在这台设备上。')),
    h('div.eyebrow', { style: { marginTop: '40px' } }, '出生信息'),
    h('div.hair', { style: { marginTop: '8px' } }),
    birthFields(redraw),
  ];
}

function modelPage(redraw) {
  const p = providerOf(settings.provider);
  const fld = (label, key, opts = {}) => {
    const input = h('input', { type: opts.type ?? 'text', value: settings[key]?.[p.id] ?? '', placeholder: opts.placeholder ?? '',
      autocomplete: 'off', autocapitalize: 'off', spellcheck: false,
      oninput: () => { settings[key][p.id] = input.value.trim(); settings.save(); } });
    return h('label.field', h('span', label), input, opts.help ? h('small', opts.help) : null);
  };
  const testOut = h('div.small', { style: { marginTop: '6px', minHeight: '18px' } });
  return [
    h('div.field', h('span', '服务商')),
    h('div', h('button.cell', { style: { borderTop: '1px solid var(--line)' }, onclick: () =>
        sheet('识别用的模型', PROVIDERS.map(x => ({ title: x.label, note: x.overseas ? '需代理' : '' })), PROVIDERS.indexOf(p),
          i => { settings.provider = PROVIDERS[i].id; settings.save(); redraw(); }) },
        h('span.grow', p.label), h('span.sub', '▾')),
      h('div.tiny.sub', { style: { marginTop: '6px', lineHeight: 1.6 } }, (p.id === 'CUSTOM' ? '任何兼容 OpenAI 格式、能看图的接口都可以。' : p.overseas ? '海外服务，国内网络通常需要开代理。' : '国内可直连。')
        + ' 必须是能看图的模型，DeepSeek 不行。')),
    p.id === 'CUSTOM' ? fld('接口地址', 'endpoint', { placeholder: 'https://api.example.com/v1' }) : null,
    fld('API Key', 'key', { type: 'password', help: 'Key 只存在这台设备的浏览器里，不进备份文件。每个服务商的 Key 分开存。' }),
    fld('模型名', 'model', { placeholder: p.model || '必填', help: p.id === 'CUSTOM' ? '填该接口支持看图的模型名' : `留空使用默认：${p.model}` }),
    h('div.row', { style: { marginTop: '14px' } },
      h('button.link', { onclick: async () => {
        testOut.className = 'small sub'; testOut.textContent = '测试中…';
        try {
          const r = await testConnection(settings);
          testOut.className = 'small accent'; testOut.textContent = `✓ 连通了，模型回复：${r}`;
        } catch (e) {
          testOut.className = 'small warn'; testOut.textContent = e.message + (e.raw ? `\n${e.raw.slice(0, 200)}` : '');
          testOut.style.whiteSpace = 'pre-wrap';
        }
      } }, '测试连接')),
    testOut,
  ];
}

function fortuneHelpPage() {
  const para = t => h('div.small', { style: { lineHeight: 1.8, marginTop: '14px' } }, t);
  return [
    para('八字：按出生时间的真太阳时排四柱（按出生区县的经度校正，会自动处理 1986–1991 年夏令时），判断日主强弱后定喜用神；填了性别会加上当前大运。'),
    para('每天从喜用神里挑一个今天最得力的五行，幸运色和幸运配饰都从它来。星盘：看今天月亮所在星座和你的上升（不知道时辰就用太阳）合不合拍，用来在颜色里做取舍。'),
    para('导入测测截图或手动填写的当天，以那份为准。仅供娱乐。'),
    para('隐私：出生信息只存在这台设备上；生成「建议 / 避免」时，只把今天的幸运色、配饰、五行和性别发给模型，不发生日、时间和八字。'),
  ];
}

function dataPage(redraw) {
  const all = store.items, last = store.lastWorn(), t = today();
  const idle = all.filter(i => !last[i.id]).length;
  const stale = all.filter(i => last[i.id] && daysBetween(last[i.id], t) > 30).length;
  return [
    h('div', { style: { fontSize: '15px', marginTop: '16px', lineHeight: 1.7 } },
      all.length ? `共 ${all.length} 件，${idle} 件从没穿过，${stale} 件超过 30 天没穿。` : '衣橱还是空的。'),
    h('div.group',
      cell('导出备份', '', doExport),
      cell('导入备份', '', () => pickFiles($('pickBackup'), async ([f]) => {
        try { const n = await importBackup(f); toast(`已导入 ${n} 件`); outfit = null; redraw(); }
        catch (e) { toast(e.message); }
      }))),
    h('div.sub.small', { style: { marginTop: '14px', lineHeight: 1.7 } },
      '衣服照片和穿着记录只存在这台设备的浏览器里，不上传。导入时，截图会发给你选的模型服务做识别。备份里不含 API Key 和个人资料。清除浏览器数据会清空衣橱，记得定期导出备份。'),
  ];
}

function aboutPage() {
  const standalone = matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
  return [
    h('div.small', { style: { marginTop: '14px' } }, `版本 ${VERSION}`),
    h('div.small', { style: { marginTop: '14px', lineHeight: 1.7, padding: '12px 14px', background: 'var(--tile)', borderRadius: '6px' } },
      isAndroid ? '安卓版和网页版是同一套代码，功能一致；数据各存各的，可以用「备份与恢复」互相搬。'
        : standalone ? '已经是从主屏幕打开的。更新会在后台自动下载，切回 App 时生效；偶尔需要关掉再开一次。'
        : 'iPhone 上建议在 Safari 点「分享」→「添加到主屏幕」，像 App 一样打开。不添加的话，Safari 可能在你一段时间没打开后清掉数据。'),
  ];
}

function birthFields(redraw) {
  const b = settings.birth;
  const changed = () => { settings.save(); outfit = null; if (b.date) ensureLibs(); }; // no redraw here: it would close an open date/time picker
  const unknown = !b.time;
  const time = h('input', { type: 'time', value: b.time, disabled: unknown,
    onchange: e => { b.time = e.target.value; changed(); } });
  const gBtn = (v, label) => h('button.chip', { class: b.gender === v ? 'on' : '', onclick: () => { b.gender = b.gender === v ? '' : v; changed(); redraw(); } }, label);
  return h('div',
    h('div.field', h('span', '性别'),
      h('div.chips', gBtn('male', '男'), gBtn('female', '女')),
      h('small', '用来排大运（阳男阴女顺排）。不填就不算大运。')),
    h('label.field', h('span', '出生日期'),
      h('input', { type: 'date', value: b.date, max: today(), min: '1920-01-01', onchange: e => { b.date = e.target.value; changed(); redraw(); } })),
    h('label.field', h('span', '出生时间'), time),
    h('button.row', { style: { padding: '8px 0', gap: '8px' }, onclick: () => {
      b.time = b.time ? '' : '12:00'; changed(); redraw();
    } }, h('span.check', { class: unknown ? 'on' : '', style: { width: '28px', height: '28px' } }, h('i', unknown ? '✓' : '')),
      h('span.small', '不确定出生时间（不排时柱、不算上升）')),
    h('label.field', h('span', '出生地'),
      h('button', { style: { width: '100%', minHeight: '46px', border: '1px solid var(--line)', borderRadius: '6px', padding: '10px 12px', textAlign: 'left', fontSize: '16px' },
        onclick: () => regionPicker(place => { b.place = place; changed(); redraw(); }) },
        `${b.place?.name ?? cityOf(b.city).name}  ▾`),
      h('small', `${b.place?.approx ? '这个区县的坐标用的是所在城市的位置，误差一般在几分钟以内。' : ''}精确到区县，用来做真太阳时校正和算上升星座。出生信息只存在这台设备上，不发给模型。`)));
}

// ---------------- 出生地选择：省 → 市 → 区县，或直接搜 ----------------

let regionsP = null;
const loadRegions = () => (regionsP ??= fetch('regions.json').then(r => { if (!r.ok) throw new Error(r.status); return r.json(); })
  .catch(e => { regionsP = null; throw e; }));

/** Leaves are [name, lon, lat, approx?, tz?]; inner nodes are [name, children]. */
const isLeaf = n => typeof n[1] === 'number';
function leaves(tree, path = [], out = []) {
  for (const n of tree) isLeaf(n) ? out.push({ path: [...path, n[0]], n }) : leaves(n[1], [...path, n[0]], out);
  return out;
}

function regionPicker(onPick) {
  const body = h('div.body');
  const close = () => screen.remove();
  const search = h('input', { type: 'search', placeholder: '搜区县，例如 汶川、西湖区', autocomplete: 'off',
    style: { width: '100%', height: '42px', border: '1px solid var(--line)', borderRadius: '21px', padding: '0 16px', background: 'transparent', fontSize: '16px', outline: 'none' } });
  const list = h('div');
  const crumbs = h('div.row', { style: { flexWrap: 'wrap', gap: '6px', margin: '14px 0 4px' } });
  const screen = h('div.screen', body);
  body.append(h('button.back', { onclick: close }, '←'),
    h('div', { style: { fontSize: '20px', fontWeight: 500, margin: '8px 0 14px' } }, '出生地'), search, crumbs, list);
  document.body.append(screen);
  const pick = (path, n) => {
    close();
    onPick({ name: path.join(' '), lon: n[1], lat: n[2], approx: !!n[3], tz: n[4] || 'Asia/Shanghai' });
  };
  const row = (title, sub, onclick) => h('button.opt', { style: { display: 'flex', width: '100%', padding: '14px 0', borderBottom: '1px solid var(--line)', textAlign: 'left', fontSize: '16px' }, onclick },
    h('span.grow', title), sub ? h('span.sub.small', sub) : null);
  let tree = null, stack = [];
  const draw = () => {
    const q = search.value.trim();
    if (q) {
      const hits = leaves(tree).filter(x => x.path[x.path.length - 1].includes(q) || x.path.join('').includes(q)).slice(0, 60);
      put(crumbs);
      put(list, hits.length ? hits.map(x => row(x.path[x.path.length - 1], x.path.slice(0, -1).join(' '), () => pick(x.path, x.n)))
        : h('div.sub.small', { style: { padding: '20px 0' } }, '没找到，换个字试试，或者逐级选择'));
      return;
    }
    let nodes = tree;
    for (const i of stack) nodes = nodes[i][1];
    const path = []; let t = tree;
    for (const i of stack) { path.push(t[i][0]); t = t[i][1]; }
    put(crumbs, h('button.link.sm', { onclick: () => { stack = []; draw(); } }, '全部'),
      path.map((p, k) => [h('span.sub.small', '›'), h('button.link.sm', { onclick: () => { stack = stack.slice(0, k + 1); draw(); } }, p)]));
    put(list, nodes.map((n, i) => isLeaf(n)
      ? row(n[0], n[3] ? '近似坐标' : '', () => pick([...path, n[0]], n))
      : row(n[0], '›', () => { stack.push(i); draw(); body.scrollTop = 0; })));
  };
  search.addEventListener('input', draw);
  put(list, h('div.status.busy', h('i'), h('span', '加载地区列表…')));
  loadRegions().then(t => { tree = t; draw(); })
    .catch(() => put(list, h('div.status.warn', h('i'), h('span', '地区列表加载失败，请联网后重试'))));
}

async function doExport() {
  try {
    const blob = await exportBackup();
    const name = `衣橱备份-${today()}.json`;
    if (isAndroid) { saveFile(name, await blob.text()); return; }
    const file = new File([blob], name, { type: 'application/json' });
    if (navigator.canShare?.({ files: [file] })) {
      await navigator.share({ files: [file], title: name }).catch(() => {});
      return;
    }
    const a = h('a', { href: URL.createObjectURL(blob), download: name });
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 4000);
  } catch (e) { toast(`导出失败：${e.message ?? e}`); }
}

// =============================== Android ===============================

/** First run of the WebView build: bring over clothes, photos, wear history and API Keys from the old native app. */
async function migrateLegacy() {
  const json = legacyBackup();
  if (!json) return;
  try {
    const data = JSON.parse(json);
    const n = await importBackup({ text: async () => json });
    const s = data.settings ?? {};
    for (const k of ['key', 'model', 'endpoint']) {
      for (const [id, v] of Object.entries(s[k] ?? {})) if (!settings[k][id]) settings[k][id] = v; // never overwrite
    }
    if (s.provider && s.key?.[s.provider]) settings.provider = s.provider;
    if (s.weather) settings.weather = s.weather;
    if (OCCASIONS.includes(s.occasion)) settings.occasion = s.occasion;
    settings.save();
    legacyDone();
    if (n) toast(`已从旧版搬过来 ${n} 件`);
  } catch (e) {
    toast(`旧版数据没能搬过来，下次打开会再试：${e.message ?? e}`); // not marked done, so it retries
  }
}

/** Android back button: close the top sheet or page; otherwise go to 今天; false lets the app exit. */
window.closetBack = () => {
  // The topmost overlay is the last one in the DOM; use its own back/cancel button so its cleanup runs.
  const top = [...document.querySelectorAll('.scrim, .screen')].sort((a, b) => (+getComputedStyle(a).zIndex || 0) - (+getComputedStyle(b).zIndex || 0)).pop();
  if (top) {
    const btn = top.querySelector('.back') ?? [...top.querySelectorAll('button')].find(b => ['取消', '关闭'].includes(b.textContent.trim()));
    if (btn) btn.click(); else top.remove();
    return true;
  }
  if (page !== 'today' && store.items.length) { show('today'); return true; }
  return false;
};

// =============================== start ===============================

(async () => {
  try { await store.load(); }
  catch (e) {
    put(document.body, h('div', { style: { padding: '40px 24px' } },
      '打不开本地存储：' + (e.message ?? e) + '。如果在无痕模式，请换成普通模式打开。'));
    return;
  }
  sweepImages();
  await migrateLegacy();
  await ensureLibs();
  show(store.items.length ? 'today' : 'closet');
  if (!isAndroid && 'serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost')) {
    // A new version takes over in the background; reload once so it shows now, unless something is open.
    const hadController = !!navigator.serviceWorker.controller;
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (!hadController) return; // first install, nothing old on screen
      if (document.querySelector('.screen, .scrim')) toast('新版本已就绪，下次打开生效');
      else location.reload();
    });
    navigator.serviceWorker.register('sw.js', { updateViaCache: 'none' }).then(r => r.update()).catch(() => {});
  }
  // iPhone keeps a home-screen app alive in the background for days; opening it again only makes it visible,
  // nothing re-renders. So on every return (and at midnight while it is open) check whether the day changed.
  const shownDay = { v: today() };
  const onReturn = () => {
    if (document.visibilityState !== 'visible') return;
    navigator.serviceWorker?.getRegistration().then(r => r?.update()).catch(() => {});
    if (shownDay.v === today() || document.querySelector('.screen, .scrim')) return; // a sheet is open: next tick
    shownDay.v = today();
    if (page === 'today') renderToday(); // drops yesterday's outfit; other pages pick the new day up when shown
  };
  document.addEventListener('visibilitychange', onReturn);
  window.addEventListener('pageshow', onReturn);
  window.addEventListener('focus', onReturn);
  setInterval(onReturn, 60000);
})();
