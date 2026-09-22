/** Match the server's declared page budget without changing a controlled page behind its owner's back. */
export function pageSizeOptions(maxPageSize?: number, configured: readonly (string | number)[] = [10, 20, 50, 100]) {
  const maximum = Number.isSafeInteger(maxPageSize) && maxPageSize! > 0 ? maxPageSize! : undefined;
  const options = [...new Set(configured.map(Number))]
    .filter(value => Number.isSafeInteger(value) && value > 0 && (maximum === undefined || value <= maximum));
  if (!options.length && maximum !== undefined) options.push(maximum);
  return options.sort((a, b) => a - b);
}
