import type {FormRelease, PublicationProgress} from './publication';

function integer(value: string): bigint {
  if (typeof value !== 'string' || !/^(0|[1-9]\d{0,18})$/.test(value)) throw new Error('Invalid publication counter');
  const number = BigInt(value);
  if (number > 9223372036854775807n) throw new Error('Publication counter exceeds its contract');
  return number;
}

/** A phase can reach 100% while the server still owns activation or cancellation confirmation. */
export function publicationPercent(progress: PublicationProgress): number {
  if (progress.contractVersion !== 1 || !['STAGING', 'APPLYING', 'RESTORING'].includes(progress.phase)
    || integer(progress.sequence) < 1n) throw new Error('Invalid publication progress');
  const completed = integer(progress.completedRecords), total = integer(progress.totalRecords);
  if (completed > total) throw new Error('Invalid publication progress totals');
  return total === 0n ? 0 : Number(completed * 100n / total);
}

/** Keep the last acknowledged phase when a competing executor temporarily owns the next batch. */
export function mergeReleaseProgress(previous: FormRelease | undefined, next: FormRelease): FormRelease {
  if (next.progress) publicationPercent(next.progress);
  if (['ACTIVE', 'CANCELLED', 'RETIRED'].includes(next.pageBinding?.status ?? '')) return {...next, progress: undefined};
  const same = previous?.pageBinding?.pageId === next.pageBinding?.pageId
    && previous?.pageBinding?.formId === next.pageBinding?.formId && previous?.pageBinding?.formVersion === next.pageBinding?.formVersion;
  const prior = same ? previous?.progress : undefined;
  if (!prior) return next;
  if (!next.progress || integer(next.progress.sequence) < integer(prior.sequence)) return {...next, progress: prior};
  if (next.progress.sequence === prior.sequence && (next.progress.phase !== prior.phase
    || next.progress.completedRecords !== prior.completedRecords || next.progress.totalRecords !== prior.totalRecords))
    throw new Error('Acknowledged publication progress changed');
  return next;
}
