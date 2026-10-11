import type {EntitySelectSource} from './index';

/** Domain adapters are client-side form extensions, never executable functions from server JSON. */
export function isEntitySelectSource(value: unknown): value is EntitySelectSource {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const source = value as EntitySelectSource;
  return typeof source.key === 'string' && !!source.key.trim()
    && typeof source.searchParams === 'function' && typeof source.option === 'function'
    && (source.resolve === undefined || typeof source.resolve === 'function');
}
