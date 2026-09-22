export type ScanStatus = 'BUILDING' | 'READY' | 'EXPIRED' | 'BUSY' | 'LIMIT_EXCEEDED' | 'TIMED_OUT' | 'FAILED';

/** A view/filter change keeps the same observed keyspace; only explicit refresh changes versions. */
export class SnapshotSession {
  revision = 0;
  private snapshotId?: string;
  private refreshRequested = false;

  refresh() {
    this.refreshRequested = true;
    return ++this.revision;
  }

  parameters(revision: number) {
    if (revision !== this.revision) throw new Error('Obsolete Redis scan request');
    return {...(this.snapshotId ? {snapshotId: this.snapshotId} : {}),
      ...(this.refreshRequested ? {refresh: true} : {})};
  }

  accept(revision: number, result: {status: ScanStatus; snapshotId?: string | null}, aborted = false) {
    if (aborted || revision !== this.revision) return;
    if (result.snapshotId && result.status !== 'EXPIRED' && result.status !== 'BUSY') {
      this.snapshotId = result.snapshotId;
      this.refreshRequested = false;
    }
  }
}

export function pollingInterval(status?: ScanStatus, active = true): number | false {
  return active && (status === 'BUILDING' || status === 'BUSY') ? 1000 : false;
}

/** A malformed READY response must be a recoverable error, never a permanent empty/loading page. */
export function validateScanResponse(response: unknown): void {
  const result = response as {status?: string; snapshotId?: string; page?: any} | null;
  if (!result || !['BUILDING', 'READY', 'EXPIRED', 'BUSY', 'LIMIT_EXCEEDED', 'TIMED_OUT', 'FAILED'].includes(result.status ?? '')) {
    throw new Error('Invalid Redis scan response');
  }
  if (result.status !== 'READY') {
    if (result.page != null) throw new Error('Partial Redis scan returned a page');
    return;
  }
  const data = result.page, page = data?.page ?? data;
  if (!result.snapshotId || !Array.isArray(data?.content) || !page
    || !['number', 'size', 'totalElements', 'totalPages'].every(field => Number.isSafeInteger(page[field]) && page[field] >= 0)
    || page.size < 1 || page.size > 100 || data.content.length > page.size
    || page.totalPages !== Math.ceil(page.totalElements / page.size)
    || data.content.some((row: any) => !row || typeof row.key !== 'string')
    || new Set(data.content.map((row: any) => row.key)).size !== data.content.length) {
    throw new Error('Invalid Redis result page');
  }
}

/** Masked booleans are not truthy capabilities, and sampled/absent values cannot be edited. */
export function canEditRedisDetail(detail: Record<string, unknown> | null | undefined) {
  return !!detail && detail.editable === true && detail.type === 'STRING'
    && typeof detail.key === 'string' && detail.key !== '***'
    && typeof detail.value === 'string' && detail.value !== '***'
    && detail.sampled !== true && detail.truncated !== true
    && typeof detail.persistent === 'boolean'
    && (detail.persistent || typeof detail.ttlSeconds === 'number'
      && Number.isSafeInteger(detail.ttlSeconds) && detail.ttlSeconds > 0);
}
