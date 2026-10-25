(() => {
  'use strict';
  if (window.top !== window.self || !window.Lite || window.__ui) return;
  window.__ui = true;

  const back = history.back;
  const go = history.go;
  // sync() runs on every mutation batch while the feed scrolls, so the
  // "is a sheet/search open" probe has to be cheap: querySelectorAll over
  // custom tag names walks the whole document (≈1 ms per 3k nodes, ×4 per
  // sync), while live getElementsByTagName collections and class selectors
  // are indexed. Tags and class parts are therefore looked up separately.
  const MENU_TAGS = ['bottom-sheet-layout', 'ytm-bottom-sheet-renderer', 'ytm-app-bottom-sheet-layout', 'ytm-menu-popup-renderer'];
  const MENU_CLASS = '.menu-content[role="dialog"]';
  const ITEMS = 'ytm-menu-service-item-renderer,yt-list-item-view-model,toggleable-list-item-view-model,.bottom-sheet-media-menu-item,[data-injected="queue-item"]';
  const SEARCH_TAGS = ['ytm-searchbox', 'ytm-searchbox-renderer', 'ytm-search-suggestions-section'];
  const SEARCH_CLASS = '.searchbox';
  const menuTags = MENU_TAGS.map(tag => document.getElementsByTagName(tag));
  const searchTags = SEARCH_TAGS.map(tag => document.getElementsByTagName(tag));
  function candidates(collections, selector) {
    const out = [];
    for (const collection of collections) for (const node of collection) out.push(node);
    if (selector) for (const node of document.querySelectorAll(selector)) out.push(node);
    return out;
  }
  let generation = null;
  let reported = null;
  let scheduled = false;
  let shown = false;

  function visible(node) {
    if (!(node instanceof Element) || node.closest('[hidden],[aria-hidden="true"]')) return false;
    const style = getComputedStyle(node);
    const rect = node.getBoundingClientRect();
    return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
  }

  function menu() {
    return candidates(menuTags, MENU_CLASS).find(node => visible(node) && node.querySelector(ITEMS)) || null;
  }

  function search() {
    const hash = /^#(?:searching|search)(?:[=&]|$)/.test(location.hash);
    return candidates(searchTags, SEARCH_CLASS).find(node => visible(node) &&
      (hash || node.contains(document.activeElement) && document.activeElement.matches('input'))) || null;
  }

  function kind() {
    return menu() ? 'media_menu' : search() ? 'search' : 'none';
  }

  function geometry(sheet, box) {
    const content = sheet?.querySelector('.menu-content,[role="dialog"]');
    const nodes = sheet ? content && visible(content) ? [content] : Array.from(sheet.querySelectorAll(ITEMS))
      : box ? candidates(searchTags, SEARCH_CLASS) : [];
    const regions = nodes.filter(visible).slice(0, 32).map(node => {
      const r = node.getBoundingClientRect();
      return [r.left, r.top, r.right, r.bottom].map(edge => Math.round(edge * 100) / 100);
    });
    return JSON.stringify({ viewportWidth: innerWidth, regions });
  }

  function sync() {
    scheduled = false;
    if (document.readyState === 'loading') return;
    const bridge = Lite.bridge();
    if (!bridge || typeof bridge.setTransientUi !== 'function') return;
    if (generation === null) generation = bridge.currentDocumentGeneration();
    // One pass over the document: menu()/search() each walk the whole feed
    // (≈5 ms at 100 cards), and sync runs on every mutation batch while scrolling.
    const sheet = menu();
    const box = sheet ? null : search();
    const value = sheet ? 'media_menu' : box ? 'search' : 'none';
    shown = value !== 'none';
    const bounds = geometry(sheet, box);
    const stamp = value + bounds;
    if (reported === stamp) return;
    reported = stamp;
    bridge.setTransientUi(value, generation, bounds);
  }

  function schedule() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(sync);
  }

  function dismiss(target = null) {
    const sheet = target ? null : menu();
    const panel = target || sheet || search();
    if (!visible(panel)) return false;
    const selectors = target
      ? '.dialog-close-button,.dialog-dismiss-button,.dismiss-button,.close-button,button[aria-label="Close"],button[aria-label="Dismiss"]'
      : sheet
      ? '.bottom-sheet-overlay,.bottom-sheet-scrim,.popup-backdrop,.menu-overlay,tp-yt-iron-overlay-backdrop'
      : '.searchbox-back-button,ytm-back-button button,button[aria-label="Back"]';
    const control = Array.from((sheet ? document : panel).querySelectorAll(selectors)).find(visible);
    if (control) {
      control.click();
      schedule();
      return true;
    }
    if (target && typeof target.close === 'function') {
      target.close();
      schedule();
      return true;
    }
    // Close hash-based panels through the page history.
    if (/^#(?:searching|search|menu|dialog|bottom-sheet)(?:[=&]|$)/.test(location.hash) && history.length > 1) {
      back.call(history);
      schedule();
      return true;
    }
    if (sheet) {
      // Dispatch dismissal to the sheet's backdrop handler.
      const content = sheet.querySelector('.menu-content,[role="dialog"]') || sheet;
      const rect = content.getBoundingClientRect();
      const x = Math.max(1, Math.min(innerWidth - 1, innerWidth / 2));
      const y = rect.top > 1 ? 1 : Math.min(innerHeight - 1, rect.bottom + 1);
      if (sheet !== content) {
        // Target the sheet backdrop itself; the content behind it may be a video link.
        sheet.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, clientX: x, clientY: y }));
        schedule();
        return true;
      }
    }
    return false;
  }

  Lite.ui = { kind, dismiss, originalBack: () => back.call(history), originalGo: delta => go.call(history, delta) };
  const CANDIDATE = [...MENU_TAGS, ...SEARCH_TAGS].join() + ',' + MENU_CLASS + ',' + SEARCH_CLASS;
  /** A feed scroll produces ~20 mutation batches per second, nearly all
   *  class flips on <img>/<span> as thumbnails lazy-load. A sheet or search
   *  panel can only appear through an added element or an attribute change
   *  on a node that is (or can contain) one; leaf attribute changes are
   *  skipped unless something is already shown and needs re-measuring. */
  function relevant(records) {
    if (shown) return true;
    for (const record of records) {
      if (record.type !== 'attributes') {
        for (const node of record.addedNodes) if (node.nodeType === 1) return true;
        for (const node of record.removedNodes) if (node.nodeType === 1) return true;
        continue;
      }
      const target = record.target;
      if (target.firstElementChild || target.matches?.(CANDIDATE)) return true;
    }
    return false;
  }
  function init() {
    new MutationObserver(records => { if (relevant(records)) schedule(); }).observe(document.documentElement,
      { childList: true, subtree: true, attributes: true, attributeFilter: ['hidden', 'aria-hidden', 'class', 'style', 'open'] });
    sync();
  }
  window.addEventListener('hashchange', schedule);
  window.addEventListener('popstate', schedule);
  window.addEventListener('resize', schedule);
  // Scrolling only moves an already-open sheet or search panel; opening one
  // goes through the mutation observer, the hash, or focus.
  document.addEventListener('scroll', () => { if (shown) schedule(); }, true);
  document.addEventListener('transitionend', schedule, true);
  document.addEventListener('animationend', schedule, true);
  window.addEventListener('tabVisibilityChanged', () => { reported = null; schedule(); });
  document.addEventListener('focusin', schedule, true);
  document.addEventListener('focusout', schedule, true);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();
})();
