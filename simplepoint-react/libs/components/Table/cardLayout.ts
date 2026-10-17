/** Stable card geometry: resizing changes columns, never depends on card content height. */
export function cardGeometry(width: number, rows = 3, maxPageSize = 100, minWidth = 280, gap = 16) {
  const maximum = Number.isSafeInteger(maxPageSize) && maxPageSize > 0 ? maxPageSize : 100;
  const safeRows = rows === 2 || rows === 4 ? rows : 3;
  const measured = Number.isFinite(width) ? Math.max(0, width) : 0;
  const minimum = Number.isFinite(minWidth) ? Math.max(180, minWidth) : 280;
  const spacing = Number.isFinite(gap) ? Math.max(0, gap) : 16;
  const columns = Math.max(1, Math.min(maximum, Math.floor((measured + spacing) / (minimum + spacing))));
  const effectiveRows = Math.max(1, Math.min(safeRows, Math.floor(maximum / columns)));
  return {columns, pageSize: columns * effectiveRows};
}

/** Keep the old first record on the new page and clamp after deleting the last page. */
export function anchoredPage(page: number, oldSize: number, newSize: number, total: number) {
  const safeSize = Math.max(1, newSize);
  const offset = Math.max(0, page - 1) * Math.max(1, oldSize);
  return Math.max(1, Math.min(Math.floor(offset / safeSize) + 1, Math.ceil(total / safeSize)));
}
