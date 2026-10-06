// Same cases as the Android LogicTest. Run: node --test web/test/
import test from 'node:test';
import assert from 'node:assert/strict';
import { OutfitEngine, Cat, seeded, normalizeCat, normalizeColor, daysBetween } from '../engine.js';
import { parseResult, parseBox, VisionError } from '../vision.js';

const today = '2026-10-05';
let n = 0;
const item = (cat, color = '黑', warmth = 2, occasions = [], name = `${cat}${n}`) => ({ id: `id${n++}`, name, cat, color, warmth, occasions });
const ok = r => { assert.ok(r.pieces, `expected outfit, got ${JSON.stringify(r)}`); return r; };
const ids = o => o.pieces.map(p => p.id);

const basic = [
  item(Cat.TOP, '白', 1), item(Cat.TOP, '灰', 2), item(Cat.TOP, '黑', 3),
  item(Cat.BOTTOM, '牛仔蓝', 2), item(Cat.BOTTOM, '黑', 1), item(Cat.BOTTOM, '卡其', 3),
  item(Cat.OUTER, '驼', 3), item(Cat.OUTER, '藏青', 1), item(Cat.SHOES, '白'), item(Cat.BAG, '黑'),
];
const W = ['HOT', 'WARM', 'COOL', 'COLD'];

test('empty wardrobe explains what is missing', () => {
  assert.ok(new OutfitEngine([], {}, today).generate('WARM', '日常').missing);
  assert.match(new OutfitEngine([item(Cat.TOP)], {}, today).generate('WARM', '日常').missing, /下装/);
});

test('always top+bottom or dress, no duplicates', () => {
  for (let s = 0; s < 300; s++) {
    const o = ok(new OutfitEngine(basic, {}, today, seeded(s)).generate(W[s % 4], '日常'));
    const cats = o.pieces.map(p => p.cat);
    assert.ok((cats.includes(Cat.TOP) && cats.includes(Cat.BOTTOM)) || cats.includes(Cat.DRESS));
    assert.equal(new Set(cats).size, cats.length);
    assert.equal(new Set(ids(o)).size, ids(o).length);
  }
});

test('hot never has outer, cold always does', () => {
  for (let s = 0; s < 200; s++) {
    const hot = ok(new OutfitEngine(basic, {}, today, seeded(s)).generate('HOT', '日常'));
    assert.ok(!hot.pieces.some(p => p.cat === Cat.OUTER));
    assert.ok(hot.pieces.filter(p => p.cat === Cat.TOP || p.cat === Cat.BOTTOM).every(p => p.warmth < 3));
    const cold = ok(new OutfitEngine(basic, {}, today, seeded(s)).generate('COLD', '日常'));
    assert.ok(cold.pieces.some(p => p.cat === Cat.OUTER));
  }
});

test('recently worn is rarely picked', () => {
  const tops = [0, 1, 2].map(() => item(Cat.TOP, '白', 2));
  const items = [...tops, item(Cat.BOTTOM, '黑', 2)];
  const worn = { [tops[0].id]: '2026-10-04' };
  let hits = 0;
  for (let s = 0; s < 400; s++) if (ids(ok(new OutfitEngine(items, worn, today, seeded(s)).generate('WARM', '日常'))).includes(tops[0].id)) hits++;
  assert.ok(hits < 40, `picked ${hits}/400`);
});

test('only dresses works; sport excludes dresses', () => {
  const items = [item(Cat.DRESS, '粉', 1), item(Cat.SHOES)];
  ok(new OutfitEngine(items, {}, today).generate('HOT', '约会'));
  assert.ok(new OutfitEngine(items, {}, today).generate('HOT', '运动').missing);
});

test('winter-only wardrobe in summer makes do and says so', () => {
  const items = [item(Cat.TOP, '黑', 3, [], '厚毛衣'), item(Cat.BOTTOM, '黑', 3, [], '加绒裤')];
  const o = ok(new OutfitEngine(items, {}, today).generate('HOT', '日常'));
  assert.equal(o.pieces.length, 2);
  assert.ok(o.reasons.some(r => r.includes('凑合')), o.reasons.join());
});

