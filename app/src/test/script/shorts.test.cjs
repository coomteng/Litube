const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/script/shorts.js', 'utf8');
function page({ blocked = false, prefs = {} } = {}) {
  let now = 0, ensure, timer;
  const listeners = new Map(), events = [];
  const video = { readyState: 3, paused: true, muted: true, volume: 0, playbackRate: 1.5, dataset: {},
    getBoundingClientRect: () => ({ width: 300, height: 600, top: 0, bottom: 600 }),
    pause() { this.paused = true; }, play() { if (!blocked) { this.paused = false; this.muted = false; } else this.muted = true; return Promise.resolve(); } };
  const root = { closest: () => null };
  // Minimal Document prototype so the script can mask hidden/visibilityState like a browser.
  const realVis = { hidden: false, state: 'visible' };
  function Document() {}
  Object.defineProperty(Document.prototype, 'hidden', { configurable: true, get() { return realVis.hidden; } });
  Object.defineProperty(Document.prototype, 'visibilityState', { configurable: true, get() { return realVis.state; } });
  const doc = Object.create(Document.prototype);
  Object.assign(doc, { querySelectorAll: () => [video], querySelector: () => null,
    addEventListener: (type, handler) => listeners.set(type, handler) });
  const context = { location: { pathname: '/shorts/aaaaaaaaaaa', href: 'https://m.youtube.com/shorts/aaaaaaaaaaa' }, innerHeight: 800, Object, Document,
    Date: { now: () => now }, document: doc, realVis,
    addEventListener: (type, handler) => listeners.set(type, handler),
    setTimeout: fn => { timer = fn; return 1; }, clearTimeout: () => { timer = null; },
    Lite: { module: (_, callback) => { ensure = callback; }, prefs: () => prefs,
      isShorts: () => /^\/shorts(?:\/|$)/.test(context.location.pathname) },
    Bridge: { shortsAudioReady: () => events.push('ready'), shortsAutoplayBlocked: (_, reason) => events.push(reason) } };
  context.window = context; vm.runInNewContext(script, context);
  return { video, context, events, poll: t => { now = t; ensure(); },
    touch: (type, x = 100, y = 100, target = root) => listeners.get(type)?.({ target, touches: [{ clientX: x, clientY: y }] }),
    listeners, fire: (type, detail) => listeners.get(type)({ detail, stopImmediatePropagation() {} }), activate: () => timer?.() };
}
test('hidden tabs pause all webpage media', () => {
  const p = page(); p.poll(1); assert.equal(p.video.paused, false);
  p.fire('tabVisibilityChanged', { active: false }); p.poll(4000); assert.equal(p.video.paused, true);
});
test('blocked sound reports fallback once; success is based on media state', () => {
  const blocked = page({ blocked: true }); [1, 1000, 2000, 3000, 4000].forEach(blocked.poll);
  assert.deepEqual(blocked.events, ['muted_after_ready']);
  const success = page(); [1, 1000, 3000].forEach(success.poll); assert.deepEqual(success.events, ['ready']);
});
test('returning to a feed resumes only video paused by hiding the tab', () => {
  const p = page(); [1, 1000, 3000].forEach(p.poll);
  p.fire('tabVisibilityChanged', { active: false }); p.poll(4000);
  p.fire('tabVisibilityChanged', { active: true }); assert.equal(p.video.paused, false);
  p.video.pause(); p.fire('tabVisibilityChanged', { active: false }); p.fire('tabVisibilityChanged', { active: true });
  assert.equal(p.video.paused, true);
});
test('explicit mute prevents autoplay retries and fallback', () => {
  for (const label of ['Mute', '音をミュート', 'Ton aus', 'كتم الصوت', '🔇']) {
    const p = page(); p.poll(1);
    p.touch('click', 100, 100, { closest: () => ({ getAttribute: () => label }) });
    p.video.muted = true; p.activate();
    p.poll(3000); assert.equal(p.video.muted, true); assert.deepEqual(p.events, []);
  }
});


