export type CatalogSource = 'INTERNAL' | 'OFFICIAL_MCP';
export type CatalogKind = 'MCP_SERVER';
export type CatalogPermissions = {sync: boolean; importRemote: boolean; importRuntime: boolean};
export type CatalogItem = {id: string; source: CatalogSource; kind: CatalogKind; registryName?: string; code?: string;
  version?: string; title?: string; description?: string; status?: string; scopeType?: string; endpointUrl?: string;
  repositoryUrl?: string; websiteUrl?: string; importable: boolean; remoteImportable: boolean; runtimeImportable: boolean; updatedAt?: string};
export type CatalogPage = {content: CatalogItem[]; number: number; size: number; totalElements: number; totalPages: number};
export type ImportOperation = 'remote' | 'runtime';
export function catalogParameters(page: number, size: number, query: string, source?: CatalogSource, kind?: CatalogKind) {
  if (!Number.isInteger(page) || page < 0 || page * size > 2147483647 || !Number.isInteger(size) || size < 1 || size > 100) throw Error('Invalid catalog page');
  const keyword = query.trim(); if (keyword.length > 200) throw Error('Catalog search exceeds 200 characters');
  return {page, size, ...(keyword ? {query: keyword} : {}), ...(source ? {source} : {}), ...(kind ? {kind} : {})};
}
export function canImport(item: CatalogItem, operation: ImportOperation, permissions?: CatalogPermissions) {
  return item.source === 'OFFICIAL_MCP' && !!item.id && item.id !== '***' && item.status === 'ACTIVE'
    && (operation === 'remote' ? permissions?.importRemote === true && item.remoteImportable === true
      : permissions?.importRuntime === true && item.runtimeImportable === true);
}
export function catalogLink(value?: string) {
  if (!value) return undefined;
  try {const url = new URL(value); return url.protocol === 'https:' && url.hostname && !url.username && !url.password ? url.href : undefined;}
  catch {return undefined;}
}
