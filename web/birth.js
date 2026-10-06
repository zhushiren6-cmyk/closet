// Birth chart maths. Pure functions; the two libraries are passed in so the same code runs in the
// browser and in Node tests.
//   lunar : lunar-javascript (6tail, MIT) — four pillars with solar-term month boundaries, hidden stems
//   A     : astronomy-engine (Don Cross, MIT) — Sun/Moon/Venus longitudes, sidereal time

import { ELEMENTS } from './fortune.js';

const STEM_EL = { 甲: '木', 乙: '木', 丙: '火', 丁: '火', 戊: '土', 己: '土', 庚: '金', 辛: '金', 壬: '水', 癸: '水' };
const SEASON_EL = { 寅: '木', 卯: '木', 巳: '火', 午: '火', 申: '金', 酉: '金', 亥: '水', 子: '水', 辰: '土', 戌: '土', 丑: '土', 未: '土' };
const idx = e => ELEMENTS.indexOf(e);
export const genOf = e => ELEMENTS[(idx(e) + 1) % 5];     // e 生 genOf(e)
export const genBy = e => ELEMENTS[(idx(e) + 4) % 5];     // genBy(e) 生 e
export const ctrlOf = e => ELEMENTS[(idx(e) + 2) % 5];    // e 克 ctrlOf(e)
export const ctrlBy = e => ELEMENTS[(idx(e) + 3) % 5];    // ctrlBy(e) 克 e

// ---------------- time ----------------

/** Minutes east of UTC for [tz] at the instant [ms]. Uses the browser/Node time-zone database (DST included). */
export function tzOffsetMin(ms, tz) {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).formatToParts(new Date(ms)).map(x => [x.type, x.value]));
  const asUtc = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
  return Math.round((asUtc - ms) / 60000);
}

/** Wall-clock 'YYYY-MM-DD' + 'HH:MM' in [tz] -> UTC milliseconds. */
export function localToUtc(date, time, tz) {
  const [y, m, d] = date.split('-').map(Number);
  const [hh, mm] = (time || '12:00').split(':').map(Number);
  const wall = Date.UTC(y, m - 1, d, hh, mm);
  let ms = wall - tzOffsetMin(wall, tz) * 60000;
  ms = wall - tzOffsetMin(ms, tz) * 60000; // second pass settles DST edges
  return ms;
}

/** Equation of time in minutes (apparent − mean solar time), standard approximation, ±0.5 min. */
export function equationOfTime(ms) {
  const d = new Date(ms);
  const n = Math.floor((ms - Date.UTC(d.getUTCFullYear(), 0, 1)) / 86400000) + 1;
  const b = (2 * Math.PI * (n - 81)) / 364;
  return 9.87 * Math.sin(2 * b) - 7.53 * Math.cos(b) - 1.5 * Math.sin(b);
}

/** True solar time at longitude [lon] for instant [ms], as calendar fields. */
export function trueSolarFields(ms, lon) {
  const t = new Date(ms + (lon * 4 + equationOfTime(ms)) * 60000);
  return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate(), h: t.getUTCHours(), mi: t.getUTCMinutes() };
}

// ---------------- 八字 ----------------

/**
 * Four pillars at true solar time. Without a birth time the hour pillar is left out.
 * Returns { pillars:[{name,stem,branch,hidden}] (year, month, day[, hour]), master, masterEl }.
 */
export function bazi(lunar, ms, lon, hasTime) {
  const f = trueSolarFields(ms, lon);
  const ec = lunar.Solar.fromYmdHms(f.y, f.m, f.d, hasTime ? f.h : 12, hasTime ? f.mi : 0, 0).getLunar().getEightChar();
  const mk = (name, hidden) => ({ name, stem: name[0], branch: name[1], hidden });
  const pillars = [mk(ec.getYear(), ec.getYearHideGan()), mk(ec.getMonth(), ec.getMonthHideGan()), mk(ec.getDay(), ec.getDayHideGan())];
  if (hasTime) pillars.push(mk(ec.getTime(), ec.getTimeHideGan()));
  const master = pillars[2].stem;
  return { pillars, master, masterEl: STEM_EL[master], solar: f, ec };
}

const MAIN_QI = { 子: '癸', 丑: '己', 寅: '甲', 卯: '乙', 辰: '戊', 巳: '丙', 午: '丁', 未: '己', 申: '庚', 酉: '辛', 戌: '戊', 亥: '壬' };
/**
 * 大运 for [year]: direction follows the year stem and [gender] ('male'|'female'; 阳男阴女顺排), start age from
 * the solar terms (lunar-javascript getYun). Null without a gender or before the first decade starts.
 */
export function daYun(bz, gender, year) {
  if (gender !== 'male' && gender !== 'female') return null;
  const yun = bz.ec.getYun(gender === 'male' ? 1 : 0);
  const d = yun.getDaYun(12).find(x => x.getGanZhi() && x.getStartYear() <= year && year <= x.getEndYear());
  if (!d) return { name: '', startAge: yun.getDaYun(2)[1].getStartAge(), forward: yun.isForward() };
  const name = d.getGanZhi();
  return { name, stem: name[0], branch: name[1], startAge: d.getStartAge(), startYear: d.getStartYear(), forward: yun.isForward() };
}

