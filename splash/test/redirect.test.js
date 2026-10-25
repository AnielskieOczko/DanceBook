const test = require('node:test');
const assert = require('node:assert/strict');
const {
  DEFAULT_TARGET_BASE,
  MESSAGES,
  computeRedirectUrl,
  resolveTargetBase,
  detectLanguage,
  initSplash
} = require('../splash.js');

const TARGET_ORIGIN = new URL(DEFAULT_TARGET_BASE).origin;

test('computeRedirectUrl: root path maps to target root', () => {
  const result = computeRedirectUrl('https://dancebook-a6588.web.app/');
  assert.equal(result, 'https://dancebook-918960884147.europe-central2.run.app/');
  assert.equal(new URL(result).origin, TARGET_ORIGIN);
});

test('computeRedirectUrl: deep path without query is preserved', () => {
  const result = computeRedirectUrl('https://dancebook-a6588.web.app/materials/42');
  assert.equal(result, 'https://dancebook-918960884147.europe-central2.run.app/materials/42');
  assert.equal(new URL(result).origin, TARGET_ORIGIN);
});

test('computeRedirectUrl: deep path with query string is preserved', () => {
  const result = computeRedirectUrl('https://dancebook-a6588.web.app/materials/42?tag=latin&sort=name');
  assert.equal(result, 'https://dancebook-918960884147.europe-central2.run.app/materials/42?tag=latin&sort=name');
  assert.equal(new URL(result).origin, TARGET_ORIGIN);
});

test('computeRedirectUrl: deep path with query and hash is preserved', () => {
  const result = computeRedirectUrl('https://dancebook-a6588.web.app/choreographies/5?edit=true#section-steps');
  assert.equal(result, 'https://dancebook-918960884147.europe-central2.run.app/choreographies/5?edit=true#section-steps');
  assert.equal(new URL(result).origin, TARGET_ORIGIN);
});

test('computeRedirectUrl: encodes spaces and unicode in path and query', () => {
  const result = computeRedirectUrl('https://dancebook-a6588.web.app/dance-figures/walc angielski?query=krok zażółć');
  assert.ok(result.startsWith('https://dancebook-918960884147.europe-central2.run.app/dance-figures/walc%20angielski'));
  assert.ok(result.includes('query=krok%20za%C5%BC%C3%B3%C5%82%C4%87'));
  assert.equal(new URL(result).origin, TARGET_ORIGIN);
});

test('computeRedirectUrl: already percent-encoded path is not double encoded', () => {
  const result = computeRedirectUrl('https://dancebook-a6588.web.app/dance-figures/walc%20angielski?tag=standard');
  assert.equal(result, 'https://dancebook-918960884147.europe-central2.run.app/dance-figures/walc%20angielski?tag=standard');
  assert.equal(new URL(result).origin, TARGET_ORIGIN);
});

test('computeRedirectUrl: prevents open redirect on double slash //evil.example/x', () => {
  const result = computeRedirectUrl('https://dancebook-a6588.web.app//evil.example/x');
  assert.equal(new URL(result).origin, TARGET_ORIGIN);
  assert.equal(result, `${DEFAULT_TARGET_BASE}/evil.example/x`);

  const relResult = computeRedirectUrl('//evil.example/x');
  assert.equal(new URL(relResult).origin, TARGET_ORIGIN);
  assert.equal(relResult, `${DEFAULT_TARGET_BASE}/evil.example/x`);
});

test('computeRedirectUrl: prevents open redirect on triple slash ///evil.example', () => {
  const result = computeRedirectUrl('https://dancebook-a6588.web.app///evil.example');
  assert.equal(new URL(result).origin, TARGET_ORIGIN);
  assert.equal(result, `${DEFAULT_TARGET_BASE}/evil.example`);

  const relResult = computeRedirectUrl('///evil.example');
  assert.equal(new URL(relResult).origin, TARGET_ORIGIN);
  assert.equal(relResult, `${DEFAULT_TARGET_BASE}/evil.example`);
});

test('computeRedirectUrl: prevents open redirect on slash backslash /\\evil.example', () => {
  const result = computeRedirectUrl('https://dancebook-a6588.web.app/\\evil.example');
  assert.equal(new URL(result).origin, TARGET_ORIGIN);
  assert.equal(result, `${DEFAULT_TARGET_BASE}/evil.example`);

  const relResult = computeRedirectUrl('/\\evil.example');
  assert.equal(new URL(relResult).origin, TARGET_ORIGIN);
  assert.equal(relResult, `${DEFAULT_TARGET_BASE}/evil.example`);
});

