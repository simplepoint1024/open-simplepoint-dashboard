export type EntityOption = {value: string; label: string; disabled?: boolean; title?: string};

export function entitySearchParams(keyword: string, page: number, size = 20) {
  const name = keyword.trim();
  return {page, size, sort: 'name,asc', ...(name ? {
    name: JSON.stringify({version: 1, where: {field: 'name', op: 'like', value: name}}),
  } : {})};
}

export function entityLookupParams(id: string) {
  return {page: 0, size: 1, id: JSON.stringify({version: 1, where: {field: 'id', op: 'equals', value: id}})};
}

/** Bound every selected-value lookup; keep all IDs, including values outside the first search page. */
export function entityLookupBatches(ids: readonly string[]) {
  const distinct = [...new Set(ids.filter(id => typeof id === 'string' && id !== ''))];
  const parameters = (values: string[]) => values.length === 1 ? entityLookupParams(values[0]) : {
      page: 0, size: values.length,
      id: JSON.stringify({version: 1, where: {field: 'id', op: 'in', value: values}}),
    };
  const batches: ReturnType<typeof parameters>[] = [];
  let values: string[] = [];
  for (const id of distinct) {
    const next = [...values, id];
    // Leave room for endpoint and eligibility filters below common gateway request-line limits.
    const wire = new URLSearchParams({id: parameters(next).id}).toString();
    if (values.length && (next.length > 100 || wire.length > 2500)) {
      batches.push(parameters(values)); values = [id];
    } else values = next;
  }
  if (values.length) batches.push(parameters(values));
  return batches;
}

export function mergeEntityOptions(rows: readonly {id: string; name?: string | null}[]): EntityOption[] {
  return [...new Map(rows.filter(row => typeof row.id === 'string' && row.id !== '')
    .map(row => [row.id, {value: row.id, label: row.name || row.id}])).values()];
}
