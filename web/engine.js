// Pure logic shared by the web app and its tests. Ported from the Android app (Model.kt / Outfit.kt).

export const Cat = {
  TOP: '上装', BOTTOM: '下装', DRESS: '连衣裙', OUTER: '外套', SHOES: '鞋子', BAG: '包', ACC: '配饰',
};
Cat.ALL = [Cat.TOP, Cat.BOTTOM, Cat.DRESS, Cat.OUTER, Cat.SHOES, Cat.BAG, Cat.ACC];

const CAT_RULES = [
  [['半身裙', '短裙', '长裙', '百褶裙', '裤', '下装'], Cat.BOTTOM],
  [['连衣裙', '裙'], Cat.DRESS],
  [['外套', '夹克', '大衣', '风衣', '羽绒', '西装', '开衫', '棉服', '冲锋衣', '马甲'], Cat.OUTER],
  [['鞋', '靴'], Cat.SHOES],
  [['包'], Cat.BAG],
  [['帽', '围巾', '腰带', '皮带', '项链', '耳', '手链', '戒指', '袜', '手套', '眼镜', '配饰', '饰'], Cat.ACC],
];

export function normalizeCat(raw) {
  const s = String(raw ?? '').trim();
  if (Cat.ALL.includes(s)) return s;
  for (const [keys, cat] of CAT_RULES) if (keys.some(k => s.includes(k))) return cat;
  return Cat.TOP;
}

export const WARMTH = ['薄', '适中', '厚'];
export const warmthLabel = w => WARMTH[Math.min(3, Math.max(1, w | 0 || 2)) - 1];
export const OCCASIONS = ['日常', '通勤', '约会', '运动', '正式'];

export const COLORS = [
  ['黑', '#222222', true], ['白', '#F2F0EA', true], ['灰', '#9A9A9A', true], ['米', '#E6D9C0', true],
  ['卡其', '#C3A982', true], ['驼', '#B38B5D', true], ['棕', '#7A5233', true], ['藏青', '#22304A', true],
  ['牛仔蓝', '#5B7BA0', true], ['蓝', '#3F6FD0', false], ['绿', '#4F7D57', false], ['红', '#B83A3A', false],
  ['粉', '#E3A1B4', false], ['黄', '#E2C24A', false], ['橙', '#E0823A', false], ['紫', '#7D5BA6', false],
  ['花色', '#B0A090', false],
].map(([name, hex, neutral]) => ({ name, hex, neutral }));
export const COLOR_NAMES = COLORS.map(c => c.name);
export const colorOf = name => COLORS.find(c => c.name === name) ?? COLORS[2];

const COLOR_RULES = [
  [['花', '格', '条纹', '印花', '拼色', '波点', '迷彩'], '花色'],
  [['米', '奶', '杏'], '米'],
  [['藏青', '深蓝', '海军', '藏蓝'], '藏青'],
  [['牛仔', '丹宁'], '牛仔蓝'],
  [['卡其'], '卡其'], [['驼'], '驼'], [['咖', '棕', '褐', '巧克力'], '棕'],
  [['灰'], '灰'], [['黑'], '黑'], [['白'], '白'], [['蓝'], '蓝'], [['绿'], '绿'],
  [['粉'], '粉'], [['红'], '红'], [['黄'], '黄'], [['橙', '橘'], '橙'], [['紫'], '紫'],
];

export function normalizeColor(raw) {
  const s = String(raw ?? '').trim();
  if (COLOR_NAMES.includes(s)) return s;
  for (const [keys, c] of COLOR_RULES) if (keys.some(k => s.includes(k))) return c;
  return '灰';
}

export const WEATHER = [
  { id: 'HOT', label: '炎热', hint: '28° 以上' },
  { id: 'WARM', label: '温暖', hint: '20–28°' },
  { id: 'COOL', label: '凉爽', hint: '10–20°' },
  { id: 'COLD', label: '寒冷', hint: '10° 以下' },
];
export const weatherOf = id => WEATHER.find(w => w.id === id) ?? WEATHER[1];

