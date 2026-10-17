/** One preference owner. Loads cannot overwrite edits; writes are serialized and coalesced. */
export function createPreferenceSession<T>(options: {
  initial: T; dirty?: boolean; load: () => Promise<T | undefined>; save: (value: T) => Promise<void>;
  current: () => boolean; change: (value: T, dirty: boolean) => void;
  failure: (failed: boolean) => void;
}) {
  let value = options.initial, revision = 0, disposed = false, writing = false;
  let dirty = options.dirty ?? false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const current = () => !disposed && options.current();
  const flush = async () => {
    if (!current() || writing || !dirty) return;
    writing = true;
    const captured = revision, saved = value;
    try {
      await options.save(saved);
      if (!current()) return;
      options.failure(false);
      if (captured === revision) {dirty = false; options.change(value, false);}
    } catch {
      if (current()) options.failure(true);
      // A failed revision is retried explicitly; a newer edit can still be sent.
      if (captured === revision) return;
    } finally {writing = false;}
    if (current() && dirty) void flush();
  };
  return {
    async load() {
      const captured = revision;
      try {
        const loaded = await options.load();
        if (!current()) return;
        options.failure(false);
        if (!dirty && captured === revision && loaded !== undefined) {value = loaded; options.change(value, false);}
        if (dirty) void flush();
      } catch {if (current()) options.failure(true);}
    },
    update(next: T) {
      if (!current()) return;
      value = next; revision++; dirty = true; options.change(value, true);
      clearTimeout(timer); timer = setTimeout(() => void flush(), 500);
    },
    retry() {return dirty ? flush() : this.load();},
    dispose() {disposed = true; clearTimeout(timer);},
  };
}
