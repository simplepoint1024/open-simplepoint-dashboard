export type ReferenceProjection = {id: string; label: string | null};
const owns = (value: object, key: PropertyKey) => Object.prototype.hasOwnProperty.call(value, key);

/** A label is useful only for the exact visible, unmasked identity in the same response. */
export function projectedReference(value: unknown, projection: unknown): ReferenceProjection | undefined {
  if (typeof value !== 'string' || !value.trim() || value === '***' || value.length > 64
    || !projection || typeof projection !== 'object' || Array.isArray(projection)) return undefined;
  const item = projection as Partial<ReferenceProjection>;
  if (!owns(item, 'id') || item.id !== value) return undefined;
  const label = owns(item, 'label') ? item.label : undefined;
  if (label === null) return {id: value, label: null};
  if (typeof label !== 'string' || !label.trim() || Array.from(label).length > 256) return undefined;
  return {id: value, label};
}

/** Schema declares one top-level projection map; arbitrary paths and inherited properties are ignored. */
export function referenceLabelFor(record: unknown, map: unknown, field: string): unknown {
  if (!record || typeof record !== 'object' || typeof map !== 'string' || !owns(record, map)) return undefined;
  const labels = (record as Record<string, unknown>)[map];
  return labels && typeof labels === 'object' && !Array.isArray(labels) && owns(labels, field)
    ? (labels as Record<string, unknown>)[field] : undefined;
}