/** Deterministic PRNG for tests (mulberry32). */
export function seeded(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const DAY = 86400000;
/** 'YYYY-MM-DD' in local time. */
export function dayKey(d = new Date()) {
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
export function daysBetween(fromKey, toKey) {
  const [a, b] = [fromKey, toKey].map(k => { const [y, m, d] = k.split('-').map(Number); return Date.UTC(y, m - 1, d); });
  return Math.round((b - a) / DAY);
}

const BASE = new Set([Cat.TOP, Cat.BOTTOM, Cat.DRESS]);

/**
 * Every candidate gets weight = recency × warmth fit × occasion fit. Hard rules pick the slots (dress or
 * top+bottom, outer by weather, shoes/bag when owned); 24 random draws are scored by geometric mean of
 * weights × colour harmony and the best wins.
 *
 * @param items     wardrobe items
 * @param lastWorn  { [id]: 'YYYY-MM-DD' }
 * @param today     'YYYY-MM-DD'
 */
export class OutfitEngine {
  constructor(items, lastWorn, today, rnd = Math.random) {
    this.items = items; this.lastWorn = lastWorn; this.today = today; this.rnd = rnd;
  }

  daysSince(id) { const d = this.lastWorn[id]; return d ? daysBetween(d, this.today) : null; }

  recency(id) {
    const d = this.daysSince(id);
    if (d === null) return 1.3;
    if (d <= 0) return 0.03;
    if (d === 1) return 0.1;
    if (d <= 3) return 0.35;
    if (d <= 6) return 0.7;
    return 1.0 + Math.min(d, 60) / 120;
  }

  warmthFit(it, w) {
    const k = Math.min(3, Math.max(1, it.warmth | 0 || 2)) - 1;
    if (BASE.has(it.cat)) return { HOT: [1, 0.2, 0], WARM: [1, 0.8, 0.05], COOL: [0.6, 1, 0.5], COLD: [0.3, 0.9, 1] }[w][k];
    if (it.cat === Cat.OUTER) return { HOT: [0, 0, 0], WARM: [0, 0, 0], COOL: [1, 1, 0.3], COLD: [0.2, 0.7, 1] }[w][k];
    return 1;
  }

  occasionFit(it, occ) {
    if (occ === '运动' && it.cat === Cat.DRESS) return 0;
    if (!it.occasions?.length || it.occasions.includes(occ)) return 1;
    return 0.15;
  }

  weight(it, w, occ, avoid) {
    return this.recency(it.id) * this.warmthFit(it, w) * this.occasionFit(it, occ) * (avoid.has(it.id) ? 0.4 : 1);
  }

  pool(cat, w, occ, avoid, exclude = new Set()) {
    const all = this.items.filter(it => it.cat === cat && !exclude.has(it.id));
    if (!all.length) return null;
    const ws = all.map(it => this.weight(it, w, occ, avoid));
    if (ws.some(x => x > 0)) return { items: all, weights: ws, relaxed: false };
    if (occ === '运动' && cat === Cat.DRESS) return null;
    return { items: all, weights: all.map(it => this.recency(it.id) * 0.05), relaxed: true };
  }

  draw(p) {
    const total = p.weights.reduce((a, b) => a + b, 0);
    if (total <= 0) return null;
    let r = this.rnd() * total;
    for (let i = 0; i < p.items.length; i++) {
      r -= p.weights[i];
      if (r <= 0 && p.weights[i] > 0) return [p.items[i], p.weights[i]];
    }
    for (let i = p.items.length - 1; i >= 0; i--) if (p.weights[i] > 0) return [p.items[i], p.weights[i]];
    return null;
  }

  colorScore(pieces) {
    let loud = 0;
    const seen = new Set();
    for (const p of pieces) {
      if (p.cat === Cat.BAG || p.cat === Cat.ACC) continue;
      const c = colorOf(p.color);
      if (c.neutral || seen.has(c.name)) continue;
      seen.add(c.name);
      loud += c.name === '花色' ? 2 : 1;
    }
    return loud <= 1 ? 1 : loud === 2 ? 0.75 : loud === 3 ? 0.35 : 0.15;
  }

  /** Returns { pieces, reasons } or { missing: message }. [avoid]: ids currently on screen. */
  generate(w, occ, avoid = new Set()) {
    const tops = this.pool(Cat.TOP, w, occ, avoid);
    const bottoms = this.pool(Cat.BOTTOM, w, occ, avoid);
    const dresses = this.pool(Cat.DRESS, w, occ, avoid);
    const canSep = tops && bottoms;
    if (!canSep && !dresses) {
      const miss = !tops && !bottoms ? '衣橱里还没有上装和下装' : !tops ? '衣橱里还没有上装' : '衣橱里还没有下装';
      return { missing: `${miss}，先去「衣橱」导入几件吧` };
    }
    const outers = this.pool(Cat.OUTER, w, occ, avoid);
    const shoes = this.pool(Cat.SHOES, w, occ, avoid);
    const bags = this.pool(Cat.BAG, w, occ, avoid);

    let best = null, bestScore = -1;
    for (let n = 0; n < 24; n++) {
      const pick = [];
      const useDress = dresses && (!canSep || this.rnd() < 0.3);
      if (useDress) { const d = this.draw(dresses); if (!d) continue; pick.push(d); }
      else {
        const t = this.draw(tops), b = this.draw(bottoms);
        if (!t || !b) continue;
        pick.push(t, b);
      }
      const wantOuter = w === 'COLD' ? true : w === 'COOL' ? this.rnd() < 0.7 : false;
      if (wantOuter && outers && !outers.relaxed) { const o = this.draw(outers); if (o) pick.push(o); }
      if (shoes) { const s = this.draw(shoes); if (s) pick.push(s); }
      if (bags && this.rnd() < 0.6) { const b = this.draw(bags); if (b) pick.push(b); }
      const gm = Math.pow(pick.reduce((a, [, x]) => a * x, 1), 1 / pick.length);
      const score = gm * this.colorScore(pick.map(([it]) => it));
      if (score > bestScore) { bestScore = score; best = pick; }
    }
    if (!best) return { missing: '这次没搭出来，再点一次试试' };
    const pieces = best.map(([it]) => it);
    return { pieces, reasons: this.reasons(pieces, w, occ, [tops, bottoms, dresses].filter(Boolean)) };
  }

  /** Replace the piece at [index] with another of the same category; null when there is nothing else. */
  swap(outfit, index, w, occ) {
    const cur = outfit.pieces[index];
    if (!cur) return null;
    const p = this.pool(cur.cat, w, occ, new Set(), new Set([cur.id]));
    if (!p) return null;
    const d = this.draw(p);
    if (!d) return null;
    const pieces = outfit.pieces.slice();
    pieces[index] = d[0];
    return { pieces, reasons: this.reasons(pieces, w, occ, []) };
  }

  reasons(pieces, w, occ, basePools) {
    const out = [];
    const hasOuter = pieces.some(p => p.cat === Cat.OUTER);
    const label = weatherOf(w).label;
    if (w === 'COLD') out.push(hasOuter ? '天冷，加了外套' : '天冷该加外套，但衣橱里还没有合适的外套');
    else if (w === 'COOL' && hasOuter) out.push('早晚凉，带件外套');
    else if (w === 'HOT') out.push('天热，优先薄款');
    for (const p of pieces) {
      if (!BASE.has(p.cat)) continue;
      if (this.warmthFit(p, w) === 0) out.push(`没有适合${label}天的${p.cat}，先用「${p.name}」凑合`);
      else if (this.occasionFit(p, occ) < 1) out.push(`「${p.name}」不太适合${occ}，衣橱里这类可选的少`);
    }
    if (basePools.some(p => p.relaxed) && !out.some(s => s.includes('凑合'))) out.push('合适的单品不多，有几件是凑合的');
    let notes = 0;
    for (const p of pieces) {
      if (notes >= 2) break;
      const d = this.daysSince(p.id);
      if (d === null) { out.push(`「${p.name}」还没穿过`); notes++; }
      else if (d >= 21) { out.push(`「${p.name}」已经 ${d} 天没穿了`); notes++; }
    }
    return out;
  }
}
