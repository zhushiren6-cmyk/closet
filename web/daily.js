// Today's fortune from the user's birth profile: 八字 (favourable elements) + 星盘 (today's Moon against the
// natal chart). Libraries are loaded lazily in the browser; tests pass them in directly.
import { computeFortune, dayPillar, ELEMENT_COLORS, ELEMENT_STONES } from './fortune.js';
import { cityOf } from './cities.js';
import { localToUtc, bazi, strength, natal, transits, genOf, SIGNS, SIGN_COLORS, harmonious } from './birth.js';

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
  const key = `${p.date}|${p.time}|${p.city}`;
  if (cache.has(key)) return cache.get(key);
  const city = cityOf(p.city);
  const hasTime = /^\d{2}:\d{2}$/.test(p.time ?? '');
  const ms = localToUtc(p.date, hasTime ? p.time : '12:00', city.tz);
  const bz = bazi(libs.lunar, ms, city.lon, hasTime);
  const st = strength(bz);
  const nat = natal(libs.A, ms, city.lat, city.lon, hasTime);
  const out = { city, hasTime, bz, st, nat };
  cache.set(key, out);
  return out;
}

/**
 * [profile] = { date:'YYYY-MM-DD', time:'HH:MM'|'' , city: name }. Without libs or a date, falls back to the
 * day-only five-element rule. Returns the same shape as computeFortune plus baziLine / astroLine.
 */
export function computeDaily(today, profile, libs, tz = Intl.DateTimeFormat().resolvedOptions().timeZone) {
  if (!profile?.date || !libs) return computeFortune(today, profile?.date ?? '');
  const { hasTime, bz, st, nat } = analyse(libs, profile);
  const day = dayPillar(today);
  const D = day.element;

  // 八字: favourable elements, the one today's element is or feeds goes first.
  const rank = e => (e === D ? 0 : e === genOf(D) ? 1 : 2);
  const top = st.favorable.slice().sort((a, b) => rank(a) - rank(b)).slice(0, 2);
  const baziColors = [...new Set(top.flatMap(e => ELEMENT_COLORS[e]))];
  const avoidColors = ELEMENT_COLORS[st.avoid];

  // 星盘: today's Moon against the ascendant (or Sun without a birth time).
  const tr = transits(libs.A, localToUtc(today, '12:00', tz));
  const keyIdx = nat.asc ?? nat.sun;
  const keyName = `${nat.asc != null ? '上升' : '太阳'}${SIGNS[keyIdx]}`;
  const ok = harmonious(tr.moon, keyIdx);
  const astroColors = ok ? SIGN_COLORS[tr.moon] : SIGN_COLORS[nat.venus];
  const astroWhy = ok ? `今日月亮在${SIGNS[tr.moon]}，和你的${keyName}合拍`
    : `今日月亮在${SIGNS[tr.moon]}，和你的${keyName}不太合拍，穿你的金星${SIGNS[nat.venus]}色稳住`;

  // Two colours a day: a main one (agreed by both systems if possible, else from the top favourable element)
  // and an accent (today's chart colour or the second favourable element). Lists rotate by day so the
  // pick within an element changes from day to day.
  const both = baziColors.filter(c => astroColors.includes(c));
  const main = rotate(both.length ? both : ELEMENT_COLORS[top[0]], today)[0];
  const accent = [...rotate(astroColors, today), ...rotate(ELEMENT_COLORS[top[1] ?? top[0]], today)]
    .find(c => c !== main && !avoidColors.includes(c));
  const colors = accent ? [main, accent] : [main];
  const avoid = avoidColors.filter(c => !colors.includes(c));
  const pool = ELEMENT_STONES[top[0]];
  const k = BRANCHES.indexOf(day.branch) % pool.length;

  const pillars = bz.pillars.map(p => p.name).join(' ');
  return {
    source: 'calc', colors, avoid,
    stones: [pool[k], pool[(k + 1) % pool.length]],
    today: `${day.name}日 · ${D}`,
    master: `${bz.master}${bz.masterEl}`,
    elements: top, avoidElement: st.avoid,
    baziLine: `八字 ${pillars}${hasTime ? '' : '（缺时柱）'} · ${bz.master}${bz.masterEl}日主${st.level} · 喜${st.favorable.join('')} · 忌${st.avoid}`,
    astroLine: `星盘 太阳${SIGNS[nat.sun]} · 月亮${SIGNS[nat.moon]}${nat.moonUncertain ? '?' : ''}${nat.asc != null ? ' · 上升' + SIGNS[nat.asc] : ''}`,
    summary: `${top.includes(D) ? `今日${D}正是你的喜用` : `今日${D}，宜补${top.join('、')}`}；${astroWhy}`,
    agree: both,
  };
}