test('other controls and video switches do not record a user mute', () => {
  const p = page({ blocked: true }); p.poll(1);
  p.touch('click', 100, 100, { closest: () => ({}) }); p.activate();
  p.poll(3000); assert.deepEqual(p.events, ['muted_after_ready']);
  const next = page(); next.poll(1);
  next.touch('click', 100, 100, { closest: () => ({}) });
  next.video.muted = true;
  next.context.document.querySelectorAll = () => [];
  next.activate();
  next.context.document.querySelectorAll = () => [next.video];
  next.poll(3000); assert.equal(next.video.muted, false); assert.deepEqual(next.events, ['ready']);
});

test('native Shorts touch gestures and playback speed are left untouched', () => {
  const p = page(); p.poll(1);
  for (const type of ['touchstart', 'touchmove', 'touchend', 'touchcancel', 'blur']) {
    assert.equal(p.listeners.has(type), false, type);
  }
  p.video.playbackRate = 2; p.poll(3000);
  assert.equal(p.video.playbackRate, 2);
  assert.deepEqual(p.events, ['ready']);
});

test('a pause right after a tap while the video plays is kept and never reported as blocked', () => {
  const p = page(); p.poll(1); assert.equal(p.video.paused, false);
  p.touch('pointerdown'); p.video.pause(); p.poll(300);
  assert.equal(p.video.paused, true);
  p.poll(3000); assert.equal(p.video.paused, true); assert.deepEqual(p.events, []);
  p.video.play(); p.listeners.get('play')({ target: p.video }); p.poll(3500);
  assert.deepEqual(p.events, ['ready']);
});

test('pauses without a tap during playback are still retried', () => {
  const p = page(); p.poll(1); p.video.pause(); p.poll(300); assert.equal(p.video.paused, false);
  const q = page(); q.touch('pointerdown'); q.poll(1); q.video.pause(); q.poll(300); assert.equal(q.video.paused, false);
});

test('a swipe onto the reused video element is not taken for a user pause', () => {
  const p = page(); p.poll(1); assert.equal(p.video.paused, false);
  p.touch('pointerdown');
  Object.assign(p.context.location, { pathname: '/shorts/bbbbbbbbbbb', href: 'https://m.youtube.com/shorts/bbbbbbbbbbb' });
  p.video.paused = true;
  let retries = 0;
  p.video.play = () => { retries++; return Promise.resolve(); };
  [300, 1000, 3000].forEach(p.poll);
  assert.equal(retries, 3); assert.deepEqual(p.events, ['paused_after_ready']);
});

test('screen-off (document hidden) keeps the Short playing when background play is on', () => {
  const p = page({ prefs: { enable_background_play: true } }); [1, 1000, 3000].forEach(p.poll);
  assert.equal(p.video.paused, false);
  p.context.realVis.hidden = true; p.context.realVis.state = 'hidden';
  p.fire('visibilitychange'); p.poll(4000);
  assert.equal(p.video.paused, false);
  // The page must not learn that it is hidden, or YouTube's own player pauses it.
  assert.equal(p.context.document.hidden, false);
  assert.equal(p.context.document.visibilityState, 'visible');
});
test('screen-off pauses the Short when background play is off', () => {
  const p = page({ prefs: { enable_background_play: false } }); [1, 1000, 3000].forEach(p.poll);
  p.context.realVis.hidden = true; p.context.realVis.state = 'hidden';
  p.fire('visibilitychange'); p.poll(4000);
  assert.equal(p.video.paused, true);
});
test('leaving the tab still stops the Short even with background play on', () => {
  const p = page({ prefs: { enable_background_play: true } }); [1, 1000, 3000].forEach(p.poll);
  p.fire('tabVisibilityChanged', { active: false }); p.poll(4000);
  assert.equal(p.video.paused, true);
});
test('waking the screen restores the real visibility state', () => {
  const p = page({ prefs: { enable_background_play: true } }); [1, 1000, 3000].forEach(p.poll);
  p.context.realVis.hidden = true; p.context.realVis.state = 'hidden'; p.fire('visibilitychange');
  assert.equal(p.context.document.hidden, false);
  p.context.realVis.hidden = false; p.context.realVis.state = 'visible'; p.fire('visibilitychange');
  p.context.realVis.hidden = true; p.context.realVis.state = 'hidden';
  // Not masked any more: without a new visibilitychange the page sees the truth.
  assert.equal(p.context.document.hidden, true);
});
