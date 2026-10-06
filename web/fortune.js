// Daily fortune computed in the app: today's day pillar (干支) and, if the user gave a birthday, their
// day master (日主). Lucky colours follow the five-element cycle. Entertainment, not prophecy — the rule is
// shown to the user in plain words.
//
// Day-pillar formula: 1900-01-01 was 甲戌 (index 10 of the 60-day cycle). Checked against the open-source
// lunar-javascript calendar for every 7th day 1940–2060 (6314 dates, 0 mismatches).

const STEMS = '甲乙丙丁戊己庚辛壬癸';
const BRANCHES = '子丑寅卯辰巳午未申酉戌亥';
export const ELEMENTS = ['木', '火', '土', '金', '水'];
const STEM_ELEMENT = ['木', '木', '火', '火', '土', '土', '金', '金', '水', '水'];

/** Colours of each element, mapped onto the app's palette. */
export const ELEMENT_COLORS = {
  木: ['绿'],
  火: ['红', '粉', '紫', '橙'],
  土: ['黄', '卡其', '驼', '米', '棕'],
  金: ['白', '灰'],
  水: ['黑', '藏青', '蓝', '牛仔蓝'],
};
/** Crystals / materials traditionally tied to each element. */
export const ELEMENT_STONES = {
  木: ['绿幽灵', '东陵玉', '绿松石'],
  火: ['红玛瑙', '草莓晶', '石榴石', '紫水晶'],
  土: ['黄水晶', '虎眼石', '黄玉'],
  金: ['白水晶', '钛晶', '银饰'],
  水: ['黑曜石', '海蓝宝', '蓝晶石'],
};

const gen = e => ELEMENTS[(ELEMENTS.indexOf(e) + 1) % 5];          // e 生 gen(e)
const genBy = e => ELEMENTS[(ELEMENTS.indexOf(e) + 4) % 5];        // genBy(e) 生 e
const ctrlBy = e => ELEMENTS[(ELEMENTS.indexOf(e) + 3) % 5];       // ctrlBy(e) 克 e
const ctrl = e => ELEMENTS[(ELEMENTS.indexOf(e) + 2) % 5];         // e 克 ctrl(e)

/** 'YYYY-MM-DD' -> { name: '癸丑', stem, branch, element } */
export function dayPillar(key) {
  const [y, m, d] = key.split('-').map(Number);
  const days = Math.round((Date.UTC(y, m - 1, d) - Date.UTC(1900, 0, 1)) / 86400000);
  const i = (((10 + days) % 60) + 60) % 60;
  const stem = STEMS[i % 10], branch = BRANCHES[i % 12];
  return { name: stem + branch, stem, branch, element: STEM_ELEMENT[i % 10] };
}

/**
 * Today's fortune. [birthday] 'YYYY-MM-DD' or ''. Returns
 * { source:'calc', colors, avoid, stones, summary, today, master }.
 *
 * With a birthday (day master E, today's element D):
 *   lucky = what nourishes you (生我, 印) and what is like you (同我, 比);
 *   avoid = what controls you (克我);
 *   on days that drain you (D 克 E, or E 克 D, or E 生 D) 印 leads; otherwise 比 leads.
 * Without one: lucky = what nourishes today's element and today's element itself; avoid = what controls it.
 */
export function computeFortune(today, birthday = '') {
  const t = dayPillar(today);
  const D = t.element;
  let first, second, avoidEl, why, master = null;
  if (/^\d{4}-\d{2}-\d{2}$/.test(birthday)) {
    const b = dayPillar(birthday);
    const E = b.element;
    master = `${b.stem}${E}`;
    const yin = genBy(E), bi = E;
    const draining = D === ctrlBy(E) || D === ctrl(E) || D === gen(E);
    [first, second] = draining ? [yin, bi] : [bi, yin];
    avoidEl = ctrlBy(E);
    why = D === ctrlBy(E) ? `今日${D}克你的${E}，用${yin}来化解`
      : D === genBy(E) ? `今日${D}生你的${E}，顺势而为`
      : D === E ? `今日与你同属${E}，气场稳`
      : D === ctrl(E) ? `今日${D}耗你的${E}，补点${yin}`
      : `今日${D}泄你的${E}，补点${yin}`;
  } else {
    [first, second] = [genBy(D), D];
    avoidEl = ctrlBy(D);
    why = `今日属${D}，${first}生${D}`;
  }
  const colors = [...new Set([...ELEMENT_COLORS[first], ...ELEMENT_COLORS[second]])];
  const avoid = ELEMENT_COLORS[avoidEl].filter(c => !colors.includes(c));
  // Rotate the stone list by the day's branch so the suggestion changes within an element's run.
  const pool = ELEMENT_STONES[first];
  const k = BRANCHES.indexOf(t.branch) % pool.length;
  const stones = [pool[k], pool[(k + 1) % pool.length]];
  return {
    source: 'calc', colors, avoid, stones,
    summary: `${why}；宜${first}、${second}色`,
    today: `${t.name}日 · ${D}`, master, elements: [first, second], avoidElement: avoidEl,
  };
}
