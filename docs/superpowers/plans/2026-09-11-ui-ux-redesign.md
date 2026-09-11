# DanceBook UI/UX Redesign

## Context

DanceBook is functionally complete: a ballroom/Latin **syllabus reference** (`wykaz figur`, the Polish federation figure catalog), a **video note library**, a **choreography builder**, and a **training log** with calendar, stats and timeline. 28 full pages, all server-rendered Thymeleaf + htmx.

The UI has drifted. The audit found the same conceptual element styled three to six different ways: section headings have **six** spellings, the "surface panel" has three competing idioms (`.card`, `.stat-card`, and a hand-rolled dashboard tile repeated verbatim four times), filter inputs bypass `.form-input` on four pages while a fifth uses it, and the six `training-events/*` pages hand-copy a page header byte-for-byte because the one shared fragment can't express a multi-button toolbar. Two colour vocabularies (`text-on-surface` vs `text-text-primary` — identical hex) are used interchangeably, often on the same page.

This is a **UI/UX-only initiative**. No new features, no new pages, no business-logic or data-model changes. Goal: one deliberate visual system, one source per component, readable at 375px and at 1200px, with a single accent used with intent.

**Approach: a three-way bake-off before committing.** Rather than deciding on paper whether to hand-write the component layer or adopt a library, we upgrade to Tailwind 4 on `main`, then build the *same three screens* three ways — bespoke, daisyUI 5, Basecoat — and pick a winner from evidence. Stage C then rolls the winner across the app.

### Three findings that shape everything

1. **No Flyway migration is needed.** Every rich-text target column is already `TEXT`. Schema stays at **V27**.
2. **`input.css` is already the de-facto design system** and templates consume it heavily (`card` ×85, `form-input` ×82, `form-label` ×82, `table-cell` ×68, `badge` ×56). Retuning tokens + the ~40 live component rules re-skins all 28 pages **with zero template edits**. That is why tokens land before anything else.
3. **The dominant cost is Thymeleaf fragments, not CSS.** Six spellings of a heading and six hand-copied toolbars are a *templating* problem. This is the central hypothesis the bake-off tests: a CSS library gives you `.btn`, but you may still write the same `<a class="btn"> + icon + label` markup 45 times.

### Corrections to `CLAUDE.md` (stale; fix in Stage A)

| Claim | Reality |
| --- | --- |
| `frontend/tailwind.config.js` | Lives at `src/main/resources/frontend/` |
| "Tailwind only scans templates" | Content is `['../templates/**/*.html', '../static/js/**/*.js']` |
| "currently at V24" | Currently at **V27**; next is V28 |
| — | **jsoup 1.18.3 is already a dependency** (scraping only, never sanitizing) |

---

## Phase 0 — Stack audit (done)

Kotlin 1.9.25 / Java 21 / Spring Boot 3.5.11 / Thymeleaf 3.1.3 / Tailwind **3.4.19** (Gradle `NpxTask` → gitignored `static/css/output.css`; no watch task). htmx 2.0.4 global; SortableJS, Chart.js, FullCalendar page-scoped from unpkg. **No Alpine.** No layout dialect — `layout.html` defines `th:fragment="html(content)"` and 29 pages do `th:replace="~{layout :: html(content=~{::section})}"`.

**CSP is the hard constraint:** `script-src 'self' https://unpkg.com` with **no `'unsafe-inline'`** (all JS must be an external file under `static/js/`), and `style-src 'self' 'unsafe-inline' https://fonts.googleapis.com` — **third-party CSS from a CDN is blocked**. Any library CSS or JS must be vendored and served from `'self'`. No CSP edit is required by this plan.

Verified in `thymeleaf-3.1.3.RELEASE.jar`: `StandardRefAttributeTagProcessor` (`th:ref`) and `StandardExpressionPreprocessor` (`__${…}__`) both present — the two mechanisms the fragment catalog depends on.

---

## Phase 0b — Library evaluation, and why Tailwind 4 comes first

**Basecoat requires Tailwind v4.** Its repo ships "Tailwind CSS v4 source files" and its install uses `@import "tailwindcss"`, the v4 CSS-first syntax. On Tailwind 3 it is simply unavailable.

**daisyUI splits by major.** daisyUI 5 is Tailwind-4-only (`@plugin "daisyui"`); daisyUI **4** is the Tailwind 3 line. Staying on Tailwind 3 would force us to compare against a maintenance-track release while still owing the v4 migration later.

**Therefore the comparison is not fair on Tailwind 3** — one candidate is impossible and the other is a version behind. Upgrading first is what makes a real bake-off possible, and it is a prerequisite either way.

### Does Tailwind 4 improve the UI?

Not directly — nothing looks different because of it. It earns its place for three concrete reasons:

1. **It kills the `TrainingEventPalette.kt` duplication.** `@theme` emits tokens as real CSS custom properties on `:root`, readable at runtime via `getComputedStyle(document.documentElement).getPropertyValue('--color-primary')`. Today 13 hex values are hardcoded in Kotlin *purely because JS cannot reach Tailwind's palette* — the file's own KDoc says so. After the upgrade, `training-stats.js` and `training-calendar.js` read colours from CSS, and `TrainingEventPalette` keeps only its category→swatch mapping logic. One source of truth, no JSON-loading machinery.
2. **It unblocks both libraries**, which is the whole point of Stage B.
3. Faster builds and native cascade layers, which matter given twelve-plus PRs of CSS work.

