export interface EntityLike {
  id?: string | number | null;
  [key: string]: unknown;
}

export function createEntityStore<T extends EntityLike>(
  initialItems: T[],
  options?: { idFactory?: (item: Partial<T>, index: number) => string },
) {
  let items = [...initialItems];

  const resolveId = (item: Partial<T>, index: number) => {
    const existing = item.id;
    if (existing !== undefined && existing !== null && existing !== '') {
      return String(existing);
    }
    return options?.idFactory?.(item, index) ?? `mock-${Date.now()}-${index}`;
  };

  return {
    all: () => [...items],
    replaceAll: (nextItems: T[]) => {
      items = [...nextItems];
    },
    create: (item: Partial<T>) => {
      const next = { ...item, id: resolveId(item, items.length) } as T;
      items = [next, ...items];
      return next;
    },
    update: (item: Partial<T>) => {
      const id = resolveId(item, items.length);
      let updated = { ...item, id } as T;
      items = items.map((current) => {
        if (String(current.id) !== id) return current;
        updated = { ...current, ...item, id } as T;
        return updated;
      });
      return updated;
    },
    remove: (ids: string[]) => {
      const removing = new Set(ids.map(String));
      items = items.filter((item) => !removing.has(String(item.id)));
      return ids;
    },
  };
}
