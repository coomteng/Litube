const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/script/shorts-quality.js', 'utf8');
function page(host, dpr = 3) {
  const video = { getBoundingClientRect: () => ({width:300,height:500,top:0,bottom:500}), closest: () => host };
  if (host && !host.querySelector) host.querySelector = () => video;
  const context = { location: { pathname: '/shorts/fixture' }, innerWidth: 360, innerHeight: 800, devicePixelRatio: dpr,
    document: { querySelectorAll: sel => sel === 'video' ? [video] : [], querySelector: () => null, addEventListener() {} },
    addEventListener() {}, navigator: {}, Lite: { module() {}, sheet() {}, isShorts: () => true, text: key => ({auto:'自动',unavailable:'不可用'})[key] } };
  context.window = context;
  vm.runInNewContext(script.replace("Lite.module('shorts-quality', ensure);",
    "window.access={levels,label,apply,player,floorLevel,ensure,window};Lite.module('shorts-quality', ensure);"), context);
  return context.access;
}
test('quality choices come from the active player and reject unavailable selections', () => {
  const applied = [];
  const host = { getAvailableQualityLevels: () => ['hd720','hd720','small','auto',null],
    setPlaybackQualityRange: (min,max) => applied.push([min,max]) };
  const api = page(host);
  assert.equal(api.player(), host);
  assert.equal(JSON.stringify(api.levels(host)), JSON.stringify(['hd720','small']));
  assert.equal(api.apply(host, 'hd1080'), false); assert.deepEqual(applied, []);
  assert.equal(api.apply(host, 'hd720'), true); assert.deepEqual(applied, [['hd720','hd720']]);
  assert.equal(api.apply(host, 'default'), true); assert.deepEqual(applied[1], ['hd720','hd720']);
});
test('automatic mode pins quality at the physical pixel height', () => {
  const applied = [];
  const host = { getAvailableQualityLevels: () => ['hd1080','hd720','large','medium','small','tiny','auto'],
    setPlaybackQualityRange: (min,max) => applied.push([min,max]) };
  const api = page(host);
  assert.equal(api.floorLevel(host), 'hd1080'); // short side 300 css px * dpr 3 * 0.9 = 810 -> hd1080
  assert.equal(api.apply(host, 'default'), true); assert.deepEqual(applied, [['hd1080','hd1080']]);
  host.getAvailableQualityLevels = () => ['medium','small'];
  assert.equal(api.floorLevel(host), 'medium'); // nothing covers 810 -> best available
  host.getAvailableQualityLevels = () => ['auto'];
  assert.equal(api.floorLevel(host), null); assert.equal(api.apply(host, 'default'), false);
  host.getAvailableQualityLevels = () => ['hd1080','medium'];
  api.window.navigator.connection = { saveData: true };
  assert.equal(api.apply(host, 'default'), true); assert.equal(applied.length, 1); // data saver: leave the player alone
});
test('a pin equal to the restored preference is nudged through a neighbour', () => {
  const applied = [];
  const host = { getAvailableQualityLevels: () => ['hd1080','hd720','medium'], getPreferredQuality: () => 'hd1080', getPlaybackQuality: () => 'medium',
    setPlaybackQualityRange: (min,max) => applied.push(min) };
  const api = page(host);
  assert.equal(api.apply(host, 'default'), true); assert.deepEqual(applied, ['hd720','hd1080']);
  host.getPlaybackQuality = () => 'hd1080';
  assert.equal(api.apply(host, 'hd1080'), true); assert.deepEqual(applied, ['hd720','hd1080','hd1080']); // already there: plain pin
});
test('automatic mode re-applies when the level list grows', () => {
  const applied = [];
  let list = ['tiny'];
  const host = { getAvailableQualityLevels: () => list, setPlaybackQualityRange: (min,max) => applied.push(min) };
  const api = page(host);
  api.ensure(); assert.deepEqual(applied, ['tiny']);
  api.ensure(); assert.deepEqual(applied, ['tiny']); // same list: no churn
  list = ['hd1080','hd720','medium','tiny'];
  api.ensure(); assert.deepEqual(applied, ['tiny','hd1080']);
});
test('a one-x screen keeps a modest floor', () => {
  const host = { getAvailableQualityLevels: () => ['hd1080','hd720','large','medium'], setPlaybackQualityRange() {} };
  assert.equal(page(host, 1).floorLevel(host), 'medium'); // 300 * 1 * 0.9 = 270 -> 360p
  assert.equal(page(host, 2).floorLevel(host), 'hd720'); // 540 -> 720p
  const wide = { getAvailableQualityLevels: () => ['hd2160','hd1440','hd1080','hd720'], setPlaybackQualityRange() {} };
  assert.equal(page(wide, 3).floorLevel(wide), 'hd1080'); // 1440p source on a 900 px wide screen: 1080p still covers it
  assert.equal(page(wide, 4).floorLevel(wide), 'hd1080'); // 1200 px: capped at 1080p, beyond the eye at phone distance
  const only4k = { getAvailableQualityLevels: () => ['hd2160'], setPlaybackQualityRange() {} };
  assert.equal(page(only4k, 4).floorLevel(only4k), 'hd2160'); // nothing at or under the cap: best available
});
test('legacy quality setters and API failures degrade without invented choices', () => {
  let chosen;
  const host = { getAvailableQualityLevels: () => ['small'], setPlaybackQuality: level => { chosen=level; } };
  const api = page(host);
  assert.equal(api.apply(host, 'small'), true); assert.equal(chosen, 'small');
  assert.equal(api.apply(host, 'default'), true); assert.equal(chosen, 'small'); // legacy setter: floor becomes a plain pick
  host.setPlaybackQuality = () => { throw Error('not ready'); };
  assert.equal(api.apply(host, 'small'), false); assert.equal(api.apply(host, 'default'), false);
  host.getAvailableQualityLevels = () => { throw Error('not ready'); };
  assert.equal(api.levels(host).length, 0); assert.equal(api.apply(null, 'default'), false);
});
test('labels use actual quality metadata and translated automatic mode', () => {
  const api = page(null);
  assert.equal(api.label('auto',null), '自动'); assert.equal(api.label('hd1080',null), '1080p');
  assert.equal(api.label('highres',{getAvailableQualityData:()=>[{quality:'highres',qualityLabel:'2160p60'}]}), '2160p60');
  assert.equal(api.label(null,null), '不可用');
});