test('computeRedirectUrl: prevents open redirect on encoded slash /%2F/evil.example', () => {
  const result = computeRedirectUrl('https://dancebook-a6588.web.app/%2F/evil.example');
  assert.equal(new URL(result).origin, TARGET_ORIGIN);
  assert.equal(result, `${DEFAULT_TARGET_BASE}/%2F/evil.example`);

  const relResult = computeRedirectUrl('/%2F/evil.example');
  assert.equal(new URL(relResult).origin, TARGET_ORIGIN);
  assert.equal(relResult, `${DEFAULT_TARGET_BASE}/%2F/evil.example`);
});

test('computeRedirectUrl: path with query and hash retains target origin', () => {
  const result = computeRedirectUrl('https://dancebook-a6588.web.app//evil.example/path?q=1#hash');
  assert.equal(new URL(result).origin, TARGET_ORIGIN);
  assert.equal(result, `${DEFAULT_TARGET_BASE}/evil.example/path?q=1#hash`);
});

test('computeRedirectUrl: works with custom targetBaseUrl', () => {
  const result = computeRedirectUrl('https://custom-site.web.app/test?a=1', 'http://localhost:8080');
  assert.equal(result, 'http://localhost:8080/test?a=1');
  assert.equal(new URL(result).origin, 'http://localhost:8080');
});

test('computeRedirectUrl: works with Location-like object', () => {
  const mockLocation = {
    pathname: '/lists/7',
    search: '?view=compact',
    hash: '#top'
  };
  const result = computeRedirectUrl(mockLocation);
  assert.equal(result, 'https://dancebook-918960884147.europe-central2.run.app/lists/7?view=compact#top');
  assert.equal(new URL(result).origin, TARGET_ORIGIN);
});

test('resolveTargetBase: deployed host strictly rejects ?target= override', () => {
  const deployedLoc = { hostname: 'dancebook-a6588.web.app' };

  const params1 = new URLSearchParams('target=http://localhost:8080');
  assert.equal(resolveTargetBase(null, params1, deployedLoc), DEFAULT_TARGET_BASE);

  const params2 = new URLSearchParams('target=https://evil.example');
  assert.equal(resolveTargetBase(null, params2, deployedLoc), DEFAULT_TARGET_BASE);
});

test('resolveTargetBase: rejects localhost.evil.example and localhost@evil.example on localhost', () => {
  const localLoc = { hostname: 'localhost' };

  const p1 = new URLSearchParams('target=http://localhost.evil.example');
  assert.equal(resolveTargetBase(null, p1, localLoc), DEFAULT_TARGET_BASE);

  const p2 = new URLSearchParams('target=http://localhost@evil.example');
  assert.equal(resolveTargetBase(null, p2, localLoc), DEFAULT_TARGET_BASE);

  const p3 = new URLSearchParams('target=http://user:pass@localhost:8080');
  assert.equal(resolveTargetBase(null, p3, localLoc), DEFAULT_TARGET_BASE);
});

test('resolveTargetBase: accepts valid localhost or 127.0.0.1 on local host', () => {
  const localLoc = { hostname: 'localhost' };

  const p1 = new URLSearchParams('target=http://localhost:8080');
  assert.equal(resolveTargetBase(null, p1, localLoc), 'http://localhost:8080');

  const p2 = new URLSearchParams('target=http://127.0.0.1:9090');
  assert.equal(resolveTargetBase(null, p2, localLoc), 'http://127.0.0.1:9090');
});

test('detectLanguage: detects Polish from navigator.language', () => {
  assert.equal(detectLanguage('pl'), 'pl');
  assert.equal(detectLanguage('pl-PL'), 'pl');
  assert.equal(detectLanguage('PL'), 'pl');
});

test('detectLanguage: defaults to English for other locales', () => {
  assert.equal(detectLanguage('en'), 'en');
  assert.equal(detectLanguage('en-US'), 'en');
  assert.equal(detectLanguage('de-DE'), 'en');
  assert.equal(detectLanguage(''), 'en');
  assert.equal(detectLanguage(null), 'en');
});

test('detectLanguage: respects query param override', () => {
  const paramsPl = new URLSearchParams('lang=pl');
  assert.equal(detectLanguage('en-US', paramsPl), 'pl');

  const paramsEn = new URLSearchParams('lang=en');
  assert.equal(detectLanguage('pl-PL', paramsEn), 'en');
});

test('initSplash: redirects immediately when app answers instantly', async () => {
  let redirectedUrl = null;
  const mockFetch = async () => ({ type: 'opaque', status: 0 });

  const splash = initSplash({
    currentLocation: { pathname: '/materials/10', search: '', hash: '', hostname: 'dancebook-a6588.web.app' },
    fetchFn: mockFetch,
    onRedirect: (url) => { redirectedUrl = url; }
  });

  await new Promise((r) => setTimeout(r, 10));

  assert.equal(splash.isAwake(), true);
  assert.equal(redirectedUrl, 'https://dancebook-918960884147.europe-central2.run.app/materials/10');
  splash.cleanup();
});