### Migration specifics to plan around

| Change | Impact here |
| --- | --- |
| `tailwind.config.js` → `@theme` in CSS | The whole Noble Harmony palette, `fontSize`, `borderRadius`, `spacing` blocks move into `input.css` |
| `safelist` removed | Now `@source inline(...)`. We currently have none, but the calendar's JS-generated classes rely on source scanning |
| CLI is `@tailwindcss/cli` | The Gradle `NpxTask` command changes from `tailwindcss`; `package.json` devDependency changes |
| `content` array → auto source detection | Must keep `../static/js/**/*.js` discoverable — explicit `@source` if auto-detection misses it |
| `darkMode: "class"` | Becomes `@custom-variant dark`. Currently inert (no `dark:` variants, no toggle) — **delete it**, light-mode only |
| Native cascade layers | The ~270 lines of **unlayered** FullCalendar `.fc-*` CSS need re-verification; they are unlayered deliberately to escape purging |
| `@apply` in separately-bundled CSS | Not an issue — we have exactly one CSS entry point |

Treat Stage A as a **behaviour-preserving** migration: same look, green build, then retune tokens.

---

## Phase 1 — View inventory

28 full-page templates, 35 full-page GET routes (forms are reused for create+edit), ~30 htmx-partial-only endpoints, 8 dual-mode routes. **Zero error templates** — 404/500 fall through to Whitelabel.

| Area | Route | Template | Purpose | Type |
| --- | --- | --- | --- | --- |
| Home | `/` | `index.html` | Counts, greeting, 10 recent activity | dashboard |
| Notes | `/materials` | `materials/list.html` | Filter/search, grid+list toggle | list+filter |
| | `/materials/{id}` | `materials/view.html` | Note detail: video, markers, comments | detail |
| | `/materials/new`, `/{id}/edit` | `materials/form.html` | Create/edit note + Drive upload | form |
| Figures | `/dance-figures` | `dance-figures/list.html` | Filter/search catalog | list+filter |
| | `/dance-figures/{id}` | `dance-figures/view.html` | Step tables, links, relations (528 ln) | detail |
| | `/dance-figures/new`, `/{id}/edit` | `dance-figures/form.html` | Create/edit + AI import (752 ln) | form |
| Collections | `/lists` | `lists/index.html` | Visible collections | list+filter |
| | `/lists/{id}` | `lists/view.html` | Resolves saved filter → note grid | detail+list |
| | `/lists/new`, `/{id}/edit` | `lists/form.html` | Create/edit + cover image | form |
| Choreo | `/choreographies` | `choreographies/index.html` | User's choreographies | list |
| | `/choreographies/{id}` | `choreographies/view.html` | Read-only, print-oriented | detail |
| | `/choreographies/{id}/edit` | `choreographies/edit.html` | Sequence builder (drag) | builder |
| | `/choreographies/new`, `/{id}/metadata` | `choreographies/form.html` | Metadata | form |
| Training | `/training-events` | `training-events/list.html` | Agenda by month + filters | list+filter |
| | `/training-events/calendar` | `training-events/calendar.html` | FullCalendar | calendar |
| | `/training-events/stats` | `training-events/stats.html` | KPIs + 2 charts | dashboard |
| | `/training-events/timeline` | `training-events/timeline.html` | Chronological, infinite scroll | list |
| | `/training-events/{id}` | `training-events/view.html` | Session detail | detail |
| | `/training-events/new`, `/{id}/edit` | `training-events/form.html` | Create/edit, single or series | form |
| Taxonomy | `/dance-categories`, `/dance-types` (+forms) | `dance-categories/*`, `dance-types/*` | Category & style admin | list+form ×4 |
| Activity | `/activity-history` | `notifications/history.html` | 50 latest events | list |
| Users | `/login` | `login.html` | Form + OAuth2 (**standalone**, no layout) | auth |
| | `/profile` | `profile/index.html` | Profile + password change | detail |
| | `/admin` | `admin/dashboard.html` | Users, settings, Drive cleanup (461 ln) | dashboard |

Partial-only files: `fragments/components.html`, `materials/fragments/comments.html`, `notifications/dropdown.html`.

---

## Phase 2 — Content audit

Rather than 28 near-identical tables, the findings group into repeating defects. **P1** ships with the design system, **P2** with its view's migration, **P3** optional.

### Cross-cutting

| Finding | Where | Change | Pri |
| --- | --- | --- | --- |
| Four different empty-state treatments (`.empty-state` component; dashed box; bare italic; Elvis-inside-`th:text` styled as real content) | 15+ sites | One `emptyState` fragment | P1 |
| Elvis fallbacks render placeholders in the same colour as real content | `dance-figures/view.html:518`, `choreographies/index.html:70,137`, `choreographies/edit.html:44` | `th:if`/`th:unless` + muted style | P1 |
| Three fallback strings for one field | `Choreography.description` | One string | P1 |
| Textareas never render validation errors anywhere | all 8 forms | Field fragment always has an error slot | P1 |
| `choreographies/form.html`, `lists/form.html` render **no** field errors at all | those 2 forms | Wire through fragment | P1 |
| No global error summary anywhere | all forms | `errorSummary` fragment | P2 |
| Same field wraps differently in different views | `Material.description`: `whitespace-pre-wrap` in list, nothing in detail | Rich-text display fragment | P1 |
| `.form-label` reused as a **detail-view** label | `training-events/view.html`, `dance-figures/view.html` | Separate `meta-label` style | P2 |
| Optional blocks vanish entirely when empty — no placeholder | `training-events/view.html:57` | Empty state or omit deliberately | P2 |
| Per-card actions revealed only on hover (`opacity-0 group-hover:opacity-100`) — unreachable by keyboard/touch | `materials/view.html` | Always visible, or `focus-within` | P1 |
| Fake data hardcoded in template | `profile/index.html` — "Dance Enthusiast", counts 124/12 | Show real values or cut | P2 |

