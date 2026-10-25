/**
 * DanceBook Splash Page Controller
 *
 * Checks if the Cloud Run app container is awake by issuing a cross-origin
 * probe to /login (public endpoint). Once an opaque response resolves,
 * redirects the user to the corresponding deep link on the app.
 */

const DEFAULT_TARGET_BASE = 'https://dancebook-918960884147.europe-central2.run.app';

const MESSAGES = {
  pl: {
    tagline: 'Twoja osobista biblioteka taneczna',
    statusInitial: 'Łączenie z DanceBook...',
    detailWaking: 'DanceBook budzi się po okresie bezczynności. To może zająć jeszcze chwilę...',
    statusSlow: 'Uruchamianie trwa dłużej niż zwykle',
    detailSlow: 'Serwer potrzebuje więcej czasu na start. Możesz poczekać jeszcze chwilę lub spróbować ponownie.',
    retryButton: 'Spróbuj ponownie',
    directLink: 'Otwórz bezpośrednio'
  },
  en: {
    tagline: 'Your personal dance library',
    statusInitial: 'Connecting to DanceBook...',
    detailWaking: 'DanceBook is waking up after being idle. This may take another moment...',
    statusSlow: 'Taking longer than usual',
    detailSlow: 'The server is taking some time to start. You can wait a moment longer or try again.',
    retryButton: 'Try again',
    directLink: 'Open directly'
  }
};

/**
 * Normalizes a URL pathname by collapsing leading slashes and converting backslashes,
 * preventing open redirects via protocol-relative paths like //evil.example.
 *
 * @param {string} pathname
 * @returns {string} Sanitized absolute path
 */
function sanitizePath(pathname) {
  let p = pathname || '/';
  p = p.replace(/\\/g, '/');
  p = p.replace(/^\/+/, '/');
  if (!p.startsWith('/')) {
    p = '/' + p;
  }
  return p;
}

/**
 * Computes the target redirect URL on the Cloud Run app preserving pathname, query and hash.
 * Guarantees that the redirect target's origin strictly matches targetBase.
 *
 * @param {string|Location|URL} current Current URL, Location, or path string
 * @param {string} [targetBase=DEFAULT_TARGET_BASE] Target origin base URL
 * @returns {string} Fully qualified redirect URL strictly on the target origin
 */
function computeRedirectUrl(current, targetBase = DEFAULT_TARGET_BASE) {
  const target = new URL(targetBase);
  let pathname = '/';
  let search = '';
  let hash = '';

  if (typeof current === 'string') {
    if (current.startsWith('http://') || current.startsWith('https://')) {
      const parsed = new URL(current);
      pathname = parsed.pathname;
      search = parsed.search;
      hash = parsed.hash;
    } else {
      const normalized = current.replace(/^[/\\]+/, '/');
      const parsed = new URL(normalized, 'http://localhost');
      pathname = parsed.pathname;
      search = parsed.search;
      hash = parsed.hash;
    }
  } else if (current && typeof current === 'object') {
    pathname = current.pathname || '/';
    search = current.search || '';
    hash = current.hash || '';
  }

  target.pathname = sanitizePath(pathname);
  target.search = search;
  target.hash = hash;

  return target.toString();
}

/**
 * Resolves the target base origin safely.
 *
 * Query parameter override (?target=) is ONLY accepted if:
 * 1. The splash page itself is running on localhost or 127.0.0.1
 * 2. The target value parsed as a valid URL has hostname exactly localhost or 127.0.0.1
 * 3. No userinfo is present (rejects http://localhost@evil.example)
 *
 * On any deployed host (e.g. dancebook-a6588.web.app), ?target= is strictly ignored.
 */
