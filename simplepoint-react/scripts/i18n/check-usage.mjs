import fs from 'node:fs';
import path from 'node:path';
import {parse} from '@babel/parser';

const SOURCE_EXTENSIONS = new Set(['.js', '.jsx', '.ts', '.tsx']);
const IGNORED_DIRECTORIES = new Set([
  '.git',
  '.nx',
  'build',
  'dist',
  'fixtures',
  '__fixtures__',
  '__tests__',
  'node_modules',
  'out',
]);
const I18N_MEMBER_ROOTS = new Set(['i18n', 'spI18n', 'translator', 'translation']);

const toPosixPath = value => value.split(path.sep).join('/');

export const normalizeExpressionText = value => value.trim().replace(/\s+/g, ' ');

const unwrapExpression = expression => {
  let current = expression;
  while (current && [
    'ParenthesizedExpression',
    'TSAsExpression',
    'TSInstantiationExpression',
    'TSNonNullExpression',
    'TSSatisfiesExpression',
    'TSTypeAssertion',
    'TypeCastExpression',
  ].includes(current.type)) {
    current = current.expression;
  }
  return current;
};

const memberPropertyName = expression => {
  if (!expression?.computed && expression?.property?.type === 'Identifier') {
    return expression.property.name;
  }
  if (expression?.computed && expression?.property?.type === 'StringLiteral') {
    return expression.property.value;
  }
  return undefined;
};

const hasI18nMemberRoot = expression => {
  const current = unwrapExpression(expression);
  if (!current) return false;
  if (current.type === 'Identifier') return I18N_MEMBER_ROOTS.has(current.name);
  if (current.type === 'MemberExpression' || current.type === 'OptionalMemberExpression') {
    const property = memberPropertyName(current);
    return I18N_MEMBER_ROOTS.has(property) || hasI18nMemberRoot(current.object);
  }
  return false;
};

const isTranslationCallee = expression => {
  const current = unwrapExpression(expression);
  if (!current) return false;
  if (current.type === 'Identifier') return current.name === 't';
  if (current.type !== 'MemberExpression' && current.type !== 'OptionalMemberExpression') return false;
  return memberPropertyName(current) === 't' && hasI18nMemberRoot(current.object);
};

const literalKey = expression => {
  const current = unwrapExpression(expression);
  if (!current) return undefined;
  if (current.type === 'StringLiteral') return current.value;
  if (current.type === 'TemplateLiteral' && current.expressions.length === 0) {
    return current.quasis[0]?.value?.cooked ?? current.quasis[0]?.value?.raw;
  }
  return undefined;
};

const parserPlugins = file => {
  const extension = path.extname(file);
  const plugins = ['decorators-legacy', 'importAttributes'];
  if (extension === '.ts' || extension === '.tsx') plugins.push('typescript');
  if (extension === '.jsx' || extension === '.tsx') plugins.push('jsx');
  return plugins;
};

const visitAst = (node, visitor) => {
  if (!node || typeof node !== 'object') return;
  if (Array.isArray(node)) {
    node.forEach(child => visitAst(child, visitor));
    return;
  }
  if (typeof node.type === 'string') visitor(node);
  for (const [key, value] of Object.entries(node)) {
    if (['comments', 'errors', 'extra', 'loc', 'tokens'].includes(key)) continue;
    if (value && typeof value === 'object') visitAst(value, visitor);
  }
};

export const collectTranslationUsages = sources => {
  const usages = [];
  const issues = [];

  for (const {file, source} of sources) {
    let ast;
    try {
      ast = parse(source, {
        sourceType: 'unambiguous',
        sourceFilename: file,
        plugins: parserPlugins(file),
      });
    } catch (error) {
      issues.push({
        kind: 'parse-error',
        file,
        message: error instanceof Error ? error.message : String(error),
      });
      continue;
    }

    visitAst(ast.program, node => {
      if (node.type !== 'CallExpression' && node.type !== 'OptionalCallExpression') return;
      if (!isTranslationCallee(node.callee)) return;

      const argument = node.arguments[0];
      const location = node.loc?.start ?? {line: 1, column: 0};
      const base = {
        file,
        line: location.line,
        column: location.column + 1,
      };

      if (!argument || argument.type === 'SpreadElement' || argument.type === 'ArgumentPlaceholder') {
        usages.push({...base, type: 'dynamic', expression: '<missing>'});
        return;
      }

      const key = literalKey(argument);
      if (key !== undefined) {
        usages.push({...base, type: 'literal', key});
        return;
      }

      const expression = source.slice(argument.start, argument.end);
      usages.push({...base, type: 'dynamic', expression: normalizeExpressionText(expression)});
    });
  }

  return {usages, issues};
};

const flattenLocaleKeys = bundles => Object.fromEntries(
  Object.entries(bundles).map(([locale, namespaces]) => [
    locale,
    new Set(Object.values(namespaces).flatMap(messages => Object.keys(messages))),
  ])
);

