import type {EntityOption} from '../../EntitySelect/query';

type Options = Record<string, unknown>;
const positive = (value: unknown, fallback: number, maximum: number) =>
  Number.isSafeInteger(Number(value)) && Number(value) > 0 ? Math.min(Number(value), maximum) : fallback;
const parameter = (value: unknown, fallback: string) => typeof value === 'string' && value.trim() ? value.trim() : fallback;
const endpoint = (value: unknown, fallback: string) => typeof value === 'string' && value.startsWith('/')
  && !value.startsWith('//') && !/[\\\u0000-\u0020]/.test(value) ? value : fallback;

/** Retain the legacy field options, with the actual endpoint's page budget and safe origin boundary. */
export function userPickerConfiguration(options: Options = {}) {
  const staticParams = Object.fromEntries(Object.entries(options.staticParams && typeof options.staticParams === 'object'
    && !Array.isArray(options.staticParams) ? options.staticParams : {})
    .filter(([, value]) => ['string', 'number', 'boolean'].includes(typeof value)).sort(([a], [b]) => a.localeCompare(b))
    .map(([key, value]) => [key, String(value)]));
  const config = {
    searchEndpoint: endpoint(options.endpoint, '/common/users/picker/items'),
    resolveEndpoint: endpoint(options.resolveEndpoint, '/common/users/picker/selected'),
    pageSize: positive(options.pageSize, 20, 50),
    debounceMs: positive(options.debounceMs, 350, 60000),
    minSearchLength: Math.max(3, positive(options.minSearchLength, 3, 320)),
    pageParam: parameter(options.pageParam, 'page'), sizeParam: parameter(options.sizeParam, 'size'),
    searchParam: parameter(options.searchParam, 'keyword'), resolveParam: parameter(options.resolveParam, 'ids'),
    staticParams,
  };
  return {...config, key: 'user-picker:' + JSON.stringify(config)};
}

export function userPickerIds(value: unknown, multiple: boolean): string[] {
  return [...new Set((multiple ? Array.isArray(value) ? value : [] : [value])
    .filter(item => typeof item === 'string' || typeof item === 'number' && Number.isFinite(item))
    .map(String).map(item => item.trim()).filter(Boolean))];
}

/** Protected/missing identities are not selectable. Never turn a masked value into a real name. */
export function userPickerOption(item: Record<string, unknown>): EntityOption | undefined {
  const text = (value: unknown) => typeof value === 'string' ? value.trim() : typeof value === 'number' && Number.isFinite(value) ? String(value) : '';
  const id = text(item.id); if (!id || /^\*+$/.test(id)) return undefined;
  const display = (value: unknown) => {const result = text(value); return /^\*+$/.test(result) ? '' : result;};
  const name = display(item.name), email = display(item.email), phone = display(item.phoneNumber);
  const primary = name || email || phone || id;
  const secondary = [...new Set([email, phone].filter(value => value && value !== primary))];
  return {value: id, label: secondary.length ? `${primary} · ${secondary.join(' · ')}` : primary};
}

/** Exact CSV hydration is bounded by both the service's 100-ID limit and encoded request size. */
export function userPickerLookupBatches(ids: readonly string[], staticParams: Record<string, string> = {}, resolveParam = 'ids') {
  const batches: string[][] = [];
  let current: string[] = [];
  const wireSize = (values: string[]) => new URLSearchParams({...staticParams, [resolveParam]: values.join(',')}).toString().length;
  for (const id of [...new Set(ids)]) {
    if (id.includes(',') || wireSize([id]) > 2500) throw new Error('已选用户标识无法通过有界回读接口解析');
    const next = [...current, id];
    if (current.length && (next.length > 100 || wireSize(next) > 2500)) {batches.push(current); current = [id];}
    else current = next;
  }
  if (current.length) batches.push(current);
  return batches;
}
