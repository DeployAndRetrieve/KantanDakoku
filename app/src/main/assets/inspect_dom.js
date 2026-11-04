/* READ-ONLY. Never call click, submit, fetch, XHR, or read .value. */
(function () {
  try {
    const safe = x => (String(x || '').replace(/[\r\n\t]/g, ' ').slice(0, 65));
    const shown = e => e.getClientRects().length > 0;
    const allForms = Array.from(document.querySelectorAll('form'));
    const fields = Array.from(document.querySelectorAll('input,select,textarea'))
      .filter(shown).slice(0, 90).map((e, i) => {
        // Never access e.value, defaultValue, selectedOptions, checked, or innerHTML.
        const id = e.getAttribute('id') || '';
        let label = '';
        if (id) {
          // CSS.escape is available on modern Android WebView. No unsafe interpolation.
          const el = document.querySelector('label[for="' + CSS.escape(id) + '"]');
          if (el) label = safe(el.textContent);
        }
        if (!label) label = safe(e.getAttribute('aria-label') || '');
        return {
          n: i,
          tag: e.tagName.toLowerCase(),
          type: safe(e.getAttribute('type') || ''),
          id: safe(id),
          name: safe(e.getAttribute('name') || ''),
          label,
          placeholder: safe(e.getAttribute('placeholder') || ''),
          required: e.hasAttribute('required'),
          disabled: e.hasAttribute('disabled')
        };
      });
    const controls = Array.from(document.querySelectorAll('button,a,[role=button],input[type=submit],input[type=button]'))
      .filter(shown).slice(0, 65).map((e, i) => ({
        n: i,
        tag: e.tagName.toLowerCase(),
        type: safe(e.getAttribute('type') || ''),
        id: safe(e.getAttribute('id') || ''),
        label: safe(e.innerText || e.getAttribute('aria-label') || e.getAttribute('title') || (e.tagName === 'INPUT' ? e.getAttribute('value') || '' : ''))
      }));
    return JSON.stringify({
      format: 'kantan-screen-structure-v1',
      pagePath: location.pathname, // deliberately excludes URL query tokens
      forms: allForms.length,
      fields,
      controls,
      notice: 'No input values, cookies, tokens, credentials, or DOM HTML collected.'
    });
  } catch (_) {
    return 'ERROR:READ_ONLY_INSPECTION';
  }
})();