test('swap changes only that slot', () => {
  const o = ok(new OutfitEngine(basic, {}, today, seeded(1)).generate('WARM', '日常'));
  const i = o.pieces.findIndex(p => p.cat === Cat.TOP || p.cat === Cat.DRESS);
  const s = new OutfitEngine(basic, {}, today, seeded(2)).swap(o, i, 'WARM', '日常');
  assert.ok(s && s.pieces[i].id !== o.pieces[i].id);
  assert.deepEqual(s.pieces.filter((_, k) => k !== i), o.pieces.filter((_, k) => k !== i));
  const single = [item(Cat.TOP), item(Cat.BOTTOM)];
  const o2 = ok(new OutfitEngine(single, {}, today).generate('WARM', '日常'));
  assert.equal(new OutfitEngine(single, {}, today).swap(o2, 0, 'WARM', '日常'), null);
});

test('color score penalises clashes', () => {
  const e = new OutfitEngine([], {}, today);
  assert.equal(e.colorScore([item(Cat.TOP, '红'), item(Cat.BOTTOM, '黑')]), 1);
  assert.ok(e.colorScore([item(Cat.TOP, '红'), item(Cat.BOTTOM, '绿'), item(Cat.SHOES, '黄')]) < 0.5);
});

test('parses fenced JSON and scales boxes', () => {
  const raw = '```json\n{"items":[{"name":"黑色连帽卫衣","cat":"上装","color":"黑色","warmth":2,"occasions":["日常","运动","胡说"],"box":[100,200,300,400]}]}\n```';
  const { items } = parseResult(raw, 1000, 2000);
  assert.equal(items.length, 1);
  assert.equal(items[0].color, '黑');
  assert.deepEqual(items[0].occasions, ['日常', '运动']);
  const [l, t, r, b] = items[0].box;
  assert.ok(l >= 90 && l <= 100 && r >= 300 && r <= 310 && t >= 380 && t <= 400 && b >= 800 && b <= 815, items[0].box.join());
});

test('parses bare array, pixels and fractions', () => {
  const { items } = parseResult('结果：[{"name":"牛仔裤","box":[0.1,0.1,0.5,0.4]}]', 800, 1000);
  assert.equal(items[0].cat, Cat.BOTTOM);
  assert.equal(items[0].color, '牛仔蓝');
  assert.ok(items[0].box);
  assert.ok(parseBox([1200, 1500, 1900, 2600], 2000, 4000));
  assert.equal(parseBox([10, 10, 12, 12], 1000, 1000), null);
  assert.equal(parseBox([1, 2, 3], 1000, 1000), null);
  assert.equal(parseBox(null, 1000, 1000), null);
  assert.ok(parseBox([500, 600, 100, 200], 1000, 1000));
});

test('empty result keeps note; garbage throws', () => {
  const { items, note } = parseResult('{"items":[],"note":"不是服饰"}', 100, 100);
  assert.equal(items.length, 0); assert.equal(note, '不是服饰');
  assert.throws(() => parseResult('我无法识别', 100, 100), VisionError);
  assert.throws(() => parseResult('{"items": [', 100, 100), VisionError);
});

test('normalizers and dates', () => {
  assert.equal(normalizeCat('百褶半身裙'), Cat.BOTTOM);
  assert.equal(normalizeCat('碎花连衣裙'), Cat.DRESS);
  assert.equal(normalizeCat('羽绒服'), Cat.OUTER);
  assert.equal(normalizeCat('马丁靴'), Cat.SHOES);
  assert.equal(normalizeCat('???'), Cat.TOP);
  assert.equal(normalizeColor('米白色'), '米');
  assert.equal(normalizeColor('深蓝'), '藏青');
  assert.equal(normalizeColor('红白条纹'), '花色');
  assert.equal(normalizeColor(''), '灰');
  assert.equal(daysBetween('2026-09-28', '2026-10-05'), 7);
  assert.equal(daysBetween('2026-03-07', '2026-03-09'), 2);
});

// ---------------- fortune & jewellery ----------------
import { pickJewelry, stoneMatch } from '../engine.js';
import { parseFortune } from '../vision.js';

const wardrobe = () => [
  item(Cat.TOP, '白', 2), item(Cat.TOP, '绿', 2, [], '鼠尾草绿衬衫'), item(Cat.TOP, '红', 2),
  item(Cat.BOTTOM, '黑', 2), item(Cat.BOTTOM, '牛仔蓝', 2), item(Cat.SHOES, '白'),
  { ...item(Cat.JEWEL, '粉', 2, [], '粉晶手链'), material: '粉晶' },
  { ...item(Cat.JEWEL, '白', 2, [], '珍珠耳钉'), material: '珍珠' },
  { ...item(Cat.JEWEL, '黄', 2, [], '金色细项链'), material: '黄金' },
];