### Text-heavy fields → rich text (all already `TEXT`; no migration)

| Field | Now rendered | Now edited |
| --- | --- | --- |
| `DanceFigure.notes` | `th:text` + `whitespace-pre-line` | `textarea rows=5` |
| `Comment.content` | `th:text` + `whitespace-pre-wrap` | `textarea rows=3` ×2 |
| `Material.description` | `th:text` (2 different wrappings) | `textarea rows=3` |
| `TrainingEvent.description` | `th:text` + `whitespace-pre-line` | `textarea rows=4` |
| `TrainingSeries.description` | never rendered | same form |
| `Choreography.description` | `th:text` + `italic` | `textarea h-28` |

### Deliberately excluded

- **`ChoreographyEntry.notes`** — `varchar(500)`, edited via single-line `<input>`. Markup would overflow; would need a migration. Out of scope, but **add `@Size(max=500)`** (P2) — today it can 500-error or truncate silently.
- **`DanceFigureStepComment.commentText`** — newline-separated blob split server-side inside a 752-line form.
- `DanceFigureStep.action` / `alignment` / `amountOfTurn` — `TEXT` in DB but short tabular values in practice.

---

## Phase 3 — Layout & information architecture

**Navigation (decided):** collapse Training / Timeline / Calendar / Stats — four routes on one concept — into a single **Training** nav item whose page carries a view switcher. Desktop goes 8 → 6 items, and the same 6 fit mobile, so desktop and bottom nav finally match. The six are **Dashboard · Notes · Figures · Collections · Choreographies · Training**, with Profile moving to the avatar menu it already sits next to. Requires editing `activeNav()` in `NavbarAdvice.kt` so `/training-events/**` maps to one value.

Per view type, mobile-first:

**List / index.** Mobile: stacked cards, one per row, 16px gutters; filters collapse into a single "Filters" disclosure showing an active-filter count; the grid/list toggle is hidden (cards only). ≥768px: two columns. ≥1200px: the existing grid, filters as a persistent left rail, toggle returns. The dense pages (`dance-figures`, `admin` users) become a **real table only at ≥1024px** and a stacked definition list below — the one genuine pattern change, not a resize.

**Detail.** Mobile: single column — identity block (title, badges, key meta), then primary media/data, then prose, then related. Desktop ≥1024px: 2/3 + 1/3, prose and step tables left, metadata and relations right as a sticky rail. Step tables keep their own `overflow-x: auto` container so the page body never scrolls sideways.

**Create / edit.** Single column at every width, `max-w-2xl`, never a two-column form — the biggest current inconsistency (forms range `max-w-md` to `max-w-4xl` with ad-hoc `grid-cols-2`). Related fields group under a rule with a section heading. Action row: sticky bottom bar on mobile (≥44px targets), right-aligned inline row on desktop.

**Dashboard / stats.** KPI row: 2-up mobile, 4-up desktop. Charts stack full-width mobile, side-by-side ≥1024px.

**Builder (`choreographies/edit.html`).** Genuinely different: mobile is palette-above / sequence-below with the palette collapsible; desktop side-by-side. Drag reordering needs a keyboard alternative — see Phase 8.

---

## Phase 4 — Rich text

**Editor: Trix 2.x** from unpkg. No build step, submits HTML through a hidden `<input>` bound with `th:field`, accessible toolbar, and its runtime CSS is injected as a `<style>` element which `'unsafe-inline'` in `style-src` already permits. Quill and TipTap were rejected: Quill's CDN stylesheet is **blocked by our `style-src`**, and TipTap needs a bundler.

**Allowed formatting — deliberately small:** bold, italic, bullet list, numbered list, link. No headings, tables, colours, or attachments. Attachments disabled outright (`trix-file-accept` → `preventDefault()`), matching the safelist.

**Sanitization** — new `service/RichTextService.kt` + `RichTextServiceImpl.kt` (house convention: interface + `Impl`), using the already-present jsoup:

```
Safelist.none()
  .addTags("b","strong","i","em","ul","ol","li","a","br","div")
  .addAttributes("a","href","rel","target")
  .addProtocols("a","href","http","https","mailto")
  .addEnforcedAttribute("a","rel","nofollow noopener noreferrer")
```

`div` and `br` are included because that is exactly what Trix 2 emits for block structure — it produces `<div>` blocks, not `<p>`.

**The highest-risk detail: `isEmpty`.** Trix submits `<div><br></div>` for an empty editor. Four services rely on `isNotBlank()` — `TrainingEventServiceImpl.kt:190`, `ChoreographyServiceImpl.kt:48,69`, `TrainingSeriesServiceImpl.kt:232` — and `"<div><br></div>".isNotBlank()` is `true`. Without an emptiness check, every session detail page renders an empty description panel. `sanitize()` must return `null` when the cleaned text is blank and there is no `<li>`/`<a>`.