test('initSplash: handles 30s+ cold start delay then redirects', async () => {
  let redirectedUrl = null;
  let resolveProbe;
  const probePromise = new Promise((resolve) => {
    resolveProbe = resolve;
  });

  const mockFetch = () => probePromise;
  const statusEl = { textContent: '' };
  const detailEl = { textContent: '', hidden: true };
  const retrySectionEl = { hidden: true };

  const splash = initSplash({
    currentLocation: { pathname: '/', search: '', hash: '', hostname: 'dancebook-a6588.web.app' },
    fetchFn: mockFetch,
    navigatorLanguage: 'en',
    timerIntervalMs: 10,
    wakeThresholdMs: 50,
    slowThresholdMs: 150,
    probeTimeoutMs: 1000,
    statusEl,
    detailEl,
    retrySectionEl,
    onRedirect: (url) => { redirectedUrl = url; }
  });

  assert.equal(splash.isAwake(), false);
  assert.equal(statusEl.textContent, MESSAGES.en.statusInitial);

  await new Promise((r) => setTimeout(r, 70));
  assert.equal(detailEl.hidden, false);
  assert.equal(detailEl.textContent, MESSAGES.en.detailWaking);
  assert.equal(splash.isAwake(), false);

  resolveProbe({ type: 'opaque', status: 0 });
  await new Promise((r) => setTimeout(r, 20));

  assert.equal(splash.isAwake(), true);
  assert.equal(redirectedUrl, 'https://dancebook-918960884147.europe-central2.run.app/');
  splash.cleanup();
});

test('initSplash: shows retry options after long delay and supports reset', async () => {
  let probeCount = 0;
  const mockFetch = () => {
    probeCount++;
    return new Promise(() => {}); // never resolves
  };

  const statusEl = { textContent: '' };
  const detailEl = { textContent: '', hidden: true };
  const retrySectionEl = { hidden: true };
  const retryBtn = {
    addEventListener: () => {},
    removeEventListener: () => {}
  };

  const splash = initSplash({
    currentLocation: { pathname: '/figures', search: '', hash: '', hostname: 'dancebook-a6588.web.app' },
    fetchFn: mockFetch,
    navigatorLanguage: 'pl',
    timerIntervalMs: 10,
    wakeThresholdMs: 30,
    slowThresholdMs: 70,
    statusEl,
    detailEl,
    retrySectionEl,
    retryBtn
  });

  assert.equal(statusEl.textContent, MESSAGES.pl.statusInitial);

  await new Promise((r) => setTimeout(r, 100));

  assert.equal(statusEl.textContent, MESSAGES.pl.statusSlow);
  assert.equal(detailEl.textContent, MESSAGES.pl.detailSlow);
  assert.equal(retrySectionEl.hidden, false);

  const initialProbeCount = probeCount;
  splash.resetAndRetry();

  assert.equal(statusEl.textContent, MESSAGES.pl.statusInitial);
  assert.equal(detailEl.hidden, true);
  assert.equal(retrySectionEl.hidden, true);
  assert.ok(probeCount > initialProbeCount, 'A new probe was initiated on retry');

  splash.cleanup();
});

test('initSplash: clicking retry several times leaves exactly one probe loop alive', async () => {
  let activeProbeCount = 0;
  let totalFetchesTriggered = 0;

  const mockFetch = (_url, options) => {
    totalFetchesTriggered++;
    activeProbeCount++;
    return new Promise((resolve, reject) => {
      if (options.signal) {
        options.signal.addEventListener('abort', () => {
          activeProbeCount--;
          reject(new Error('AbortError'));
        });
      }
    });
  };

  const splash = initSplash({
    currentLocation: { pathname: '/', search: '', hash: '', hostname: 'dancebook-a6588.web.app' },
    fetchFn: mockFetch,
    timerIntervalMs: 10
  });

  // Rapidly click retry 4 times
  splash.resetAndRetry();
  splash.resetAndRetry();
  splash.resetAndRetry();
  splash.resetAndRetry();

  // Give any microtasks/catch blocks time to process
  await new Promise((r) => setTimeout(r, 50));

  // Exactly one active probe in flight, despite 4 rapid retries
  assert.equal(activeProbeCount, 1, `Expected exactly 1 active probe in flight, got ${activeProbeCount}`);

  // Wait 100ms and verify that no rogue loops spawned additional fetches
  const countAfterRetries = totalFetchesTriggered;
  await new Promise((r) => setTimeout(r, 100));
  assert.equal(totalFetchesTriggered, countAfterRetries, 'No additional probe loops were spawned');

  splash.cleanup();
});
