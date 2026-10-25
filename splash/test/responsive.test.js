const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('responsive layout: html and css guarantee no horizontal overflow at 375px', () => {
  const indexHtml = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
  const splashCss = fs.readFileSync(path.join(__dirname, '..', 'splash.css'), 'utf8');

  // Verify responsive viewport
  assert.ok(
    indexHtml.includes('name="viewport"') && indexHtml.includes('width=device-width'),
    'index.html must specify responsive viewport'
  );

  // Verify box-sizing border-box applied globally
  assert.ok(
    splashCss.includes('box-sizing: border-box'),
    'splash.css must enforce box-sizing: border-box'
  );

  // Verify container max-width does not exceed standard mobile limits
  assert.ok(
    splashCss.includes('max-width: 24rem') || splashCss.includes('max-width: 384px'),
    'splash container has appropriate max-width'
  );

  // Verify progress track is well within 375px - 48px (327px content width)
  assert.ok(
    splashCss.includes('width: 12.5rem') || splashCss.includes('width: 200px'),
    'progress track width (200px) is well under 327px available mobile width'
  );

  // Verify body prevents horizontal scroll
  assert.ok(
    splashCss.includes('overflow-x: hidden'),
    'body includes overflow-x: hidden to prevent sideways scroll'
  );
});

test('hidden attribute precedence: [hidden] wins over display rules and leaves no gap', () => {
  const indexHtml = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
  const splashCss = fs.readFileSync(path.join(__dirname, '..', 'splash.css'), 'utf8');

  // Both splash.css and index.html critical CSS must enforce [hidden] { display: none !important; }
  // to prevent .retry-section { display: flex } from showing buttons before the timeout
  const hiddenRulePattern = /\[hidden\]\s*\{\s*display:\s*none\s*!important;\s*\}/;

  assert.ok(
    hiddenRulePattern.test(splashCss),
    'splash.css must define [hidden] { display: none !important; } so hidden elements cannot be displayed'
  );
  assert.ok(
    hiddenRulePattern.test(indexHtml),
    'index.html inline style must define [hidden] { display: none !important; }'
  );

  // Neither splash.css nor index.html should have min-height on status-section which would leave an empty gap
  assert.ok(
    !splashCss.includes('min-height: 4rem'),
    'splash.css must not have min-height: 4rem on status-section to prevent empty gaps'
  );
  assert.ok(
    !indexHtml.includes('min-height: 4rem'),
    'index.html must not have min-height: 4rem on status-section to prevent empty gaps'
  );
});