**Write-path call sites:** `MaterialServiceImpl.kt:56,85` · `CommentServiceImpl.kt:41,59` · `DanceFigureServiceImpl.kt:135` · `TrainingEventServiceImpl.kt:190` · `TrainingSeriesServiceImpl.kt:232` · `ChoreographyServiceImpl.kt:48,69`.

**Easy to miss:** `GoogleCalendarClientImpl.kt:133` pushes `event.description` to Google Calendar. Once it's HTML, raw markup leaks into calendar entries — change to `toPlainText(...)`.

**Legacy plain text, no migration.** Discriminate in Kotlin, never in a template: if the stored value contains `<` and parses to element children, re-sanitize it; otherwise escape it and wrap each line in `<div>`, reproducing today's `whitespace-pre-line` behaviour. Re-sanitizing on **read** as well as write is what makes skipping a migration honest.

**Display — `templates/fragments/rich-text.html`, the only file permitted to contain `th:utext`:**

```html
<div th:fragment="content" class="rich-text" th:utext="${@richTextService.toHtml(value)}">Content</div>
<p   th:fragment="excerpt" th:text="${@richTextService.excerpt(value, max ?: 160)}">Excerpt</p>
```

`excerpt` is `th:text`, so list views are structurally incapable of leaking markup. Read sites: `materials/view.html:49`, `materials/fragments/comments.html:48`, `dance-figures/view.html:518`, `training-events/view.html:59`, `choreographies/view.html:118` (→ `content`); `materials/list.html:203`, `choreographies/index.html:70,137`, `choreographies/edit.html:44` (→ `excerpt`).

**Guard test** — `TemplateSafetyTest` walks `src/main/resources/templates`, asserts `th:utext` appears in no file but `fragments/rich-text.html`. Baseline today is zero occurrences, so it starts green and stays meaningful.

**Validation:** `MaterialRequest.description` has `@Size(max=2000)`; markup routinely triples byte count. Add `dto/RichTextSize.kt` — a `@Constraint` validating `Jsoup.parse(value).text().length` — behind a storage-safety `@Size(max=20000)`.

---

## Phase 5 — Design system

### Colour — 5 neutrals + 1 accent + 2 status

Ink-on-paper, grounded in what this app is: a reference manual. Rules and tonal shifts do the separating, not shadows.

| Token | Hex | Role | Contrast on `surface` |
| --- | --- | --- | --- |
| `surface` | `#FFFFFF` | Page | — |
| `surface-container` | `#F5F5F3` | Inset blocks, table header rows | — |
| `outline-variant` | `#E3E3E0` | Decorative hairlines, table rules | 1.3:1 (decorative only) |
| `outline` | `#8E9199` | **Form control borders** | **3.15:1** ✓ AA non-text |
| `on-surface-variant` | `#5B6067` | Meta, labels, secondary text | **6.34:1** ✓ |
| `on-surface` | `#16181A` | Body ink | **17.4:1** ✓ |
| `primary` | `#1F4B4A` | The one accent | **9.71:1** ✓ (also white-on-primary) |
| `primary-hover` | `#163736` | Hover/pressed | — |
| `error` | `#B3261E` | Destructive, validation | **6.53:1** ✓ |
| `warning` | `#8A5A00` | "Awaiting confirmation" only | **5.93:1** ✓ |

Two rule weights exist for a reason: `outline-variant` is invisible-by-design table ruling, `outline` is the ≥3:1 an input border legally needs. Never use `outline-variant` on a form control.

**Accent discipline.** `primary` appears only as: the single primary action per view, the active nav item, the focus ring, links inside prose, and the "attended/confirmed" state. Never a card background, never decorative. There is no separate success green — *attended* uses the accent, which is what makes the accent consistently mean "affirmative".

**Chart categories** are the one legitimate multi-hue surface (max 8). Defined in `@theme` as `--color-chart-1` … `--color-chart-8`, each ≥3:1 on white, and **read from CSS at runtime by `training-stats.js`** — which is what retires the hardcoded hex in `dto/TrainingEventPalette.kt`.

### Type — two faces, clear roles

**Inter** for UI, labels, navigation and all tabular data (`font-variant-numeric: tabular-nums`). **Source Serif 4** for long-form prose only — exactly what the rich-text display fragment renders. This mirrors how a dance technique manual is actually set: serif prose, sans tables. Both on Google Fonts, already CSP-allowed. **Manrope is loaded but used nowhere — remove it**, and remove the duplicate `@import` in `input.css:7` that re-fetches fonts already `<link>`ed in `layout.html`.

| Role | Size / line-height | Face | Notes |
| --- | --- | --- | --- |
| `display` | 30/36 → 36/40 ≥768px | Inter 600, -0.02em | Page titles |
| `title` | 24/32 | Inter 600 | Section/card titles |
| `section` | 18/28 | Inter 600 | Sub-headings |
| `body` | 16/26 | Inter 400 | UI copy |
| `prose` | 17/28 | **Source Serif 4** 400 | Rich text; `max-width: 68ch` |
| `label` | 13/16 | Inter 600 | Form + meta labels — **sentence case, no letter-spacing** |
| `meta` | 13/18 | Inter 400 | Timestamps, counts |
| `tabular` | 14/20 | Inter 500, `tabular-nums` | Step tables, stats |

