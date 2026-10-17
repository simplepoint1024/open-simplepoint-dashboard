import type {QueryClient} from '@tanstack/react-query';
import {getQueryScopeSnapshot, scopedQueryKey, type QueryScope} from './queryScope.ts';

export interface PermissionField {type: string; title: string; operators: string[]; values: string[]; readOnly?: boolean}
export interface PermissionPage {models: Array<{properties: Record<string, PermissionField>}>}
export type PermissionCatalog = Record<string, PermissionPage>;
export const permissionCatalogKey = ['permission-expression-catalog'] as const;

/** Derive one real target from the same authorized full snapshot; retain its age and never invent a missing page. */
export function permissionCatalogSeed(client: QueryClient, scope: QueryScope, resourceCode?: string) {
  if (!resourceCode || JSON.stringify(scope) !== getQueryScopeSnapshot()) return {};
  const state = client.getQueryState<PermissionCatalog>(scopedQueryKey(scope, permissionCatalogKey));
  if (state?.status !== 'success' || !state.dataUpdatedAt || !state.data
    || !Object.prototype.hasOwnProperty.call(state.data, resourceCode)) return {};
  return {initialData: {[resourceCode]: state.data[resourceCode]}, initialDataUpdatedAt: state.dataUpdatedAt};
}
