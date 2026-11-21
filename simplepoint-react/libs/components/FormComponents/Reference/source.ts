import type {EntitySelectSource} from '../../EntitySelect/index.tsx';
import type {EntityOption} from '../../EntitySelect/query.ts';
import type {ReferenceSearchContext} from './inputs.ts';

export type ReferenceOption = {id: string; version: number; label: string; values: Record<string, unknown>; canDrillThrough?: boolean};
export type ReferencePage = {page: number; size: number; total: number; records: ReferenceOption[]};
export type ReferenceTransport = {
  search: (keyword: string, page: number, size: number, signal: AbortSignal) => Promise<ReferencePage>;
  resolve: (ids: string[], signal: AbortSignal) => Promise<ReferenceOption[]>;
};

const referencePage = (result: ReferencePage) => ({content: result.records, page: {number: result.page, size: result.size,
  totalElements: result.total, totalPages: Math.ceil(result.total / result.size)}});

/** Each candidate context owns its query lifecycle; exact hydration stays shared by relation. */
export function contextualReferenceSource(source: EntitySelectSource, context: ReferenceSearchContext,
  search: ReferenceTransport['search']): EntitySelectSource {
  return {...source, key: JSON.stringify([source.key, context]),
    search: async (keyword, page, size, signal) => referencePage(await search(keyword, page, size, signal))};
}

function option(row: Record<string, unknown>): EntityOption | undefined {
  return typeof row.id === 'string' && typeof row.label === 'string'
    ? {value: row.id, label: row.label, metadata: {version: row.version, values: row.values,
      canDrillThrough: row.canDrillThrough === true}} : undefined;
}

/** Adapt domain responses without a second selection state or an unbounded selected-ID request. */
export function referenceSource(key: string, transport: ReferenceTransport): EntitySelectSource {
  return {
    key, pageSize: 50, searchParams: (keyword, page, size) => ({keyword, page, size}), option,
    search: async (keyword, page, size, signal) => {
      const result = await transport.search(keyword, page, size, signal);
      return referencePage(result);
    },
    resolve: async (ids, signal) => {
      const distinct = [...new Set(ids)];
      const resolved: EntityOption[] = [];
      for (let start = 0; start < distinct.length; start += 100) {
        signal.throwIfAborted();
        const batch = distinct.slice(start, start + 100);
        const rows = await transport.resolve(batch, signal);
        signal.throwIfAborted();
        for (const row of rows) {
          if (!batch.includes(row.id)) continue;
          const selected = option(row);
          if (selected) resolved.push(selected);
        }
      }
      return resolved;
    },
  };
}