Collapses today's 8 `fontFamily` keys that all resolve to the same stack. **No tracked-out ALL-CAPS labels** — today `font-label-sm` (89 uses) carries `0.05em` + uppercase; that goes.

### Spacing, radius, elevation

- **Spacing:** stock numeric scale only (`1 2 3 4 6 8 12 16` = 4→64px). Retire the alphabetic `xs/sm/md/lg/xl/base/gutter/margin` scale — ~65 uses, and `p-md` meaning 24px next to `text-sm` is a permanent readability tax.
- **Radius:** `sm` 2px (badges, inputs), `DEFAULT` 4px, `lg` 8px (cards, modals), `full`. Deliberately tighter than today's 8px default — reference-manual precision, not app-store softness. High leverage, high risk: 91 `rounded-lg` + 44 `rounded-md` + 26 `rounded-button` + 19 `rounded-xl` all move at once.
- **Elevation — exactly two levels, and the second means something.** Level 0: flat, 1px `outline-variant` hairline. Every card, table, input, panel. Level 1: a shadow, used *only* by things that genuinely float — modals, dropdowns, the notification panel, the calendar quick-create sheet. Nothing else. Today five `boxShadow` keys hold byte-identical values and cards get shadows by default; collapse to `ambient` + `none`.

### Component catalog — Thymeleaf fragments

**This layer is required regardless of which Stage B branch wins** — it is the templating answer, and no CSS library provides it. What changes per branch is only what goes *inside* each fragment.

Two mechanics govern the catalog:

**1. Declare no signature; use named parameters.** Thymeleaf validates arity strictly *only* when a fragment declares parameters — a signature-less fragment called with named params skips validation, so absent params resolve to `null` and can be defaulted inside. Today's `page-header(title, subtitle, actionUrl, actionText, actionIcon, actionType)` forces every caller to pass all six; `notifications/history.html` passes three dummy values purely to satisfy arity. Cost: a mistyped param silently becomes `null` — caught by the catalog rendering test.

**2. `th:field` as a parameter works via preprocessing.** `th:attr="th:field=…"` does **not** work (`th:attr` writes plain output attributes after the dialect has run). The working pattern is `th:field="*{__${path}__}"` — preprocessing substitutes before expression evaluation, and because fragment insertion is inline, `*{…}` resolves against the caller's `<form th:object>`. Errors need no preprocessing: `#fields.hasErrors(path)` takes a plain `String`.

| File | Fragments |
| --- | --- |
| `icon.html` | `icon(name, size, filled, cls)` |
| `button.html` | `link`, `button`, `iconButton`, `submitRow(cancelHref, submitLabel, …)` |
| `form.html` | `field(path,label,type,hint,required,attrs)`, `select` (options as slot), `textarea`, `checkbox`, `toggle`, `richText`, `errorSummary`, **`rawField`** |
| `page.html` | `header(title, subtitle, subtitleSlot, toolbar)`, `sectionTitle(title, icon)` |
| `card.html` | `statCard(value, label, icon, trend)` |
| `table.html` | `dataTable(head, body)`, `stepTable(steps, role)` |
| `badge.html` | `badge(label, variant, icon)`, `attendanceBadge(event)` |
| `empty.html` | `emptyState(icon, title, text, actionHref, actionLabel)` |
| `alert.html` | `alert(variant, title, text, dismissible)` |
| `modal.html` | `modal(id, title, body, footer, size)` — native `<dialog>` |
| `rich-text.html` | `content(value)`, `excerpt(value, max)` |

**`rawField` exists because `#fields.*` throws without a form context.** `materials/fragments/comments.html:14,75` submits via `CommentController`'s raw `@RequestParam("content")` with no `th:object`. (Converting that controller to a DTO is a behaviour change; noted as follow-up.)

Multi-action toolbars use a slot, which fixes the six hand-copied training headers:

```html
<div th:replace="~{fragments/page :: header(title='Training', toolbar=~{::pageToolbar})}">
    <div th:ref="pageToolbar" class="flex items-center gap-2 shrink-0">
        <a th:replace="~{fragments/button :: link(href='/training-events/new', label='Add session', icon='add')}"></a>
    </div>
</div>
```

**Not fragmented, deliberately:** a generic `card` (85 uses are a bare `class="card p-6"`); generic table rows (content differs per page); the Drive-upload block in `materials/form.html:90-145` (bound to `drive-upload.js` by hardcoded ids); `login.html` (standalone).

---

## Phase 6 — Icons

**Keep Material Symbols Outlined.** Already loaded, already CSP-allowed, ~290 call sites. Migrating to Lucide means 290 edits for no user-visible gain. (If a Stage B library ships its own icon set, using it is a point of comparison — but not a requirement.)

- One `icon` fragment replaces ~290 ad-hoc spans.
- **Fix three competing fill mechanisms** — `.icon-filled`, `th:style="font-variation-settings…"`, and **31 raw `style="font-variation-settings: 'FILL' 1, …"` attributes across 5 templates** (`index.html`, `layout.html`, `dance-figures/view.html`, `materials/list.html`, `lists/index.html`). One line in `layout.html` uses two at once. Keep `.icon-filled` only.
- **Size scale — three steps:** `sm` 16px, `md` 20px (default), `lg` 24px. Nothing else.
- **Usage rule:** icon + label → decorative, `aria-hidden="true"`. Icon alone → a control, needs `aria-label` **and** a ≥44px target. Never an icon-only destructive action.

---

## Phase 7 — Naming & microcopy