const validateRuleShape = (rule, index) => {
  const issues = [];
  const label = `dynamic rule #${index + 1}`;
  if (!rule || typeof rule !== 'object' || Array.isArray(rule)) {
    return [{kind: 'invalid-rule', message: `${label} must be an object`}];
  }
  if (typeof rule.file !== 'string' || !rule.file.trim()) {
    issues.push({kind: 'invalid-rule', message: `${label} requires a file`});
  }
  if (typeof rule.expression !== 'string' || !rule.expression.trim()) {
    issues.push({kind: 'invalid-rule', message: `${label} requires an expression`});
  }
  if (!Number.isInteger(rule.expectedOccurrences) || rule.expectedOccurrences < 1) {
    issues.push({kind: 'invalid-rule', message: `${label} expectedOccurrences must be a positive integer`});
  }
  if (rule.mode !== 'finite' && rule.mode !== 'runtime') {
    issues.push({kind: 'invalid-rule', message: `${label} mode must be finite or runtime`});
  }
  if (rule.mode === 'finite') {
    if (!Array.isArray(rule.resolvedKeys) || rule.resolvedKeys.length === 0
      || rule.resolvedKeys.some(key => typeof key !== 'string' || !key)) {
      issues.push({kind: 'invalid-rule', message: `${label} finite rules require non-empty resolvedKeys`});
    } else if (new Set(rule.resolvedKeys).size !== rule.resolvedKeys.length) {
      issues.push({kind: 'invalid-rule', message: `${label} resolvedKeys must be unique`});
    }
  }
  if (rule.mode === 'runtime' && (typeof rule.reason !== 'string' || rule.reason.trim().length < 12)) {
    issues.push({kind: 'invalid-rule', message: `${label} runtime rules require a concrete reason`});
  }
  return issues;
};

const ruleIdentity = rule => `${rule.file}\u0000${normalizeExpressionText(rule.expression)}`;

export const analyzeTranslationUsage = ({sources, bundles, rules = []}) => {
  const collected = collectTranslationUsages(sources);
  const issues = [...collected.issues];
  const localeKeys = flattenLocaleKeys(bundles);
  const locales = Object.keys(localeKeys).sort();

  if (locales.length === 0) {
    issues.push({kind: 'invalid-bundles', message: 'No i18n locales were provided'});
  }

  const normalizedRules = rules.map((rule, index) => ({
    ...rule,
    index,
    file: typeof rule?.file === 'string' ? toPosixPath(rule.file) : rule?.file,
    expression: typeof rule?.expression === 'string'
      ? normalizeExpressionText(rule.expression)
      : rule?.expression,
    matches: 0,
  }));
  normalizedRules.forEach((rule, index) => issues.push(...validateRuleShape(rule, index)));

  const rulesByIdentity = new Map();
  for (const rule of normalizedRules) {
    if (typeof rule.file !== 'string' || typeof rule.expression !== 'string') continue;
    const identity = ruleIdentity(rule);
    const matching = rulesByIdentity.get(identity) ?? [];
    matching.push(rule);
    rulesByIdentity.set(identity, matching);
  }
  for (const matching of rulesByIdentity.values()) {
    if (matching.length > 1) {
      issues.push({
        kind: 'duplicate-rule',
        file: matching[0].file,
        expression: matching[0].expression,
        message: `dynamic expression matches ${matching.length} rules`,
      });
    }
  }

  for (const usage of collected.usages) {
    if (usage.type === 'literal') {
      const missingLocales = locales.filter(locale => !localeKeys[locale].has(usage.key));
      if (missingLocales.length > 0) {
        issues.push({...usage, kind: 'missing-literal', missingLocales});
      }
      continue;
    }

    const identity = `${toPosixPath(usage.file)}\u0000${usage.expression}`;
    const matching = rulesByIdentity.get(identity) ?? [];
    matching.forEach(rule => { rule.matches += 1; });
    if (matching.length === 0) {
      issues.push({...usage, kind: 'unconfigured-dynamic'});
    } else if (matching.length > 1) {
      issues.push({...usage, kind: 'ambiguous-dynamic', matchCount: matching.length});
    }
  }

  for (const rule of normalizedRules) {
    if (Number.isInteger(rule.expectedOccurrences) && rule.matches !== rule.expectedOccurrences) {
      issues.push({
        kind: 'rule-occurrence',
        file: rule.file,
        expression: rule.expression,
        expected: rule.expectedOccurrences,
        actual: rule.matches,
      });
    }
    if (rule.mode !== 'finite' || !Array.isArray(rule.resolvedKeys)) continue;
    for (const key of rule.resolvedKeys) {
      const missingLocales = locales.filter(locale => !localeKeys[locale].has(key));
      if (missingLocales.length > 0) {
        issues.push({
          kind: 'missing-finite-key',
          file: rule.file,
          expression: rule.expression,
          key,
          missingLocales,
        });
      }
    }
  }

  return {
    issues,
    usages: collected.usages,
    summary: {
      files: sources.length,
      literalCalls: collected.usages.filter(usage => usage.type === 'literal').length,
      dynamicCalls: collected.usages.filter(usage => usage.type === 'dynamic').length,
      dynamicRules: rules.length,
    },
  };
};

