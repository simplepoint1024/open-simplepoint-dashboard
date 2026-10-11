/** A settled response for the requested page may prove that deletion removed its last page. */
export function reachableServerPage(current: number, loaded?: {number?: number; totalPages?: number}) {
  if (!Number.isSafeInteger(current) || current < 1 || loaded?.number !== current - 1
    || !Number.isSafeInteger(loaded.totalPages) || loaded.totalPages! < 0) return current;
  return Math.min(current, Math.max(1, loaded.totalPages!));
}