The same `Material` is called a "material", a "sequence" and a "movement" on different screens; comments are called "notes"; and both `DanceFigure` and `Figure` surface as "figure".

### Glossary (UI labels only — no code renames)

**`Material` is called a "Note" in the UI.** The user's mental model is that they are creating a note, and "Create note" reads far more naturally than "Add material". The entity, table and route (`Material`, `/materials`) are untouched — this is a copy change.

Consequence: "Notes" can no longer name the free-text prose field, even though `DanceFigure.notes` is the actual column name. That field is **"Description"** on every entity, which also matches the real column names on `Material`, `TrainingEvent` and `Choreography`.

| Term | Means | Never call it |
| --- | --- | --- |
| **Notes** | The section — the collection of notes (nav label, was "Library") | "Library", "Materials" |
| **Note** | One video/resource (`Material`) | "material", "sequence", "movement" |
| **Marker** | A timestamped figure inside a note (`Figure`) | "figure" — resolves the collision |
| **Figure** | A syllabus catalog entry (`DanceFigure`) | "movement", "technique" |
| **Description** | The free-text prose field on **any** entity | "Notes", "Details", "Remarks" |
| **Comment** | User discussion on a note | "note" |
| **Collection** | A saved filter over notes (`CustomList`) | "list" (routes stay `/lists`) |
| **Choreography** | The whole piece | — |
| **Sequence** | The ordered entry list inside a choreography | reuse anywhere else |
| **Session** | One dated training occurrence | "event" |
| **Series** | A repeating session pattern | — |
| **Style** | `DanceType` (Waltz, Samba) | "type" |
| **Category** | `DanceCategory` (Standard, Latin) | "class" — `danceClass` is the A–S grade |

Concrete renames: nav "Library" → "Notes"; "Add Material" → "Add note"; the figure detail "Notes" heading → "Description"; the training form's "Notes" label → "Description" (keep its "What did you work on?" placeholder); "Key Figures & Movements" → "Markers".

### Rules

- **Active voice on actions.** "Save changes", "Add session", "Delete figure" — not "Submit", "OK".
- **The verb survives the whole flow.** "Add session" → "Session added". Never "Success".
- **Errors say what happened and how to fix it**, without apologising: "This name is already used by another style. Pick a different name."
- **Empty states invite.** "No sessions logged yet. Add your first session to start tracking hours."
- **Sentence case everywhere.** No tracked-out caps.

Also fix the four service-layer failures that `rejectValue` onto the *name* field regardless of actual cause (`TrainingEventWebController.kt:175,244`, `DanceFigureWebController.kt:110,225`).

---

## Phase 8 — Accessibility

- **Contrast:** every text token above is verified AA (Phase 5 table). `outline` at 3.15:1 for control borders; `outline-variant` restricted to decoration.
- **Focus:** keep `*:focus-visible { ring-2 ring-primary ring-offset-2 }`, retuned. `focus:outline-none` without a replacement (`materials/list.html:193`) is a defect.
- **Touch targets ≥44px** on mobile. Today `.btn-sm` is 40px and `btn-ghost px-0 min-h-0` explicitly cancels the target.
- **Hover-only affordances are keyboard traps** — `materials/view.html`'s `opacity-0 group-hover:opacity-100` becomes always-visible or `focus-within`.
- **Rich-text toolbar:** Trix's toolbar is button-based and tab-reachable; verify accessible names and that the editor is labelled via `<label for>`.
- **Modals:** native `<dialog>` gives focus trap, `Esc`, backdrop and focus return for free. Neither current modal does any of it (`lists/view.html:60`, `training-events/calendar.html:92`).
- **Drag-only reordering needs a keyboard path** — move-up/down buttons hitting the existing `/entries/reorder` endpoint.
- **Tables:** `<th scope>`; the horizontal scroll container gets `tabindex="0"` and an accessible name.

---

## Stage A — Shared trunk (on `main`)

Both prerequisites, shipped before any branch diverges, so the Stage B branches differ **only** by the thing being compared.

| PR | Scope | Done when |
| --- | --- | --- |
| **A1. Tailwind 3 → 4** | `input.css` gains `@theme` holding the *current* Noble Harmony values verbatim; delete `tailwind.config.js` (or keep behind `@config` only if something blocks); `package.json` → `@tailwindcss/cli`; update the Gradle `NpxTask` command; add a `buildTailwindWatch` task; drop the inert `darkMode: "class"`; re-verify the unlayered `.fc-*` block under native cascade layers; fix `CLAUDE.md`. **Behaviour-preserving — the app must look identical.** | `./gradlew build` green; all 5 existing tests pass; visual diff of the 6 densest pages shows no change |
| **A2. Design tokens** | Re-pick values in `@theme` per Phase 5: colours, two-face type scale, radius, collapse 5 shadows → 2, collapse 8 `fontFamily` keys → 2. Add `--color-chart-1..8`. Point `training-stats.js` / `training-calendar.js` at CSS custom properties and strip the hex from `dto/TrainingEventPalette.kt`. **Keep every legacy alias alive but re-pointed** (`text-primary`, `border`, `danger`, `accent`, …) or ~30 templates break at once. | Build green; calendar + charts render in the new palette with no Kotlin hex; the app is re-skinned with zero template edits |

---

## Stage B — Three-way bake-off

Three branches off `main` after A2. Each implements **the same three screens**, to the same finish quality:

