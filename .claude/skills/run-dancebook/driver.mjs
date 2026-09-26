#!/usr/bin/env node
// Headless browser driver for a running DanceBook. Reads one command per line from stdin
// (or from the files given as arguments) and runs them in order in one browser session.
// The signed-in session is saved between runs, so `login` is only needed once per server.
//
//   node .claude/skills/run-dancebook/driver.mjs <<'EOF'
//   login
//   nav /training
//   screenshot training
//   EOF
//
// Env: BASE_URL (default http://localhost:8080), RUN_DIR (default <repo>/build/dancebook-run).
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const BASE_URL = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
// Inside the repo, not /tmp: Claude's file reads are limited to the working directory.
const RUN_DIR = process.env.RUN_DIR
  || path.join(execSync('git rev-parse --show-toplevel', { encoding: 'utf8' }).trim(), 'build', 'dancebook-run');
const port = new URL(BASE_URL).port || '80';
const SHOTS = path.join(RUN_DIR, 'shots');
const STATE = path.join(RUN_DIR, `session-${port}.json`);
fs.mkdirSync(SHOTS, { recursive: true });

const HELP = `commands:
  login [user] [password]   sign in (default: the dev test account rafal / password123)
  logout                    forget the saved session
  nav <path|url>            open a page and print its HTTP status (404 is printed, not fatal)
  click <selector>          Playwright selector: css, text=Save, role=button[name="Save"]
  fill <selector> <text>    type into an input or textarea
  select <selector> <value> choose an <option> by value or label
  check <selector>          tick a checkbox
  press <key>               e.g. Enter, Escape
  wait-for <selector>       wait until visible (text=... works); htmx swaps need this
  text [selector]           print visible text (default: main, else body), capped at 4000 chars
  screenshot [name]         full-page PNG in RUN_DIR/shots/
  url                       print the current URL
  errors                    print console errors and failed or 5xx requests so far
  eval <js>                 evaluate an expression in the page and print the result
  sleep <ms>                last resort; prefer wait-for`;

const input = process.argv.length > 2
  ? process.argv.slice(2).map((f) => fs.readFileSync(f, 'utf8')).join('\n')
  : fs.readFileSync(0, 'utf8');
const commands = input.split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));

const browser = await chromium.launch({ channel: 'chrome' }).catch(() => chromium.launch());
const context = await browser.newContext({
  baseURL: BASE_URL,
  viewport: { width: 1280, height: 900 },
  storageState: fs.existsSync(STATE) ? STATE : undefined,
});
const page = await context.newPage();
page.setDefaultTimeout(15000);

const problems = [];
page.on('console', (m) => { if (m.type() === 'error') problems.push(`console: ${m.text()}`); });
page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
page.on('requestfailed', (r) => problems.push(`failed: ${r.method()} ${r.url()} ${r.failure()?.errorText}`));
// 5xx anywhere, and 4xx on sub-resources (a 404 page itself is often the expected answer).
page.on('response', (r) => {
  const s = r.status();
  if (s >= 500 || (s >= 400 && !r.request().isNavigationRequest())) problems.push(`${s}: ${r.request().method()} ${r.url()}`);
});
page.on('pageerror', (e) => problems.push(`  ${(e.stack || '').split('\n')[1]?.trim() ?? '?'}`));

const rest = (line) => line.replace(/^\S+\s*/, '');
const splitFirst = (s) => { const m = s.match(/^(\S+)\s*(.*)$/); return m ? [m[1], m[2]] : [s, '']; };
// Selectors may contain spaces when quoted: fill "input[name=title]" Rumba basics
const splitSelector = (s) => {
  const q = s.match(/^"([^"]+)"\s*(.*)$/) || s.match(/^'([^']+)'\s*(.*)$/);
  return q ? [q[1], q[2]] : splitFirst(s);
};

let failed = false;
for (const line of commands) {
  const cmd = line.split(/\s+/)[0];
  const arg = rest(line);
  console.log(`> ${line}`);
  try {
    switch (cmd) {
      case 'help': console.log(HELP); break;
      case 'login': {
        const [user = 'rafal', pass = 'password123'] = arg ? arg.split(/\s+/) : [];
        await page.goto('/login');
        await page.fill('input[name="username"]', user);
        await page.fill('input[name="password"]', pass);
        await Promise.all([page.waitForNavigation(), page.click('button[type="submit"], input[type="submit"]')]);
        if (new URL(page.url()).pathname.startsWith('/login')) throw new Error(`login as ${user} failed (still on ${page.url()})`);
        await context.storageState({ path: STATE });
        console.log(`signed in as ${user} -> ${page.url()}`);
        break;
      }
      case 'logout': fs.rmSync(STATE, { force: true }); await context.clearCookies(); console.log('session cleared'); break;
      case 'nav': {
        const res = await page.goto(arg);
        console.log(`${res?.status()} ${page.url()}`);
        if (new URL(page.url()).pathname.startsWith('/login') && !arg.startsWith('/login')) console.log('(redirected to login - run `login` first)');
        break;
      }
      case 'click': await page.click(splitSelector(arg)[0]); break;
      case 'fill': { const [sel, text] = splitSelector(arg); await page.fill(sel, text); break; }
      case 'select': { const [sel, v] = splitSelector(arg); await page.selectOption(sel, [{ value: v }]).catch(() => page.selectOption(sel, [{ label: v }])); break; }
      case 'check': await page.check(splitSelector(arg)[0]); break;
      case 'press': await page.keyboard.press(arg); break;
      case 'wait-for': await page.waitForSelector(arg, { state: 'visible' }); break;
      case 'text': {
        const sel = arg || ((await page.$('main')) ? 'main' : 'body');
        const t = (await page.innerText(sel)).replace(/\n{3,}/g, '\n\n').trim();
        console.log(t.length > 4000 ? `${t.slice(0, 4000)}\n... (${t.length} chars, truncated)` : t);
        break;
      }
      case 'screenshot': {
        const file = path.join(SHOTS, `${arg || `shot-${Date.now()}`}.png`);
        await page.screenshot({ path: file, fullPage: true });
        console.log(file);
        break;
      }
      case 'url': console.log(page.url()); break;
      case 'errors': console.log(problems.length ? problems.join('\n') : 'no console errors or failed requests'); break;
      case 'eval': console.log(JSON.stringify(await page.evaluate(arg), null, 2)); break;
      case 'sleep': await page.waitForTimeout(Number(arg)); break;
      default: throw new Error(`unknown command "${cmd}" - try help`);
    }
  } catch (e) {
    console.log(`ERROR: ${e.message.split('\n')[0]}`);
    await page.screenshot({ path: path.join(SHOTS, 'error.png'), fullPage: true }).catch(() => {});
    console.log(`(page at failure: ${path.join(SHOTS, 'error.png')})`);
    failed = true;
    break;
  }
}

await context.storageState({ path: STATE }).catch(() => {});
await browser.close();
process.exit(failed ? 1 : 0);
