const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/script/feed-prefetch.js', 'utf8');
function page({ pathname = '/', saveData = false } = {}) {
  const created = [];
  class Element {}
  class IntersectionObserver {
    constructor(callback, options = {}) {
      this.callback = callback; this.root = options.root || null; this.rootMargin = options.rootMargin || '0px';
      this.thresholds = [].concat(options.threshold ?? 0); this.observed = []; created.push(this);
    }
    observe(t) { this.observed.push(t); }
    unobserve(t) { const i = this.observed.indexOf(t); if (i >= 0) this.observed.splice(i, 1); }
    disconnect() { this.observed.length = 0; }
    takeRecords() { return []; }
  }
  const context = { Element, IntersectionObserver, Object, String, location: { pathname }, navigator: { connection: { saveData } } };
  context.window = context; context.self = context; context.top = context;
  vm.runInNewContext(script, context);
  const node = (tagName) => Object.assign(new Element(), { tagName });
  return { context, created, node, margins: () => created.map(o => o.rootMargin) };
}
test('the lazy feed observer tracks the sentinel 3 viewports out and thumbnails 1 viewport out', () => {
  const p = page();
  const cb = () => {};
  const o = new p.context.IntersectionObserver(cb, { rootMargin: '10%', threshold: 0.01 });
  const sentinel = p.node('YTM-CONTINUATION-ITEM-RENDERER'), img = p.node('IMG');
  o.observe(sentinel); o.observe(img);
  const by = Object.fromEntries(p.created.map(x => [x.rootMargin, x.observed]));
  assert.deepEqual(by['300%'], [sentinel]);
  assert.deepEqual(by['100%'], [img]);
  assert.deepEqual(by['10%'], []);
  assert.equal(o.rootMargin, '10%');
  assert.deepEqual(o.thresholds, [0.01]);
  o.unobserve(img); assert.deepEqual(by['100%'], []);
  o.disconnect(); assert.deepEqual(by['300%'], []);
  for (const x of p.created) assert.equal(x.callback, cb);
});
test('other observers and the normalized margin form are handled', () => {
  const p = page();
  const other = new p.context.IntersectionObserver(() => {}, { rootMargin: '-48px 0px -49px', threshold: 0.8 });
  assert.equal(p.created.length, 1); assert.equal(other.rootMargin, '-48px 0px -49px');
  const norm = new p.context.IntersectionObserver(() => {}, { rootMargin: '10% 10% 10% 10%' });
  norm.observe(p.node('IMG'));
  assert.equal(p.created.find(x => x.rootMargin === '100%').observed.length, 1);
  const rooted = new p.context.IntersectionObserver(() => {}, { rootMargin: '10%', root: p.node('DIV') });
  assert.equal(rooted.rootMargin, '10%');
});
test('watch pages and Data Saver keep the page margin', () => {
  for (const opts of [{ pathname: '/watch' }, { pathname: '/shorts/abc' }, { pathname: '/', saveData: true }]) {
    const p = page(opts);
    const o = new p.context.IntersectionObserver(() => {}, { rootMargin: '10%', threshold: 0.01 });
    const img = p.node('IMG'); o.observe(img);
    assert.deepEqual(p.created.find(x => x.rootMargin === '10%').observed, [img], JSON.stringify(opts));
  }
  const p = page({ pathname: '/feed/subscriptions' });
  const o = new p.context.IntersectionObserver(() => {}, { rootMargin: '10%' });
  const s = p.node('YTM-CONTINUATION-ITEM-RENDERER'); o.observe(s);
  assert.deepEqual(p.created.find(x => x.rootMargin === '300%').observed, [s]);
});
test('route decided per observe: an SPA hop to watch uses the page margin for new targets', () => {
  const p = page();
  const o = new p.context.IntersectionObserver(() => {}, { rootMargin: '10%' });
  const a = p.node('IMG'); o.observe(a);
  p.context.location.pathname = '/watch';
  const b = p.node('IMG'); o.observe(b);
  assert.deepEqual(p.created.find(x => x.rootMargin === '100%').observed, [a]);
  assert.deepEqual(p.created.find(x => x.rootMargin === '10%').observed, [b]);
});
test('the wrapper is inert without a native IntersectionObserver and in frames', () => {
  const bare = { Object, String, location: { pathname: '/' }, navigator: {} }; bare.window = bare; bare.self = bare; bare.top = bare;
  vm.runInNewContext(script, bare); assert.equal(bare.IntersectionObserver, undefined);
  const framed = page(); const inner = { ...framed.context, top: {} }; inner.window = inner; inner.self = inner;
  const before = inner.IntersectionObserver = framed.created.constructor; vm.runInNewContext(script.replace('__feedPrefetch', '__x'), inner);
  assert.equal(inner.IntersectionObserver, before);
});
