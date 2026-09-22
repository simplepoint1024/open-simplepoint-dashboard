import {readFile, readdir} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {parse} from '@babel/parser';

const root = fileURLToPath(new URL('../', import.meta.url));
const renderers = new Set(['libs/components/Table/index.tsx', 'libs/components/DataTable/index.tsx']);
// A calendar is a keyboard-navigable date grid, not a business record list.
const dateGrids = new Set(['modules/simplepoint-host/src/views/home/HomeCalendar.tsx']);

function walk(node, visit) {
  if (!node || typeof node !== 'object') return;
  if (node.type) visit(node);
  for (const [key, value] of Object.entries(node)) {
    if (['loc', 'start', 'end', 'comments', 'tokens'].includes(key)) continue;
    if (Array.isArray(value)) value.forEach(child => walk(child, visit));
    else if (value && typeof value === 'object') walk(value, visit);
  }
}

export function tableSites(source, file) {
  const ast = parse(source, {sourceType: 'module', plugins: ['typescript', 'jsx']});
  const imports = new Map();
  walk(ast, node => {
    if (node.type !== 'ImportDeclaration' || node.importKind === 'type') return;
    for (const item of node.specifiers) {
      if (item.importKind === 'type') continue;
      imports.set(item.local.name, {source: node.source.value,
        imported: item.type === 'ImportNamespaceSpecifier' ? '*' : item.imported?.name ?? 'default'});
    }
  });
  const sites = [];
  walk(ast, node => {
    if (node.type !== 'JSXOpeningElement') return;
    const name = node.name;
    const member = name.type === 'JSXMemberExpression' ? name.property.name : undefined;
    const local = member ? name.object.name : name.name;
    const imported = imports.get(local);
    const origin = imported?.source ?? '';
    const primitive = local === 'table' || (origin === 'antd' && (imported.imported === 'Table' || member === 'Table'))
      || /^antd\/(es|lib)\/table(?:\/index)?$/.test(origin);
    const platformOrigin = /(?:\/|^)(?:SimpleTable|DataTable|TreeTable|AssignmentTable|Table)(?:\/index)?$/.test(origin);
    const platform = platformOrigin && ((imported.imported === 'default' && !member)
      || ['SimpleTable', 'DataTable', 'TreeTable', 'AssignmentTable', 'Table'].includes(imported.imported));
    if (!primitive && !platform) return;
    const query = node.attributes.find(a => a.type === 'JSXAttribute' && a.name.name === 'query');
    const expression = query?.value?.expression;
    const mode = expression?.type === 'ObjectExpression'
      ? expression.properties.find(p => p.type === 'ObjectProperty' && p.key.name === 'mode')?.value?.value : undefined;
    sites.push({file, line: node.loc.start.line, component: member ? `${local}.${member}` : local, origin,
      kind: dateGrids.has(file) && local === 'table' ? 'date-grid' : renderers.has(file) ? 'platform-renderer' : primitive ? 'nonstandard' : 'platform',
      queryBinding: query ? mode ?? 'dynamic' : origin.includes('SimpleTable') ? 'schema-or-controlled' : 'not-declared',
      attributes: node.attributes.filter(a => a.type === 'JSXAttribute').map(a => a.name.name).sort()});
  });
  return sites;
}

async function sources(directory) {
  const result = [];
  for (const item of await readdir(path.join(root, directory), {withFileTypes: true})) {
    if (['node_modules', 'dist', '.cache', '@mf-types'].includes(item.name)) continue;
    const file = path.join(directory, item.name);
    if (item.isDirectory()) result.push(...await sources(file));
    else if (/\.tsx$/.test(file)) result.push(file);
  }
  return result;
}

export async function auditTables() {
  const files = (await Promise.all(['modules', 'libs'].map(sources))).flat().sort();
  const sites = (await Promise.all(files.map(async file => tableSites(await readFile(path.join(root, file), 'utf8'), file)))).flat();
  return {filesScanned: files.length, sites};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const result = await auditTables();
  if (process.argv.includes('--check')) {
    const violations = result.sites.filter(site => site.kind === 'nonstandard');
    if (violations.length) {
      console.error(violations.map(site => `${site.file}:${site.line} uses nonstandard ${site.component}`).join('\n'));
      process.exitCode = 1;
    } else console.log(`Platform table imports verified across ${result.filesScanned} TSX files (${result.sites.length} table sites).`);
  } else console.log(JSON.stringify(result, null, 2));
}