1. **`dance-figures/list.html`** — filters, htmx swap targets, card grid, empty state, plus **one modal**
2. **`training-events/form.html`** — field+label+error+hint, select, date/time grid, validation states
3. **`dance-figures/view.html`** — dense step tables, role tabs, badges, prose block (the hardest, most domain-specific screen)

| Branch | Approach |
| --- | --- |
| `ui/bespoke` | Hand-written `@layer components` + the Phase 5 fragment catalog |
| `ui/daisyui` | daisyUI 5 plugin, custom theme mapping our tokens + the fragment catalog |
| `ui/basecoat` | Basecoat CSS vendored to `'self'`, its JS vendored past the CSP + the fragment catalog |

**All three build the fragment catalog.** That is deliberate — it isolates the variable. The catalog is needed regardless (Phase 5), so what the bake-off actually measures is *what goes inside each fragment* and how much custom CSS survives.

### Comparison criteria — agreed before building, so the decision is evidence not vibes

| # | Criterion | How measured |
| --- | --- | --- |
| 1 | Custom CSS retained | Lines in `input.css` after the three screens work |
| 2 | Template verbosity | Markup lines for the same three screens |
| 3 | Design fidelity | Does the ink-on-paper direction survive the library's defaults, or does it fight them? Judged on screenshots at 375 / 768 / 1280 |
| 4 | Dense-table handling | Does the step table look right, or does the library assume roomy app layouts? |
| 5 | Accessibility out of the box | Focus visibility, target sizes, `<dialog>` semantics — before we fix anything |
| 6 | htmx compatibility | Do swap targets survive? Any library JS that fights `htmx:afterSwap`? |
| 7 | Payload | `output.css` size |
| 8 | Escape hatches | How painful is overriding one component? |

**Timebox each branch to the three screens.** If a branch is clearly losing on criteria 3 and 4 partway through, stop it early and say so — an abandoned branch is a result, not a failure.

**Deliverable:** a short written comparison plus screenshots, then a decision. The two losing branches are deleted; the winner merges and Stage C continues on `main`.

---

## Stage C — Roll out the winner

Renumbered from the original plan; scope per PR is unchanged by the bake-off except that the component layer follows the winning approach.

| PR | Scope | Done when |
| --- | --- | --- |
| **C1. Component layer** | Finish the component layer for the whole app in the winning approach. Delete dead CSS (`.sidebar*`, `.avatar*`, `.toast*`, `.star*`, `.nav-item*`, most `.bottom-nav*`, `.card-hover`, `.card-interactive`, `.divider`). Keep `.chip*` (18 uses). **Delete the dead `btn-text` class from `materials/list.html:194`** — a label span referenced by no JS or CSS, not a button variant. Re-tokenize `.tc-*` and the unlayered `.fc-*` block. | Build green; `output.css` shrinks; **the app looks redesigned** |
| **C2. Finish the catalog** | Complete the fragment catalog beyond the three bake-off screens; `FragmentCatalogRenderingTest`, `HtmxFragmentRenderingTest` | Catalog covers every component in Phase 5 |
| **C3. Page headers** | 7 `page-header(...)` call sites + 6 hand-copied `training-events/*` toolbars + bespoke headers in `admin`, `profile`, `index`, 3 forms. Delete `fragments/components.html`. | `grep -r "page-header(" templates` empty |
| **C4. Icons** | `icon` fragment across ~290 sites; delete `th:style` and all 31 raw `font-variation-settings` attributes | `grep -r 'font-variation-settings' templates` empty |
| **C5. Forms I** | The 5 forms that already have error slots: `materials`, `dance-categories`, `profile`, `dance-figures` (scaffolding only) — `training-events/form` already done in Stage B | All `th:classappend="…hasErrors…"` copies gone |
| **C6. Forms II + validation gaps** | `choreographies/form.html`, `lists/form.html` (currently render **no** errors); `errorSummary` on all 8 forms; `@Size(max=500)` on `ChoreographyEntryRequest.notes`. ⚠️ **Flagged:** `AdminController.createUser:211`/`editUser:238` take `@Valid` with no `BindingResult` → `BindException` → Whitelabel 400. Fixing it is a behaviour change; recommend including, drop if the no-logic-change rule is absolute. | Every form shows field errors |
| **C7. Error pages, modal, alert** | `templates/error/{404,500,error}.html` (Spring Boot convention, no controller); `modal` fragment on the 2 sites via native `<dialog>`, deleting hand-written modal handling from `main.js`; `alert` fragment *inside* the 4 admin htmx fragments without touching their roots | Designed 404; modal traps focus and closes on `Esc` with no JS |
| **C8. Rich text, server side** | `RichTextService` + `Impl`, 9 write sites, `GoogleCalendarClientImpl.kt:133`, `RichTextSize`, `rich-text.html`, 9 read sites, `TemplateSafetyTest`, `RichTextServiceTest`. **Editors stay plain textareas** — safely orderable before the editor. | Legacy notes render identically; injected `<script>` stripped |
| **C9. Trix editor** | `static/js/rich-text.js`, vendored **unlayered** Trix CSS, script tag on 6 forms, `richText` fragment on the 6 fields. No CSP edit. | Round-trips; empty editor persists `null`; htmx-swapped comment editor re-initialises |
| **C10. Lists, tables, badges** | Remaining list/dashboard templates; `statCard`; `attendanceBadge` | The 4-branch attendance ternary exists once |
| **C11. Big views + cleanup** | `choreographies/{view,edit}.html`; `login.html`; nav restructure in `layout.html` + `NavbarAdvice.activeNav()`; retire the alphabetic spacing scale; delete legacy alias tokens + a test asserting no template references them | Desktop and mobile nav match at 6 items |
| **C12. Copy pass** | Sweep every template against the Phase 7 glossary. Verb consistency per flow; errors that say how to fix; empty states that invite. | `grep -ri 'material\|sequence' templates` returns only genuine binding names, never display copy |

