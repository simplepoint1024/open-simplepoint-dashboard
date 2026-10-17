export type FormAttachment = {id: string; name: string; size: number};

/** Reject arbitrary URLs and malformed metadata; download locations are derived from opaque object IDs. */
export function parseFormAttachments(value: unknown): FormAttachment[] {
  if (value == null || value === '') return [];
  if (typeof value !== 'string') throw new Error('Expected JSON attachment text');
  const files: unknown = JSON.parse(value);
  if (!Array.isArray(files) || files.length > 10) throw new Error('Invalid attachment collection');
  const ids = new Set<string>();
  return files.map(file => {
    if (!file || typeof file !== 'object' || !/^[A-Za-z0-9_-]{1,64}$/.test(file.id)
      || typeof file.name !== 'string' || !file.name.trim() || file.name.length > 255
      || !Number.isSafeInteger(file.size) || file.size <= 0 || file.size > 10 * 1024 * 1024
      || ids.has(file.id)) throw new Error('Invalid attachment metadata');
    ids.add(file.id);
    return {id: file.id, name: file.name, size: file.size};
  });
}
