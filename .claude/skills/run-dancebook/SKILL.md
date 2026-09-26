---
name: run-dancebook
description: Start, run, stop and drive the DanceBook web app - sign in, click through pages, fill forms, take screenshots, check HTTP statuses and console errors in the running app. Use when asked to run or start DanceBook, screenshot a page, check a change in the running app, verify agy's branch in the browser, or confirm an acceptance criterion by using the app rather than the tests.
---

# Run and drive DanceBook

DanceBook is a Spring Boot + Thymeleaf/HTMX app. Start it with `start.sh`, then drive it
with `driver.mjs`, a headless Chrome (Playwright) that reads one command per line from stdin.
Paths are relative to the repository root. The scripts work unchanged in an agy clone
(`../DanceBook-agy-<N>`), because the skill is committed.

## Setup (once per checkout)

```bash
npm install --prefix .claude/skills/run-dancebook
```

This uses the installed Google Chrome, so no browser download is needed. You also need
Docker running, because Postgres is the `dancebook-db` container.

## Start and stop

```bash
.claude/skills/run-dancebook/start.sh                                 # main, :8080, dev database
PORT=8081 DB=dancebook_agy154 .claude/skills/run-dancebook/start.sh ../DanceBook-agy-154
.claude/skills/run-dancebook/stop.sh                                  # PORT=8081 for the other one
```

`start.sh` returns once `/login` answers, which takes about 10–20s. Log:
`build/dancebook-run/app-<port>.log`.

- **Always verify a branch on its own `DB`.** Any `DB` other than `dancebook` is created
  on first use as a copy of the dev database, which includes the test account. The
  branch's Flyway migrations then run on the copy, and your dev database stays on `main`'s
  schema.
- **Drop the copy when you're done** (after `stop.sh` for that port):
  `docker exec dancebook-db dropdb -U dancebook dancebook_agy154`

## Drive (the agent path)

**Anything that creates, edits or deletes data runs against a copy, never on :8080.** The
app on :8080 uses your real dev database.

```bash
PORT=8081 DB=dancebook_scratch .claude/skills/run-dancebook/start.sh
BASE_URL=http://localhost:8081 node .claude/skills/run-dancebook/driver.mjs <<'EOF'
login
nav /materials/new
fill input[name=name] Smoke test note
fill trix-editor Written by the run-dancebook driver.
click "form button[type=submit]"
wait-for text=Smoke test note
screenshot note-created
errors
EOF
PORT=8081 .claude/skills/run-dancebook/stop.sh
docker exec dancebook-db dropdb -U dancebook dancebook_scratch
```

Without `BASE_URL` the driver talks to :8080. Screenshots go to
`build/dancebook-run/shots/<name>.png`; read them to look at the page. On failure the
driver stops, saves `shots/error.png` and exits 1.

| command | does |
|---|---|
| `login [user] [password]` | Signs in, by default as the dev test account `rafal` / `password123`. The session is saved per port, so later runs start signed in. |
| `logout` | Forgets the saved session, e.g. to switch user. |
| `nav <path>` | Opens a page and prints its **HTTP status**, so a 404 for a hidden item shows as `404 …` and doesn't fail the run. |
| `click` / `fill` / `select` / `check <selector> …` | Take Playwright selectors (`text=Save`, `role=button[name="Save"]`, CSS). Quote a selector that contains spaces. |
| `wait-for <selector>` | Waits until the element is visible. Use it after anything that triggers an HTMX swap. |
| `text [selector]` | Prints the visible text of `main` (the default) or of the selector. |
| `screenshot [name]` | Saves a full-page PNG. |
| `url`, `eval <js>`, `press <key>`, `errors`, `help` | `errors` lists console errors, page errors, 4xx sub-resources and any 5xx. |

## Run (human path)

IntelliJ's **DanceBookApplication** run configuration has the real Google credentials.
Export those variables before `start.sh` and it will use them, not the dummy values.

## Gotchas

- **Screenshots live in `build/`, not `/tmp`.** Claude's file reads are limited to the
  working directory, so a PNG in `/tmp` can be written but not looked at.
- **`spring-boot-docker-compose` overrides `DATABASE_URL`.** It's on the dev classpath,
  and left on, it silently connects every `bootRun` to the compose database `dancebook`,
  whatever `DATABASE_URL` says; in a clone it also runs `compose up`. `start.sh` passes
  `--spring.docker.compose.enabled=false`, and fails if Flyway's log shows a different
  database than `DB`. Without that, a "copy" run once wrote test notes into the dev data.
- **Don't fire several `DELETE /api/materials/{id}` at once.** Two at a time can race
  on a shared uploaded-file row: one returns 500 (`StaleObjectStateException`). Delete
  one at a time.
- **Google is stubbed.** Without real credentials, the Google variables are set to
  `dummy`. The app boots and everything local works, but Drive uploads and Google Calendar
  sync calls fail; Drive deletes log `401 Unauthorized`, which the app tolerates. Give the user a manual checklist for those; don't ask for credentials.
- **Don't run `docker compose up` from a clone.** The compose project takes its name
  from the directory, so it would try to create a second `dancebook-db` on port 5432.
  `start.sh` starts the existing container by name instead.
- **Pages should load with no page errors.** A `pageerror` from `js/main.js` is a real
  bug, not noise: until #140 one such error stopped the rest of the script, and the rich
  text toolbar quietly lost its pruning. Submitting the note form does log one
  `Failed to load resource: 404` with no URL; that one is known.
- **Routes:** notes are `/materials`, figures `/dance-figures`, training
  `/training-events` (plus `/stats`, `/history`, `/timeline`), and calendars
  `/admin/calendars`. `/training` is a 404.
- **A malformed or non-existent note id returns 400, not 404** (`/materials/999999`). To
  check that a hidden item gives 404, use the real id of another user's item.
- **The note body is a Trix editor.** `fill trix-editor <text>` works; the hidden
  `description` input doesn't.

## Test

`./gradlew build` runs the full suite, and needs Docker for Testcontainers. See
`AGENTS.md`.
