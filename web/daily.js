// Today's fortune from the user's birth profile: 八字 (favourable elements) + 星盘 (today's Moon against the
// natal chart). Libraries are loaded lazily in the browser; tests pass them in directly.
import { computeFortune, dayPillar, ELEMENT_COLORS, ELEMENT_STONES } from './fortune.js';
import { cityOf } from './cities.js';
import { localToUtc, bazi, strength, daYun, natal, transits, genOf, genBy, ctrlOf, SIGNS, SIGN_COLORS, harmonious } from './birth.js';

const BRANCHES = '子丑寅卯辰巳午未申酉戌亥';
/** Rotate a list by the day number so repeated elements still give a different colour each day. */
export function rotate(list, today) {
  if (!list.length) return list;
  const [y, m, d] = today.split('-').map(Number);
  const k = Math.floor(Date.UTC(y, m - 1, d) / 86400000) % list.length;
  return [...list.slice(k), ...list.slice(0, k)];
}

let libsP = null;
/** Browser only: lunar-javascript is a UMD script (adds window.Solar), astronomy-engine is an ES module. */
export function loadLibs() {
  libsP ??= Promise.all([
    new Promise((resolve, reject) => {
      if (window.Solar) return resolve({ Solar: window.Solar });
      const s = document.createElement('script');
      s.src = 'vendor/lunar.js';
      s.onload = () => (window.Solar ? resolve({ Solar: window.Solar }) : reject(new Error('lunar 未加载')));
      s.onerror = () => reject(new Error('lunar 加载失败'));
      document.head.append(s);
    }),
    import('./vendor/astronomy.js'),
  ]).then(([lunar, A]) => ({ lunar, A })).catch(e => { libsP = null; throw e; });
  return libsP;
}

/** Birth analysis depends only on the profile; cache it. */
const cache = new Map();
function analyse(libs, p) {
  const key = `${p.date}|${p.time}|${JSON.stringify(p.place ?? p.city)}`;
  if (cache.has(key)) return cache.get(key);
  // A county picked from regions.json, or (older settings) one of the preset cities.
  const city = p.place?.lat != null ? { lat: p.place.lat, lon: p.place.lon, tz: p.place.tz || 'Asia/Shanghai' } : cityOf(p.city);
  const hasTime = /^\d{2}:\d{2}$/.test(p.time ?? '');
  const ms = localToUtc(p.date, hasTime ? p.time : '12:00', city.tz);
  const bz = bazi(libs.lunar, ms, city.lon, hasTime);
  const nat = natal(libs.A, ms, city.lat, city.lon, hasTime);
  const out = { city, hasTime, bz, nat };
  cache.set(key, out);
  return out;
}

/** What the lucky element is to the day master (十神 group), and what that area of life is about. */
export function themeOf(master, el) {
  return el === master ? { god: '比劫', about: '朋友、同伴、合作' }
    : el === genBy(master) ? { god: '印星', about: '学习、长辈贵人、休整充电' }
    : el === genOf(master) ? { god: '食伤', about: '表达、创作、展示自己' }
    : el === ctrlOf(master) ? { god: '财星', about: '理财、务实做事、收获' }
    : { god: '官杀', about: '规则、责任、工作推进' };
}

/**
 * [profile] = { date:'YYYY-MM-DD', time:'HH:MM'|'', place|city, gender:'male'|'female'|'' }. Without libs or a date, falls back to the
 * day-only five-element rule. Returns the same shape as computeFortune plus baziLine / astroLine.
 */
export function computeDaily(today, profile, libs, tz = Intl.DateTimeFormat().resolvedOptions().timeZone) {
  if (!profile?.date || !libs) return computeFortune(today, profile?.date ?? '');
  const { hasTime, bz, nat } = analyse(libs, profile);
  const luck = daYun(bz, profile.gender, +today.slice(0, 4));
  const st = strength(bz, luck);
  const day = dayPillar(today);
  const D = day.element;

  // 星盘: today's Moon against the ascendant (or Sun without a birth time).
  const tr = transits(libs.A, localToUtc(today, '12:00', tz));
  const keyIdx = nat.asc ?? nat.sun;
  const keyName = `${nat.asc != null ? '上升' : '太阳'}${SIGNS[keyIdx]}`;
  const ok = harmonious(tr.moon, keyIdx);
  const astroColors = ok ? SIGN_COLORS[tr.moon] : SIGN_COLORS[nat.venus];
  const astroWhy = ok ? `今日月亮在${SIGNS[tr.moon]}，和你的${keyName}合拍`
    : `今日月亮在${SIGNS[tr.moon]}，和你的${keyName}不太合拍`;

  // Like 测测: one lucky element a day, and everything else follows from it.
  // Among the favourable elements, the one today's pillar strengthens most wins (same element 3, today feeds
  // it 2, neutral 1, it feeds today 0, today controls it -1); the chart breaks ties.
  const boost = e => (e === D ? 3 : genOf(D) === e ? 2 : genOf(e) === D ? 0 : ctrlOf(D) === e ? -1 : 1);
  const chartHit = e => (ELEMENT_COLORS[e].some(c => astroColors.includes(c)) ? 0.5 : 0);
  const lucky = st.favorable.slice().sort((a, b) => (boost(b) + chartHit(b)) - (boost(a) + chartHit(a)))[0];
  const agreed = ELEMENT_COLORS[lucky].filter(c => astroColors.includes(c));
  const color = rotate(agreed.length ? agreed : ELEMENT_COLORS[lucky], today)[0];
  const stone = rotate(ELEMENT_STONES[lucky], today)[0];

  const pillars = bz.pillars.map(p => p.name).join(' ');
  const luckText = !luck ? '' : luck.name ? ` · 大运${luck.name}` : ` · ${luck.startAge}岁起运`;
  return {
    source: 'calc', colors: [color], avoid: [], stones: [stone],
    today: `${day.name}日 · ${D}`,
    master: `${bz.master}${bz.masterEl}`,
    element: lucky, elements: [lucky], theme: themeOf(bz.masterEl, lucky), gender: profile.gender || '',
    baziLine: `八字 ${pillars}${hasTime ? '' : '（缺时柱）'}${luckText} · ${bz.master}${bz.masterEl}日主${st.level} · 喜${st.favorable.join('')} · 忌${st.avoid}`,
    astroLine: `星盘 太阳${SIGNS[nat.sun]} · 月亮${SIGNS[nat.moon]}${nat.moonUncertain ? '?' : ''}${nat.asc != null ? ' · 上升' + SIGNS[nat.asc] : ''}`,
    summary: `今日幸运五行：${lucky}${lucky === D ? '（正逢今日）' : ''}；${astroWhy}`,
  };
}
