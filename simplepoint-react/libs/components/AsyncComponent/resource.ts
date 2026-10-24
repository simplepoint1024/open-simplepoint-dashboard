/** Share code loading, cache only success, and permit a fresh attempt after a failed download. */
export function componentResource<T>(loader: () => Promise<{default: T}>) {
  let value: T | undefined;
  let pending: Promise<T> | undefined;
  return {
    current: () => value,
    load: () => {
      if (value !== undefined) return Promise.resolve(value);
      if (!pending) pending = Promise.resolve().then(loader).then(module => {
        value = module.default;
        return value;
      }).finally(() => {pending = undefined;});
      return pending;
    },
  };
}
