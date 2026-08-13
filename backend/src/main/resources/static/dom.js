// Shared DOM helper module for the FinSight static UI.
//
// Centralises HTML escaping and small helpers so each workspace script can call them
// without re-implementing the same escaping rules. The module is loaded via
// <script type="module"> and exposes a single `finsight.dom` namespace on `window`.
(function () {
  const ESCAPE_MAP = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  };

  function escapeHtml(value) {
    if (value === null || value === undefined) {
      return '';
    }
    return String(value).replace(/[&<>"']/g, (ch) => ESCAPE_MAP[ch]);
  }

  function setText(element, value) {
    if (!element) {
      return;
    }
    element.textContent = value === null || value === undefined ? '' : String(value);
  }

  function clearChildren(element) {
    if (!element) {
      return;
    }
    while (element.firstChild) {
      element.removeChild(element.firstChild);
    }
  }

  function createElement(tag, options) {
    const el = document.createElement(tag);
    if (!options) {
      return el;
    }
    if (options.className) el.className = options.className;
    if (options.text !== undefined) el.textContent = options.text;
    if (options.attrs) {
      Object.keys(options.attrs).forEach((name) => {
        el.setAttribute(name, options.attrs[name]);
      });
    }
    return el;
  }

  window.finsight = window.finsight || {};
  window.finsight.dom = {
    escapeHtml: escapeHtml,
    setText: setText,
    clearChildren: clearChildren,
    createElement: createElement
  };
})();
