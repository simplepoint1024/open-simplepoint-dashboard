import assert from 'node:assert/strict';
import test from 'node:test';
import {
  analyzeTranslationUsage,
  assertTranslationUsage,
  collectTranslationUsages,
  formatTranslationUsageIssues,
} from './check-usage.mjs';

const source = (text, file = 'modules/example/src/Page.tsx') => ({file, source: text});

const bundles = ({en = [], zh = en} = {}) => ({
  'en-US': {common: Object.fromEntries(en.map(key => [key, `en:${key}`]))},
  'zh-CN': {common: Object.fromEntries(zh.map(key => [key, `zh:${key}`]))},
});

const finiteRule = (overrides = {}) => ({
  file: 'modules/example/src/Page.tsx',
  expression: '`status.${state}`',
  expectedOccurrences: 1,
  mode: 'finite',
  resolvedKeys: ['status.ready', 'status.failed'],
  ...overrides,
});

test('collects string and no-substitution template literals from translation calls only', () => {
  const result = collectTranslationUsages([source(`
    const a = t('page.title');
    const b = t(\`page.subtitle\`, 'fallback');
    const c = i18n.t("page.description");
    const d = window.spI18n.t('page.window');
    arbitrary.t('not.translation');
    // t('comment.fake')
    const text = "t('string.fake')";
  `)]);

  assert.deepEqual(
    result.usages.map(usage => usage.key),
    ['page.title', 'page.subtitle', 'page.description', 'page.window'],
  );
  assert.deepEqual(result.issues, []);
});

test('does not allow a fallback to hide a missing literal key', () => {
  const result = analyzeTranslationUsage({
    sources: [source("t('missing.key', 'Visible fallback');")],
    bundles: bundles(),
    rules: [],
  });

  assert.equal(result.issues.length, 1);
  assert.equal(result.issues[0].kind, 'missing-literal');
  assert.deepEqual(result.issues[0].missingLocales, ['en-US', 'zh-CN']);
});

test('rejects every dynamic call that is not explicitly configured', () => {
  const result = analyzeTranslationUsage({
    sources: [source('t(dynamicKey); i18n.t(`status.${state}`);')],
    bundles: bundles(),
    rules: [],
  });

  assert.deepEqual(
    result.issues.map(issue => issue.kind),
    ['unconfigured-dynamic', 'unconfigured-dynamic'],
  );
});

test('accepts a finite dynamic rule only when every resolved key exists in every locale', () => {
  const options = {
    sources: [source('t(`status.${state}`);')],
    bundles: bundles({en: ['status.ready', 'status.failed']}),
    rules: [finiteRule()],
  };

  assert.deepEqual(assertTranslationUsage(options), {
    files: 1,
    literalCalls: 0,
    dynamicCalls: 1,
    dynamicRules: 1,
  });
});

test('reports the exact locale missing a finite resolved key', () => {
  const result = analyzeTranslationUsage({
    sources: [source('t(`status.${state}`);')],
    bundles: bundles({en: ['status.ready', 'status.failed'], zh: ['status.ready']}),
    rules: [finiteRule()],
  });

  const issue = result.issues.find(item => item.kind === 'missing-finite-key');
  assert.equal(issue?.key, 'status.failed');
  assert.deepEqual(issue?.missingLocales, ['zh-CN']);
});

test('runtime rules require a concrete reason', () => {
  const result = analyzeTranslationUsage({
    sources: [source('t(serverKey);')],
    bundles: bundles(),
    rules: [{
      file: 'modules/example/src/Page.tsx',
      expression: 'serverKey',
      expectedOccurrences: 1,
      mode: 'runtime',
      reason: '',
    }],
  });

  assert.ok(result.issues.some(issue => issue.kind === 'invalid-rule'));

  assert.doesNotThrow(() => assertTranslationUsage({
    sources: [source('t(serverKey);')],
    bundles: bundles(),
    rules: [{
      file: 'modules/example/src/Page.tsx',
      expression: 'serverKey',
      expectedOccurrences: 1,
      mode: 'runtime',
      reason: 'The key is authored by the server-side menu registry.',
    }],
  }));
});

test('rejects stale rules, occurrence changes, and duplicate matches', () => {
  const stale = analyzeTranslationUsage({
    sources: [source('const value = 1;')],
    bundles: bundles({en: ['status.ready', 'status.failed']}),
    rules: [finiteRule()],
  });
  assert.ok(stale.issues.some(issue => issue.kind === 'rule-occurrence' && issue.actual === 0));

  const changed = analyzeTranslationUsage({
    sources: [source('t(`status.${state}`);')],
    bundles: bundles({en: ['status.ready', 'status.failed']}),
    rules: [finiteRule({expectedOccurrences: 2})],
  });
  assert.ok(changed.issues.some(issue => issue.kind === 'rule-occurrence'
    && issue.expected === 2 && issue.actual === 1));

  const duplicate = analyzeTranslationUsage({
    sources: [source('t(`status.${state}`);')],
    bundles: bundles({en: ['status.ready', 'status.failed']}),
    rules: [finiteRule(), finiteRule()],
  });
  assert.ok(duplicate.issues.some(issue => issue.kind === 'duplicate-rule'));
  assert.ok(duplicate.issues.some(issue => issue.kind === 'ambiguous-dynamic'));
});

test('reports a literal key that is missing from only one locale', () => {
  const result = analyzeTranslationUsage({
    sources: [source("t('locale.partial');")],
    bundles: bundles({en: ['locale.partial'], zh: []}),
    rules: [],
  });

  assert.deepEqual(result.issues[0].missingLocales, ['zh-CN']);
});

test('aggregates all missing literal locations instead of stopping at the first failure', () => {
  const result = analyzeTranslationUsage({
    sources: [source("t('missing.same');\n\nt('missing.same');\nt('missing.other');")],
    bundles: bundles(),
    rules: [],
  });
  const output = formatTranslationUsageIssues(result.issues);

  assert.match(output, /missing literal missing\.same/);
  assert.match(output, /Page\.tsx:1:1/);
  assert.match(output, /Page\.tsx:3:1/);
  assert.match(output, /missing literal missing\.other/);
  assert.equal(result.issues.filter(issue => issue.kind === 'missing-literal').length, 3);
});
