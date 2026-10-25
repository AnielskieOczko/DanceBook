# DanceBook Cold-Start Splash Page

Static branded splash page for **Firebase Hosting** (project `dancebook-a6588`, site `https://dancebook-a6588.web.app`).

When DanceBook's Cloud Run container is scaled to zero and its Neon database is suspended, the first visit can take 15–35 seconds. This static page provides an instantaneous, branded landing screen while waiting for the JVM and database to wake, and then redirects to the target deep link on Cloud Run (`https://dancebook-918960884147.europe-central2.run.app`).

---

## How It Works

1. **Immediate Branded UI:** Served from Firebase CDN with zero server-side cold start. Uses DanceBook's design tokens (deep teal `#1f4b4a`, warm neutrals, Inter and Source Serif 4 typography).
2. **Cross-Origin Probe:** Issues background `fetch` requests with `mode: 'no-cors'` and `cache: 'no-store'` to the app's public `/login` endpoint.
   - Because cross-origin requests without CORS headers produce opaque responses (`status: 0`), the browser cannot read the HTTP status code directly.
   - When the Cloud Run container boots and responds, the fetch promise resolves.
   - The page then replaces browser location with the target deep link (`window.location.replace`). If the app server responds with an error, redirecting allows the user to see the real app error page.
3. **Deep Links:** Preserves the full URL path, query string, and hash fragment (e.g. `/materials/42?tag=latin#notes`).
4. **Calm Waking & Retry Messages:**
   - **0–20s:** Displays "Connecting to DanceBook..." ("Łączenie z DanceBook...") with a gentle loading animation.
   - **At 20s:** Displays an explanation: "DanceBook is waking up after being idle. This may take another moment..." ("DanceBook budzi się po okresie bezczynności. To może zająć jeszcze chwilę...").
   - **At 50s:** If the server is still starting, displays a retry button ("Try again" / "Spróbuj ponownie") and a direct fallback link ("Open directly" / "Otwórz bezpośrednio").
5. **Language & Accessibility:**
   - Detects Polish vs. English from `navigator.language` (can be overridden with `?lang=pl` or `?lang=en`).
   - Respects `prefers-reduced-motion: reduce` by disabling animations and rendering a static indicator.
   - Fully responsive down to 375px with zero horizontal scroll.

---

## Deployment Instructions (for Owner)

### 1. Install Firebase CLI
```bash
npm install -g firebase-tools
```

### 2. Sign In to Firebase
```bash
firebase login
```

### 3. Deploy Hosting
From this directory (`splash/`):
```bash
cd splash
firebase deploy --only hosting
```
Or from repository root:
```bash
firebase deploy --only hosting --config splash/firebase.json
```

### 4. How to Confirm It Worked
1. **Root page:** Open `https://dancebook-a6588.web.app/` in your browser. Verify the branded loading page displays and seamlessly redirects to `https://dancebook-918960884147.europe-central2.run.app/`.
2. **Deep link:** Open `https://dancebook-a6588.web.app/materials?sort=desc`. Verify it redirects to `https://dancebook-918960884147.europe-central2.run.app/materials?sort=desc`.
3. **Language detection:** Open with Polish browser settings or append `?lang=pl`. Verify all messages display in Polish.
4. **Firebase Console:** Open https://console.firebase.google.com/project/dancebook-a6588/hosting to view deployed release history.

---

## Local Development & Testing

### Running Locally with a Static File Server
```bash
# Using Python
python3 -m http.server 8080 -d splash

# Or using npx serve
npx serve splash
```
Open `http://localhost:8080/` in your browser.

### Running Automated Tests
```bash
# Node unit tests (redirect targets, encodings, thresholds, retry logic)
node --test splash/test/redirect.test.js

# Gradle test (verifies splash files, Firebase configuration, and deep-link logic)
./gradlew test --tests "*SplashPageTest*"
```

### Running the Local Simulation Server
The test server can simulate various container states (instant answer, 32s cold start, or unresponsive):

```bash
# 1. Simulate instant container response (awake)
node splash/test/simulate-server.js instant 8080
# Visit http://localhost:8080/?target=http://localhost:8080

# 2. Simulate 32-second cold start
node splash/test/simulate-server.js delayed 8080
# Visit http://localhost:8080/?target=http://localhost:8080
# -> Observe initial animation, 20s wake message, and redirect at 32s.

# 3. Simulate unresponsive container
node splash/test/simulate-server.js unresponsive 8080
# Visit http://localhost:8080/?target=http://localhost:8080
# -> Observe 20s wake message, followed by 50s retry button and direct link.
```
