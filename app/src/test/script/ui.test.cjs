const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/script/ui.js', 'utf8');
function page() {
  const listeners = new Map(), reports = [], rafs = [];
  let queries = 0, sheet = null;
  const node = (tag) => ({ tagName: tag, closest: () => null, querySelector: () => (tag === 'sheet' ? node('item') : null),
    querySelectorAll: () => [], getBoundingClientRect: () => ({ left: 0, top: 100, right: 360, bottom: 400, width: 360, height: 300 }),
    contains: () => false, matches: () => false });
  const doc = { readyState: 'complete', activeElement: null, documentElement: {},
    addEventListener: (type, handler) => listeners.set(type, handler),
    querySelectorAll: (sel) => { queries++; return []; },
    getElementsByTagName: (tag) => ({ [Symbol.iterator]: function* () { if (sheet && tag === 'bottom-sheet-layout') yield sheet; } }) };
  const context = { document: doc, location: { hash: '' }, innerWidth: 360, innerHeight: 656, history: { back() {}, go() {} },
    Element: Object, getComputedStyle: () => ({ display: 'block', visibility: 'visible' }),
    requestAnimationFrame: (fn) => { rafs.push(fn); return rafs.length; },
    addEventListener: (type, handler) => listeners.set(type, handler),
    MutationObserver: function (cb) { context.__mutate = cb; this.observe = () => {}; },
    Lite: { bridge: () => ({ setTransientUi: (...a) => reports.push(a), currentDocumentGeneration: () => 7 }) },
    JSON, Array, Math, Object, RegExp };
  context.window = context; context.self = context; context.top = context;
  vm.runInNewContext(script, context);
  const flush = () => { while (rafs.length) rafs.shift()(); };
  return { listeners, reports, flush, mutate: (records) => context.__mutate(records), queries: () => queries, reset: () => { queries = 0; }, open: () => { sheet = node('sheet'); } };
}
test('a scroll with nothing shown does not walk the document', () => {
  const p = page(); p.flush(); p.reset();
  p.listeners.get('scroll')(); p.flush();
  assert.equal(p.queries(), 0);
  assert.equal(p.reports.length, 1); // the initial 'none'
});
test('one sync queries the document once per selector', () => {
  const p = page(); p.flush(); p.reset();
  p.listeners.get('hashchange')(); p.flush();
  assert.equal(p.queries(), 2); // one class query for menu, one for search, not 2x each
});
test('scrolling re-measures an open sheet', () => {
  const p = page(); p.flush(); p.open();
  p.listeners.get('hashchange')(); p.flush();
  assert.equal(p.reports.at(-1)[0], 'media_menu');
  p.reset(); p.listeners.get('scroll')(); p.flush();
  assert.equal(p.reports.at(-1)[0], 'media_menu');
  assert.ok(p.queries() > 0);
});

test('thumbnail class flips while scrolling do not schedule a sync', () => {
  const p = page(); p.flush(); p.reset();
  const img = { nodeType: 1, firstElementChild: null, matches: () => false };
  p.mutate([{ type: 'attributes', target: img, addedNodes: [], removedNodes: [] }]); p.flush();
  assert.equal(p.queries(), 0);
  const container = { nodeType: 1, firstElementChild: img, matches: () => false };
  p.mutate([{ type: 'attributes', target: container, addedNodes: [], removedNodes: [] }]); p.flush();
  assert.ok(p.queries() > 0);
  p.reset(); p.mutate([{ type: 'childList', target: container, addedNodes: [img], removedNodes: [] }]); p.flush();
  assert.ok(p.queries() > 0);
});