function resolveTargetBase(optionsTarget, searchParams, currentLocation) {
  if (optionsTarget) {
    return optionsTarget;
  }

  const queryTarget = searchParams ? searchParams.get('target') : null;
  if (!queryTarget) {
    return DEFAULT_TARGET_BASE;
  }

  const currentHost = currentLocation && currentLocation.hostname;
  const isLocalHost = currentHost === 'localhost' || currentHost === '127.0.0.1';
  if (!isLocalHost) {
    return DEFAULT_TARGET_BASE;
  }

  try {
    const parsed = new URL(queryTarget);
    if ((parsed.protocol === 'http:' || parsed.protocol === 'https:') &&
        (parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1') &&
        !parsed.username && !parsed.password) {
      return parsed.origin;
    }
  } catch (_) {
    // Malformed URL - ignore
  }

  return DEFAULT_TARGET_BASE;
}

/**
 * Detects whether Polish or English should be displayed.
 *
 * @param {string} [navLanguage] Value of navigator.language
 * @param {URLSearchParams} [searchParams] URL search params (to support ?lang= override)
 * @returns {'pl'|'en'}
 */
function detectLanguage(navLanguage, searchParams) {
  if (searchParams && typeof searchParams.get === 'function') {
    const override = searchParams.get('lang');
    if (override) {
      return override.toLowerCase().startsWith('pl') ? 'pl' : 'en';
    }
  }
  const lang = (navLanguage || '').toLowerCase();
  return lang.startsWith('pl') ? 'pl' : 'en';
}

/**
 * Initializes the splash screen lifecycle, DOM elements, timers and network probe.
 */
function initSplash(options = {}) {
  const loc = options.currentLocation || (typeof window !== 'undefined' ? window.location : null);
  const navLang = options.navigatorLanguage || (typeof navigator !== 'undefined' ? navigator.language : 'en');
  const onRedirect = options.onRedirect || ((url) => {
    if (typeof window !== 'undefined' && window.location) {
      window.location.replace(url);
    }
  });
  const fetchFn = options.fetchFn || (typeof window !== 'undefined' ? window.fetch.bind(window) : null);

  const wakeThresholdMs = options.wakeThresholdMs != null ? options.wakeThresholdMs : 20000;
  const slowThresholdMs = options.slowThresholdMs != null ? options.slowThresholdMs : 50000;
  const probeTimeoutMs = options.probeTimeoutMs != null ? options.probeTimeoutMs : 25000;
  const timerIntervalMs = options.timerIntervalMs != null ? options.timerIntervalMs : 1000;

  if (!loc) {
    return { error: 'No location available' };
  }

  const searchParams = loc.search ? new URLSearchParams(loc.search) : new URLSearchParams();
  const targetBase = resolveTargetBase(options.targetBaseUrl, searchParams, loc);

  const lang = detectLanguage(navLang, searchParams);
  const msgs = MESSAGES[lang];

  const targetRedirectUrl = computeRedirectUrl(loc, targetBase);
  const probeUrlBase = options.probeUrl || new URL('/login', targetBase).toString();

  // DOM Elements
  const taglineEl = options.taglineEl || (typeof document !== 'undefined' ? document.getElementById('brand-tagline') : null);
  const statusEl = options.statusEl || (typeof document !== 'undefined' ? document.getElementById('status-message') : null);
  const detailEl = options.detailEl || (typeof document !== 'undefined' ? document.getElementById('status-detail') : null);
  const retrySectionEl = options.retrySectionEl || (typeof document !== 'undefined' ? document.getElementById('retry-section') : null);
  const retryBtn = options.retryBtn || (typeof document !== 'undefined' ? document.getElementById('retry-btn') : null);
  const directLinkEl = options.directLinkEl || (typeof document !== 'undefined' ? document.getElementById('direct-link') : null);

  // Apply initial translations
  if (typeof document !== 'undefined' && document.documentElement) {
    document.documentElement.lang = lang;
  }
  if (taglineEl) taglineEl.textContent = msgs.tagline;
  if (statusEl) statusEl.textContent = msgs.statusInitial;
  if (detailEl) {
    detailEl.textContent = '';
    detailEl.hidden = true;
  }
  if (retrySectionEl) retrySectionEl.hidden = true;
  if (retryBtn) retryBtn.textContent = msgs.retryButton;
  if (directLinkEl) {
    directLinkEl.textContent = msgs.directLink;
    directLinkEl.href = targetRedirectUrl;
  }

  let isAwake = false;
  let isDestroyed = false;
  let startTime = Date.now();
  let timerInterval = null;

  let currentProbeId = 0;
  let probeRetryTimer = null;
  let probeTimeoutTimer = null;
  let activeAbortController = null;

  function cancelProbeTimers() {
    if (probeRetryTimer) {
      clearTimeout(probeRetryTimer);
      probeRetryTimer = null;
    }
    if (probeTimeoutTimer) {
      clearTimeout(probeTimeoutTimer);
      probeTimeoutTimer = null;
    }
  }

  function updateTimerUI() {
    const elapsedMs = Date.now() - startTime;

    if (elapsedMs >= slowThresholdMs) {
      if (statusEl) statusEl.textContent = msgs.statusSlow;
      if (detailEl) {
        detailEl.textContent = msgs.detailSlow;
        detailEl.hidden = false;
      }
      if (retrySectionEl) retrySectionEl.hidden = false;
    } else if (elapsedMs >= wakeThresholdMs) {
      if (detailEl) {
        detailEl.textContent = msgs.detailWaking;
        detailEl.hidden = false;
      }
    }
  }

  function handleAwake() {
    if (isAwake || isDestroyed) return;
    isAwake = true;
    cleanup();
    onRedirect(targetRedirectUrl);
  }

  function startProbe() {
    if (isAwake || isDestroyed || !fetchFn) return;

    cancelProbeTimers();

    if (activeAbortController) {
      try {
        activeAbortController.abort();
      } catch (_) {}
      activeAbortController = null;
    }

    const thisProbeId = ++currentProbeId;
    const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    activeAbortController = controller;

    const separator = probeUrlBase.includes('?') ? '&' : '?';
    const cacheBusterUrl = `${probeUrlBase}${separator}_wake=${Date.now()}`;

    if (controller && probeTimeoutMs > 0) {
      probeTimeoutTimer = setTimeout(() => {
        if (thisProbeId !== currentProbeId || isAwake || isDestroyed) return;
        try {
          controller.abort();
        } catch (_) {}
        startProbe();
      }, probeTimeoutMs);
    }

    const fetchOptions = {
      mode: 'no-cors',
      cache: 'no-store'
    };
    if (controller) {
      fetchOptions.signal = controller.signal;
    }

    fetchFn(cacheBusterUrl, fetchOptions)
      .then(() => {
        if (thisProbeId !== currentProbeId || isAwake || isDestroyed) return;
        cancelProbeTimers();
        handleAwake();
      })
      .catch(() => {
        // Stop quietly if superseded, destroyed, awake, or aborted
        if (thisProbeId !== currentProbeId || isAwake || isDestroyed) return;
        if (controller && controller.signal && controller.signal.aborted) return;

        cancelProbeTimers();
        // Schedule single retry after 2 seconds
        probeRetryTimer = setTimeout(startProbe, 2000);
      });
  }

  function resetAndRetry() {
    startTime = Date.now();
    cancelProbeTimers();
    if (statusEl) statusEl.textContent = msgs.statusInitial;
    if (detailEl) {
      detailEl.textContent = '';
      detailEl.hidden = true;
    }
    if (retrySectionEl) retrySectionEl.hidden = true;
    startProbe();
  }

  if (retryBtn) {
    retryBtn.addEventListener('click', resetAndRetry);
  }

  timerInterval = setInterval(updateTimerUI, timerIntervalMs);
  startProbe();

  function cleanup() {
    isDestroyed = true;
    cancelProbeTimers();
    if (timerInterval) {
      clearInterval(timerInterval);
      timerInterval = null;
    }
    if (activeAbortController) {
      try {
        activeAbortController.abort();
      } catch (_) {}
      activeAbortController = null;
    }
    if (retryBtn) {
      retryBtn.removeEventListener('click', resetAndRetry);
    }
  }

  return {
    lang,
    targetRedirectUrl,
    cleanup,
    resetAndRetry,
    isAwake: () => isAwake,
    getCurrentProbeId: () => currentProbeId
  };
}

// Auto-initialize when running in browser
if (typeof window !== 'undefined' && typeof document !== 'undefined') {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => initSplash());
  } else {
    initSplash();
  }
}

// CommonJS export for Node test runner
if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    DEFAULT_TARGET_BASE,
    MESSAGES,
    sanitizePath,
    computeRedirectUrl,
    resolveTargetBase,
    detectLanguage,
    initSplash
  };
}