const locationText = issue => issue.line
  ? `${issue.file}:${issue.line}:${issue.column ?? 1}`
  : (issue.file ?? '<configuration>');

export const formatTranslationUsageIssues = issues => {
  const lines = [`i18n usage check failed with ${issues.length} issue(s):`];
  const missingLiteralGroups = new Map();

  for (const issue of issues) {
    if (issue.kind === 'missing-literal') {
      const id = `${issue.key}\u0000${issue.missingLocales.join(',')}`;
      const group = missingLiteralGroups.get(id) ?? {
        key: issue.key,
        locales: issue.missingLocales,
        locations: [],
      };
      group.locations.push(locationText(issue));
      missingLiteralGroups.set(id, group);
    }
  }

  for (const group of [...missingLiteralGroups.values()].sort((left, right) => left.key.localeCompare(right.key))) {
    lines.push(`- missing literal ${group.key} [${group.locales.join(', ')}]`);
    group.locations.sort().forEach(location => lines.push(`  at ${location}`));
  }

  for (const issue of issues.filter(item => item.kind !== 'missing-literal')) {
    switch (issue.kind) {
      case 'unconfigured-dynamic':
        lines.push(`- unconfigured dynamic key ${issue.expression} at ${locationText(issue)}`);
        break;
      case 'ambiguous-dynamic':
        lines.push(`- dynamic key ${issue.expression} matches ${issue.matchCount} rules at ${locationText(issue)}`);
        break;
      case 'rule-occurrence':
        lines.push(`- dynamic rule ${issue.file} :: ${issue.expression} expected ${issue.expected}, found ${issue.actual}`);
        break;
      case 'missing-finite-key':
        lines.push(`- finite key ${issue.key} [${issue.missingLocales.join(', ')}] from ${issue.file} :: ${issue.expression}`);
        break;
      case 'parse-error':
        lines.push(`- cannot parse ${issue.file}: ${issue.message}`);
        break;
      default:
        lines.push(`- ${issue.message ?? issue.kind}${issue.file ? ` at ${issue.file}` : ''}${issue.expression ? ` :: ${issue.expression}` : ''}`);
    }
  }
  return lines.join('\n');
};

export const assertTranslationUsage = options => {
  const result = analyzeTranslationUsage(options);
  if (result.issues.length > 0) {
    const error = new Error(formatTranslationUsageIssues(result.issues));
    error.name = 'I18nUsageError';
    error.issues = result.issues;
    throw error;
  }
  return result.summary;
};

const isTestSource = fileName => /\.(?:spec|test)\.(?:js|jsx|ts|tsx)$/.test(fileName);

const collectFilesBelow = (root, files) => {
  for (const entry of fs.readdirSync(root, {withFileTypes: true})) {
    if (entry.isDirectory() && IGNORED_DIRECTORIES.has(entry.name)) continue;
    const absolute = path.join(root, entry.name);
    if (entry.isDirectory()) {
      collectFilesBelow(absolute, files);
      continue;
    }
    if (!SOURCE_EXTENSIONS.has(path.extname(entry.name)) || entry.name.endsWith('.d.ts') || isTestSource(entry.name)) {
      continue;
    }
    files.push(absolute);
  }
};

export const collectProjectSources = reactRoot => {
  const sourceRoots = [];
  const modulesRoot = path.join(reactRoot, 'modules');
  if (fs.existsSync(modulesRoot)) {
    for (const entry of fs.readdirSync(modulesRoot, {withFileTypes: true})) {
      const sourceRoot = path.join(modulesRoot, entry.name, 'src');
      if (entry.isDirectory() && fs.existsSync(sourceRoot)) sourceRoots.push(sourceRoot);
    }
  }

  const libsRoot = path.join(reactRoot, 'libs');
  if (fs.existsSync(libsRoot)) {
    for (const entry of fs.readdirSync(libsRoot, {withFileTypes: true})) {
      if (!entry.isDirectory()) continue;
      const libraryRoot = path.join(libsRoot, entry.name);
      const conventionalSourceRoot = path.join(libraryRoot, 'src');
      // Existing workspace libraries keep source directly below libs/<name>.
      sourceRoots.push(fs.existsSync(conventionalSourceRoot) ? conventionalSourceRoot : libraryRoot);
    }
  }

  const files = [];
  sourceRoots.sort().forEach(root => collectFilesBelow(root, files));
  return files.sort().map(file => ({
    file: toPosixPath(path.relative(reactRoot, file)),
    source: fs.readFileSync(file, 'utf8'),
  }));
};

export const readDynamicRules = file => {
  const document = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (!document || document.version !== 1 || !Array.isArray(document.rules)) {
    throw new Error(`Invalid i18n dynamic rules file: ${file}`);
  }
  return document.rules;
};

export const checkProjectTranslationUsage = ({reactRoot, bundles, rulesFile}) => assertTranslationUsage({
  sources: collectProjectSources(reactRoot),
  bundles,
  rules: readDynamicRules(rulesFile),
});