Copy should be written correctly as each view is migrated; **C12 is the sweep and verification**, not the first application of the glossary.

---

## Risks

**Tailwind purge vs. fragment parameters.** A class literal at a *call site* is safe — the caller is a scanned file. A class built by concatenation is **silently broken**: `th:class="'badge-' + ${status}"` yields `badge-attended`, which appears in no scanned file, so the rule is dropped and the element renders unstyled while the attribute is still emitted. **API rule:** fragment params carry semantic variants (`variant='danger'`), never class fragments; the variant→class mapping lives *inside* the fragment as literals. In v4, `safelist` is gone — the escape hatch is `@source inline(...)`.

**Tailwind 4 migration risk.** Stage A1 must be behaviour-preserving and is the one PR where "looks identical" is the acceptance test. The unlayered `.fc-*` block is the sharpest edge: it is unlayered deliberately to escape purging, and native cascade layers change precedence semantics.

**Library CSS must reach the browser from `'self'`.** `style-src` allows no CDN. daisyUI is a build-time plugin so this is moot; **Basecoat's CSS and JS must both be vendored** into `static/` — factor that into criterion 8.

**Vendored library JS vs htmx.** Any Basecoat component JS must re-initialise on `htmx:afterSwap`, exactly like Trix. Branch `ui/basecoat` must prove this on the `dance-figures/list` screen, which is an htmx swap target.

**Radius/spacing overrides.** `borderRadius` currently *overrides* stock values (`lg` is 1rem, not 0.5rem), so `rounded-2xl` == `rounded-lg` today. Changing it moves every corner in all 32 templates at once.

**The 30 htmx swap targets are the main regression surface.** Controllers name fragments as strings (`"training-events/list :: eventsList"`) — a rename is a runtime 500 invisible to the compiler. Three frozen rules: (1) **never relocate a controller-named fragment** — migrate its contents, leave the marker on its original element in its original file; (2) **root element tag, `id`, and fragment name are frozen** — dropping `id="events-list"` makes the first swap work and every later one silently no-op; (3) `materials/fragments/comments.html` uses `hx-target="closest .group"`, and `group` is also a Tailwind variant marker — do not introduce `group` into any fragment used there. `HtmxFragmentRenderingTest` converts rules 1–2 into build failures.

**`dance-figures/view.html`** has four near-identical step-comment blocks (lines 262, 327, 387, 452) inside JS-driven toggles. `dance-figure-view.js` queries `.js-step-set-group`, `.leader-steps-section`, `.follower-steps-section`, `data-set-id`, and the `hidden` class at line 337 — all must survive the `stepTable` extraction. This screen is in the bake-off, so all three branches confront it.

---

## Verification

Per PR: `./gradlew build` (compiles, tests, rebuilds Tailwind). Existing guards that must stay green: `TrainingEventViewRenderingTest`, `TrainingStatsViewRenderingTest`, `TrainingTimelineViewRenderingTest`, `DanceFigureWebControllerTest`, `TrainingEventWebControllerTest`.

New automated guards: `FragmentCatalogRenderingTest` (each fragment rendered twice — required params only, then all params), `HtmxFragmentRenderingTest` (each htmx endpoint with `HX-Request: true`, asserting the expected root id), `TemplateSafetyTest` (`th:utext` confined to one fragment), `RichTextServiceTest`.

**Stage A1 specifically:** capture screenshots of the six densest pages *before* the migration, repeat after, and diff. "Looks identical" is the acceptance criterion — any visual change is a migration bug.

**Stage B evaluation:** all three branches run side by side at 375 / 768 / 1280 on the same three screens, scored against the eight criteria, with screenshots attached to the written comparison.

Manual, per view-migration PR, at **375px, 768px and 1280px**:
1. `docker compose up -d postgres`, then `./gradlew bootRun` with the required env vars.
2. Walk the migrated views; confirm no horizontal body scroll at 375px and that wide tables scroll inside their own container.
3. Tab through every migrated form: visible focus, labels announced, errors reachable and associated.
4. For C9: bold/italic/list/link round-trip through save→reload; empty editor persists `null` (check `training-events/view.html` shows no empty description panel); edit a comment via htmx and confirm the editor re-binds after the swap.

## Assumptions and out of scope

- **Light mode only.** Tokens are CSS custom properties via `@theme`, so dark mode stays addable later; the current `darkMode: "class"` is completely inert and gets deleted.
- Out of scope: new features, new pages, business-logic changes, schema changes (**none needed**), `ChoreographyEntry.notes` and step-comment rich text, converting `CommentController` to a DTO, and the `Figure`/`DanceFigure` *code* rename (UI labels only).
- The `AdminController` `BindingResult` fix in C6 is the one deliberate, flagged exception to "no logic changes".
- **Two of the three Stage B branches get deleted.** The bake-off is for deciding, not for maintaining parallel implementations.