test('every outfit carries the lucky colour when the wardrobe has one', () => {
  const ws = wardrobe();
  const f = { colors: ['绿'], avoid: [], stones: [] };
  for (let s = 0; s < 200; s++) {
    const o = ok(new OutfitEngine(ws, {}, today, seeded(s)).generate(W[s % 4], '日常', new Set(), f));
    assert.ok(o.pieces.some(p => p.color === '绿' && p.cat !== Cat.JEWEL), `seed ${s}: ${o.pieces.map(p => p.name)}`);
    assert.ok(o.reasons[0].includes('幸运色'));
  }
});

test('lucky colour missing from wardrobe: still dresses you and says so', () => {
  const ws = wardrobe();
  const o = ok(new OutfitEngine(ws, {}, today, seeded(3)).generate('WARM', '日常', new Set(), { colors: ['紫'], avoid: [], stones: [] }));
  assert.ok(o.reasons.some(r => r.includes('还没有能穿的这个颜色')));
});

test('avoid colours are rarely picked', () => {
  const ws = wardrobe();
  let red = 0;
  for (let s = 0; s < 300; s++) {
    const o = ok(new OutfitEngine(ws, {}, today, seeded(s)).generate('WARM', '日常', new Set(), { colors: [], avoid: ['红'], stones: ['x'] }));
    if (o.pieces.some(p => p.color === '红')) red++;
  }
  assert.ok(red < 25, `red picked ${red}/300`);
});

test('jewellery follows the recommended stones, none without a fortune', () => {
  const ws = wardrobe();
  assert.equal(pickJewelry(ws, null).pieces.length, 0);
  const noFortune = ok(new OutfitEngine(ws, {}, today, seeded(1)).generate('WARM', '日常'));
  assert.ok(!noFortune.pieces.some(p => p.cat === Cat.JEWEL));
  const j = pickJewelry(ws, { colors: [], stones: ['粉水晶', '黄金'] });
  assert.deepEqual(j.pieces.map(p => p.name), ['粉晶手链', '金色细项链']);
  const miss = pickJewelry(ws, { colors: ['白'], stones: ['绿幽灵'] });
  assert.ok(miss.reasons[0].includes('绿幽灵'));
  assert.deepEqual(miss.pieces.map(p => p.name), ['珍珠耳钉']); // falls back to a lucky-colour piece
  assert.ok(stoneMatch({ name: '白水晶吊坠', material: '' }, '白晶'));
  assert.ok(!stoneMatch({ name: '黄金戒指', material: '黄金' }, '粉晶'));
});

test('swapping the only lucky piece keeps the lucky colour', () => {
  const ws = [...wardrobe(), item(Cat.TOP, '绿', 2, [], '绿色针织')];
  const f = { colors: ['绿'], avoid: [], stones: [] };
  const o = ok(new OutfitEngine(ws, {}, today, seeded(5)).generate('WARM', '日常', new Set(), f));
  const i = o.pieces.findIndex(p => p.color === '绿' && p.cat !== Cat.JEWEL);
  for (let s = 0; s < 30; s++) {
    const n = new OutfitEngine(ws, {}, today, seeded(100 + s)).swap(o, i, 'WARM', '日常', f);
    assert.ok(n && n.pieces[i].color === '绿' && n.pieces[i].id !== o.pieces[i].id);
  }
});

test('parses fortune screenshots and rejects non-fortunes', () => {
  const f = parseFortune('```json\n{"colorText":["薄荷绿","奶白"],"colors":["绿","白","花色"],"avoid":["黑","绿"],"stones":["粉晶"],"summary":"宜出门见朋友"}\n```');
  assert.deepEqual(f.colors, ['绿', '白']);
  assert.deepEqual(f.avoid, ['黑']);
  assert.deepEqual(f.stones, ['粉晶']);
  const g = parseFortune('{"colorText":["酒红色"],"colors":[],"avoid":[],"stones":[]}');
  assert.deepEqual(g.colors, ['红']);
  assert.throws(() => parseFortune('{"ok":false,"note":"这是订单截图"}'), /不是运势截图/);
  assert.throws(() => parseFortune('{"colorText":[],"colors":[],"stones":[]}'), /没从截图里读到/);
  assert.throws(() => parseFortune('今天运势不错'), VisionError);
});

test('jewellery keywords map to 首饰', () => {
  for (const s of ['粉晶手链', '珍珠耳钉', '黄金项链', '银戒指', '首饰']) assert.equal(normalizeCat(s), Cat.JEWEL, s);
  assert.equal(normalizeCat('渔夫帽'), Cat.ACC);
});
