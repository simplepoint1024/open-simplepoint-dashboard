import type {RoleResourceRow} from '@/api/system/role-resource-configuration';
import type {Page} from '@simplepoint/shared/types/request';
import type {ResourceDraft} from './resourceDraft';

export class PreviewChangedError extends Error {
  constructor() {super('RESOURCE_PREVIEW_CHANGED');}
}

export async function loadResourcePreview(draft: ResourceDraft, signal: AbortSignal, current: () => boolean,
  page: (index: number, signal: AbortSignal) => Promise<Page<RoleResourceRow>>,
  context: (ids: string[], signal: AbortSignal) => Promise<RoleResourceRow[]>): Promise<RoleResourceRow[]> {
  const assertCurrent = () => {if (signal.aborted || !current()) throw new DOMException('Preview cancelled', 'AbortError');};
  const granted = new Map<string, RoleResourceRow>();
  let total: number | undefined, totalPages = 1;
  for (let index = 0; index < totalPages; index++) {
    assertCurrent();
    const result = await page(index, signal); assertCurrent();
    const metadata = result.page;
    if (metadata.number !== index || metadata.size !== 100 || !Number.isSafeInteger(metadata.totalPages)
      || metadata.totalPages < 0 || !Number.isSafeInteger(metadata.totalElements) || metadata.totalElements < 0
      || metadata.totalPages !== Math.ceil(metadata.totalElements / 100)
      || total !== undefined && total !== metadata.totalElements
      || result.content.length !== Math.min(100, Math.max(0, metadata.totalElements - index * 100))) {
      throw new PreviewChangedError();
    }
    total = metadata.totalElements; totalPages = metadata.totalPages;
    for (const row of result.content) {
      if (granted.has(row.id)) throw new PreviewChangedError();
      granted.set(row.id, row);
    }
  }
  for (const entry of Object.values(draft)) granted.set(entry.baseline.id, entry.baseline);
  const ids = [...new Set([...granted.values()].map(row => row.parentId)
    .filter((id): id is string => !!id && !granted.has(id)))];
  for (let offset = 0; offset < ids.length; offset += 200) {
    assertCurrent(); const rows = await context(ids.slice(offset, offset + 200), signal); assertCurrent();
    for (const row of rows) if (!granted.has(row.id)) granted.set(row.id, row);
  }
  return [...granted.values()];
}
