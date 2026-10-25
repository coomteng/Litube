(() => {
  'use strict';
  if (window.top !== window.self || window.__feedPrefetch) return;
  window.__feedPrefetch = true;
  // m.youtube.com lazy-loads the feed through IntersectionObservers with rootMargin 10%: the
  // continuation sentinel (one /browse per crossing, 4-5 cards each) and every thumbnail <img>.
  // On a tablet one continuation is a single row, and each row costs a serial round trip plus
  // YouTube's 150-400 ms append, so a swipe lands on a spinner. Widening the sentinel margin
  // makes the page fetch the next rows while the current ones are still on screen; widening
  // the thumbnail margin starts the image download a viewport earlier. The page's own code is
  // untouched: only the two observers created with that margin are replaced by wider ones.
  // Feed pages only (home, subscriptions, channel tabs); the margin stays open-ended but
  // self-limiting: the sentinel stops crossing once it sits 3 viewports below the fold.
  const SENTINEL_MARGIN = '300%';
  const THUMBNAIL_MARGIN = '100%';
  const LAZY_MARGIN = /^10%(?:\s+10%){0,3}$/;
  const FEED_PATH = /^\/(?:$|feed\/|channel\/|c\/|user\/|@)/;
  const Original = window.IntersectionObserver;
  if (typeof Original !== 'function') return;
  const feedPage = () => FEED_PATH.test(location.pathname) && !navigator.connection?.saveData;
  const lazy = (options) => !!options && !options.root && LAZY_MARGIN.test(String(options.rootMargin || '').trim());
  const sentinel = (target) => target instanceof Element && target.tagName === 'YTM-CONTINUATION-ITEM-RENDERER';
  function Observer(callback, options) {
    if (!lazy(options)) return new Original(callback, options);
    // Three observers share the callback; the target decides which one tracks it at observe()
    // time (so SPA navigation to a non-feed page keeps YouTube's own margin). unobserve/
    // disconnect fan out, which is harmless for targets the others never saw.
    const plain = new Original(callback, options);
    const wide = new Original(callback, Object.assign({}, options, { rootMargin: SENTINEL_MARGIN }));
    const near = new Original(callback, Object.assign({}, options, { rootMargin: THUMBNAIL_MARGIN }));
    const all = [plain, wide, near];
    const pick = (target) => !feedPage() ? plain : sentinel(target) ? wide : near;
    return {
      observe: (target) => pick(target).observe(target),
      unobserve: (target) => all.forEach(observer => observer.unobserve(target)),
      disconnect: () => all.forEach(observer => observer.disconnect()),
      takeRecords: () => all.flatMap(observer => observer.takeRecords()),
      get root() { return plain.root; },
      get rootMargin() { return plain.rootMargin; },
      get thresholds() { return plain.thresholds; },
    };
  }
  Observer.prototype = Original.prototype;
  window.IntersectionObserver = Observer;
})();
