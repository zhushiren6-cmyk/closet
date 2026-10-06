// Birth-chart maths. Reference values: UTC conversions from Python zoneinfo; pillars from lunar-javascript;
// ascendant checked against the horizon (altitude 0, eastern azimuth).
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import * as A from '../vendor/astronomy.js';
import { localToUtc, equationOfTime, trueSolarFields, bazi, strength, natal, ascendant, harmonious, SIGNS } from '../birth.js';
import { computeDaily } from '../daily.js';
const lunar = createRequire(import.meta.url)('../vendor/lunar.js');

test('wall clock -> UTC, including China 1986-1991 DST', () => {
  assert.equal(new Date(localToUtc('1990-05-20', '08:30', 'Asia/Shanghai')).toISOString(), '1990-05-19T23:30:00.000Z'); // DST, +9
  assert.equal(new Date(localToUtc('1995-11-02', '08:30', 'Asia/Shanghai')).toISOString(), '1995-11-02T00:30:00.000Z');
  assert.equal(new Date(localToUtc('1990-05-20', '08:30', 'America/New_York')).toISOString(), '1990-05-20T12:30:00.000Z');
});

test('equation of time stays within its known range', () => {
  for (let m = 0; m < 12; m++) {
    const e = equationOfTime(Date.UTC(2026, m, 15));
    assert.ok(e > -15 && e < 17, `${m}: ${e}`);
  }
  // Beijing standard 07:30 in mid-May -> true solar ~07:19 (116.41°E is 14 min west of 120°E; EoT ~ +3.6)
  const f = trueSolarFields(localToUtc('1990-05-20', '08:30', 'Asia/Shanghai'), 116.41);
  assert.equal(f.h, 7); assert.ok(f.mi >= 15 && f.mi <= 23, String(f.mi));
});

test('four pillars at true solar time', () => {
  const ms = localToUtc('1990-05-20', '08:30', 'Asia/Shanghai');
  const bz = bazi(lunar, ms, 116.41, true);
  assert.deepEqual(bz.pillars.map(p => p.name), ['庚午', '辛巳', '乙酉', '庚辰']);
  assert.equal(bz.masterEl, '木');
  assert.equal(bazi(lunar, ms, 116.41, false).pillars.length, 3);
  // Urumqi: Beijing clock 08:30 is ~06:30 true solar time -> 卯时, not 辰时.
  const u = bazi(lunar, localToUtc('1995-11-02', '08:30', 'Asia/Shanghai'), 87.62, true);
  assert.equal(u.pillars[3].branch, '卯');
});

test('strength: crowded day master is strong and wants draining, isolated one is weak', () => {
  const mk = (n, hidden) => ({ name: n, stem: n[0], branch: n[1], hidden });
  const strongChart = { masterEl: '木', master: '甲', pillars: [mk('甲寅', ['甲', '丙', '戊']), mk('乙卯', ['乙']), mk('甲寅', ['甲', '丙', '戊']), mk('癸亥', ['壬', '甲'])] };
  const s = strength(strongChart);
  assert.ok(s.strong && s.score >= 6, JSON.stringify(s));
  assert.deepEqual(s.favorable, ['火', '土', '金']);
  assert.equal(s.avoid, '水');
  const weakChart = { masterEl: '木', master: '乙', pillars: [mk('庚申', ['庚', '壬', '戊']), mk('辛酉', ['辛']), mk('乙丑', ['己', '癸', '辛']), mk('庚辰', ['戊', '乙', '癸'])] };
  const w = strength(weakChart);
  assert.ok(!w.strong && w.score <= -2, JSON.stringify(w));
  assert.deepEqual(w.favorable, ['水', '木']);
  assert.equal(w.avoid, '金');
});

test('natal chart and ascendant', () => {
  const ms = localToUtc('1990-05-20', '08:30', 'Asia/Shanghai');
  const n = natal(A, ms, 39.90, 116.41, true);
  assert.equal(SIGNS[n.sun], '金牛'); // Sun at ~28.7° Taurus on 1990-05-19/20
  assert.ok(n.asc !== null);
  // Ascendant lies on the eastern horizon.
  const lam = ascendant(A, ms, 39.90, 116.41), e = 23.44 * Math.PI / 180, l = lam * Math.PI / 180;
  const ra = (((Math.atan2(Math.sin(l) * Math.cos(e), Math.cos(l)) * 180 / Math.PI) + 360) % 360) / 15;
  const dec = Math.asin(Math.sin(e) * Math.sin(l)) * 180 / Math.PI;
  const hz = A.Horizon(A.MakeTime(new Date(ms)), new A.Observer(39.90, 116.41, 0), ra, dec, null);
  assert.ok(Math.abs(hz.altitude) < 0.3 && hz.azimuth > 0 && hz.azimuth < 180, JSON.stringify(hz));
  assert.equal(natal(A, ms, 39.9, 116.41, false).asc, null);
  assert.ok(harmonious(0, 4) && harmonious(1, 3) && !harmonious(0, 3));
});

test('daily fortune from a full profile', () => {
  const libs = { lunar, A };
  const p = { date: '1990-05-20', time: '08:30', city: '北京' };
  const f = computeDaily('2026-10-06', p, libs, 'Asia/Shanghai');
  assert.equal(f.source, 'calc');
  assert.match(f.baziLine, /庚午 辛巳 乙酉 庚辰/);
  assert.match(f.astroLine, /太阳金牛/);
  assert.equal(f.colors.length, 2);
  assert.notEqual(f.colors[0], f.colors[1]);
  assert.ok(f.avoid.every(c => !f.colors.includes(c)));
  assert.equal(f.stones.length, 2);
  assert.deepEqual(computeDaily('2026-10-06', p, libs, 'Asia/Shanghai'), f); // stable within a day
  // No birth time: still works, hour pillar and ascendant left out.
  const g = computeDaily('2026-10-06', { ...p, time: '' }, libs, 'Asia/Shanghai');
  assert.match(g.baziLine, /缺时柱/); assert.ok(!/上升/.test(g.astroLine));
  // A year of days never crashes, yields 1–2 colours, and the pair changes over time.
  const seen = new Set();
  for (let i = 0; i < 365; i += 3) {
    const d = new Date(Date.UTC(2026, 0, 1 + i)).toISOString().slice(0, 10);
    const x = computeDaily(d, p, libs, 'Asia/Shanghai');
    assert.ok(x.colors.length >= 1 && x.colors.length <= 2 && x.avoid.every(c => !x.colors.includes(c)), d);
    seen.add(x.colors.join());
  }
  assert.ok(seen.size > 3, `colours vary across the year: ${seen.size} combinations`);
});