/**
 * Day-master strength, after the published scoring used by stellium (stellium.readthedocs.io):
 *   seasonal (month branch vs day master): same +3, season produces DM +1, DM produces season 0,
 *     season controls DM -1, DM controls season -2;
 *   roots: hidden stems of DM's element, by pillar (day 1.5, month 1.2, hour 0.8, year 0.6) and position
 *     (main 1.0, middle 0.6, residual 0.3);
 *   support − drain over the other visible stems and branch main qi.
 *   score = seasonal×2 + roots×1.5 + (support − drain)×0.5;  ≥2 strong, ≤−2 weak, between: by sign.
 * Favourable: strong → what drains it (output, wealth, power); weak → resource and self.
 */
export function strength(bz, luck = null) {
  const E = bz.masterEl;
  const S = SEASON_EL[bz.pillars[1].branch];
  const seasonal = S === E ? 3 : genOf(S) === E ? 1 : genOf(E) === S ? 0 : ctrlOf(S) === E ? -1 : -2;
  const pw = [0.6, 1.2, 1.5, 0.8];
  const qw = [1.0, 0.6, 0.3];
  let roots = 0;
  bz.pillars.forEach((p, i) => p.hidden.forEach((g, j) => { if (STEM_EL[g] === E) roots += pw[i] * (qw[j] ?? 0.3); }));
  let support = 0, drain = 0;
  const others = [];
  bz.pillars.forEach((p, i) => { if (i !== 2) others.push(p.stem); others.push(p.hidden[0]); });
  // The current 大运 counts like one more pillar's stem and main qi (岁运参与旺衰).
  if (luck?.name) others.push(luck.stem, MAIN_QI[luck.branch]);
  for (const g of others) { const e = STEM_EL[g]; if (e === E || genOf(e) === E) support++; else drain++; }
  const score = seasonal * 2 + roots * 1.5 + (support - drain) * 0.5;
  const strong = score >= 0;
  const level = score >= 6 ? '很旺' : score >= 2 ? '偏旺' : score > -2 ? (strong ? '中和略旺' : '中和略弱') : score > -6 ? '偏弱' : '很弱';
  const favorable = strong ? [genOf(E), ctrlOf(E), ctrlBy(E)] : [genBy(E), E];
  const avoid = strong ? genBy(E) : ctrlBy(E);
  return { score: Math.round(score * 10) / 10, strong, level, favorable, avoid };
}

// ---------------- 星盘 ----------------

export const SIGNS = ['白羊', '金牛', '双子', '巨蟹', '狮子', '处女', '天秤', '天蝎', '射手', '摩羯', '水瓶', '双鱼'];
const SIGN_EL = ['火', '土', '风', '水'];
export const signEl = i => SIGN_EL[i % 4];
/** Traditional sign colours, mapped onto the app palette. */
export const SIGN_COLORS = [
  ['红', '橙'], ['绿', '粉'], ['黄', '白'], ['白', '灰'], ['橙', '黄'], ['米', '棕', '藏青'],
  ['粉', '蓝'], ['黑', '红'], ['紫', '蓝'], ['棕', '灰', '黑'], ['蓝', '牛仔蓝'], ['绿', '紫'],
];
const norm = x => ((x % 360) + 360) % 360;
const signOf = lon => Math.floor(norm(lon) / 30);

/** Same element, or fire–air / earth–water: harmonious. */
export function harmonious(a, b) {
  const ea = signEl(a), eb = signEl(b);
  if (ea === eb) return true;
  const pair = new Set([ea, eb]);
  return (pair.has('火') && pair.has('风')) || (pair.has('土') && pair.has('水'));
}

function longitudes(A, ms) {
  const t = A.MakeTime(new Date(ms));
  return {
    t,
    sun: A.SunPosition(t).elon,
    moon: A.EclipticGeoMoon(t).lon,
    venus: A.Ecliptic(A.GeoVector(A.Body.Venus, t, true)).elon,
  };
}

/**
 * Ascendant (tropical ecliptic longitude). Formula checked against the horizon: the returned point has
 * altitude 0 on the eastern side for Beijing, Shanghai, Urumqi, Hong Kong, Sydney and London test cases.
 */
export function ascendant(A, ms, lat, lon) {
  const t = A.MakeTime(new Date(ms));
  const eps = (23.439291 - 0.0130042 * (t.tt / 36525)) * Math.PI / 180;
  const th = norm(A.SiderealTime(t) * 15 + lon) * Math.PI / 180;
  const f = lat * Math.PI / 180;
  return norm(Math.atan2(Math.cos(th), -(Math.sin(th) * Math.cos(eps) + Math.tan(f) * Math.sin(eps))) * 180 / Math.PI);
}

/** Natal placements. Without a birth time there is no ascendant and the Moon may be off by a sign. */
export function natal(A, ms, lat, lon, hasTime) {
  const L = longitudes(A, ms);
  return {
    sun: signOf(L.sun), moon: signOf(L.moon), venus: signOf(L.venus),
    asc: hasTime ? signOf(ascendant(A, ms, lat, lon)) : null,
    moonUncertain: !hasTime,
  };
}

/** Where the Moon and Venus are today (at local noon). */
export function transits(A, ms) {
  const L = longitudes(A, ms);
  return { moon: signOf(L.moon), venus: signOf(L.venus) };
}
