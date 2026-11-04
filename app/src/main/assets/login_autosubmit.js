/*
 * CC-BizMate login automation, executed ONLY in a trusted WebView origin.
 * The three JSON string literals are substituted by Java before injection.
 * No credentials or form values are collected by the screen inspection feature.
 */
(function () {
  try {
    const company = __COMPANY_JSON__;
    const account = __ACCOUNT_JSON__;
    const password = __PASSWORD_JSON__;
    const configuredLoginSelector = __LOGIN_SELECTOR_JSON__;
    const configuredLoginLabel = __LOGIN_LABEL_JSON__;
    const visible = e => !e.disabled && !e.readOnly && e.getClientRects().length > 0;
    const inputs = Array.from(document.querySelectorAll('input')).filter(visible);
    const pw = inputs.filter(e => e.type === 'password');
    const plain = inputs.filter(e => ['text','email','number','tel'].includes(e.type));
    if (pw.length !== 1 || plain.length !== 2) return 'NEED_MANUAL:FIELDS';
    const form = pw[0].form || pw[0].closest('form');
    if (form && (!form.contains(plain[0]) || !form.contains(plain[1]))) return 'NEED_MANUAL:FORM';
    const put = (element, value) => {
      const desc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
      if (desc && desc.set) desc.set.call(element, value);
      else element.value = value;
      element.dispatchEvent(new Event('input', { bubbles: true }));
      element.dispatchEvent(new Event('change', { bubbles: true }));
    };
    put(plain[0], company);
    put(plain[1], account);
    put(pw[0], password);

    // An explicitly registered login selector is the most reliable for custom controls.
    if (configuredLoginSelector) {
      const target = document.querySelector(configuredLoginSelector);
      const targetCaption = target && (target.innerText || target.value || target.getAttribute('aria-label') ||
          target.getAttribute('title') || target.id || target.getAttribute('name') || '').replace(/\s+/g,' ').trim();
      if (target && visible(target) && typeof target.click === 'function' &&
          (!configuredLoginLabel || targetCaption === configuredLoginLabel)) {
        target.click();
        return 'SUBMITTED:LEARNED';
      }
    }

    // ① Prefer one unequivocally named, clickable login control.
    const all = Array.from(document.querySelectorAll('button,input[type=submit],input[type=image],input[type=button],a,[role=button],[onclick]'))
      .filter(e => visible(e) && typeof e.click === 'function');
    const caption = e => [e.innerText, e.value, e.getAttribute('aria-label'), e.getAttribute('title')]
      .filter(Boolean).join(' ').replace(/\s+/g, ' ').trim();
    const loginName = /^(?:ログイン|ログインする|サインイン|sign\s*in|log\s*in|login)$/i;
    const exact = all.filter(e => loginName.test(caption(e)) && (!form || !e.form || e.form === form));
    if (exact.length === 1) {
      exact[0].click();
      return 'SUBMITTED:CLICK';
    }

    // Login link with an image or custom handler: identify by stable element metadata.
    const byId = all.filter(e => /(?:login|signin|sign-in|ログイン)/i.test(
      [e.id, e.getAttribute('name')].filter(Boolean).join(' ')) &&
      (!form || !e.form || e.form === form));
    if (byId.length === 1) {
      byId[0].click();
      return 'SUBMITTED:ID';
    }

    // ② Submit using the unique submit control, even if it is icon-only or has
    //    no visible text. requestSubmit runs the form's submit listeners and validation.
    if (form) {
      const submits = Array.from(form.querySelectorAll('button,input[type=submit],input[type=image]'))
        .filter(e => visible(e) && (e.tagName.toLowerCase() !== 'button' || (e.type || 'submit') === 'submit') &&
                (e.tagName.toLowerCase() === 'button' || ['submit', 'image'].includes(e.type)));
      if (submits.length === 1) {
        if (typeof form.requestSubmit === 'function' && submits[0].type !== 'image') form.requestSubmit(submits[0]);
        else submits[0].click();
        return 'SUBMITTED:FORM_BUTTON';
      }
      // ③ A password-containing form with no submit control: submit its own form.
      // requestSubmit triggers validation and submit listeners; do not call form.submit()
      // because that would bypass submit handlers / CSRF setup.
      if (submits.length === 0 && typeof form.requestSubmit === 'function') {
        form.requestSubmit();
        return 'SUBMITTED:FORM';
      }
    }
    return 'FILLED_ONLY:AMBIGUOUS_SUBMIT';
  } catch (_) {
    return 'ERROR:LOGIN_SCRIPT';
  }
})();
