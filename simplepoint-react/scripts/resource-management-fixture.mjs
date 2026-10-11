import {readFileSync} from 'node:fs';

export const resourceManagementRoute = JSON.parse(readFileSync(
  new URL('./resource-management-contracts/navigation.json', import.meta.url), 'utf8'));
const contracts = Object.fromEntries(['schema', 'page-0', 'page-26', 'filtered', 'scope-options'].map(name => [name,
  JSON.parse(readFileSync(new URL('./resource-management-contracts/' + name + '.json', import.meta.url), 'utf8'))]));

/** Only native schema/HTTP exports; this read-only browser scenario cannot write business records. */
export function createResourceManagementFixture() {
  let enabled = false;
  const reads = [];
  return {reads, enable() {enabled = true;}, async handle(req, url, respond) {
    if (!enabled) return false;
    if (req.method === 'GET' && url.pathname === '/common/platform/dictionaries/options' && url.searchParams.get('dictionaryCode') === 'resource.scope.type') {
      reads.push({path: url.pathname, params: new URLSearchParams(url.searchParams)}); respond(structuredClone(contracts['scope-options'])); return true;
    }
    if (!['/common/resources', '/common/resources/schema'].includes(url.pathname)) return false;
    if (req.method !== 'GET') throw Error('Resource management browser contract must remain read-only');
    reads.push({path: url.pathname, params: new URLSearchParams(url.searchParams)});
    if (url.pathname.endsWith('/schema')) {respond(structuredClone(contracts.schema)); return true;}
    if (Number(url.searchParams.get('size') ?? 20) !== 20) throw Error('Native management browser page must use its actual size');
    if (url.searchParams.has('name')) {
      const filter = JSON.parse(url.searchParams.get('name'));
      if (filter.where?.field !== 'name' || filter.where?.op !== 'like' || filter.where?.value !== 'Management520') throw Error('Management header query lost the real typed contract');
      respond(structuredClone(contracts.filtered)); return true;
    }
    const page = Number(url.searchParams.get('page') ?? 0), result = contracts['page-' + page];
    if (!result) throw Error('Management browser must request a captured native first/tail page');
    respond(structuredClone(result)); return true;
  }};
}
