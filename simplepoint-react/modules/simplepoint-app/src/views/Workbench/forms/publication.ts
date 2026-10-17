/** Captured immutable publication intent, retained across transport failures and retries. */
export type PublicationIntent = {formId: string; revision: number; checksum: string; pageId: string;
  idempotencyKey: string; version?: number};
export type FormVersion = {formId: string; version: number; checksum: string};
export type PublicationInspection = {checkedAt: string; repairPageId?: string | null;
  issues: {id: string; kind: 'REQUIRED' | 'INVALID_VALUE'; fields: string[]; count: string}[]};
export type FormRelease = {
  inspection?: PublicationInspection | null;
  dataPublication: {id: string; formId: string; formVersion: number; status: string; errorCode?: string | null} | null;
  pageBinding?: {pageId: string; formId: string; formVersion: number; status: string; errorCode?: string | null} | null;
};
type Transport = {
  freeze: (revision: number) => Promise<FormVersion>;
  versions: () => Promise<FormVersion[]>;
  publish: (version: number, request: {pageId: string; idempotencyKey: string}) => Promise<FormRelease>;
  mayReconcile: (error: unknown) => boolean;
  current: () => boolean;
};

/** A lost freeze response is resolved by its exact checksum, never by the latest version. */
export async function publishIntent(intent: PublicationIntent, transport: Transport): Promise<FormRelease> {
  if (intent.version === undefined) {
    let version: FormVersion;
    try {version = await transport.freeze(intent.revision);} catch (cause) {
      if (!transport.current() || !transport.mayReconcile(cause)) throw cause;
      let recovered: FormVersion | undefined;
      try {recovered = (await transport.versions()).find(item => item.formId === intent.formId && item.checksum === intent.checksum);}
      catch {throw cause;}
      if (!recovered) throw cause;
      version = recovered;
    }
    if (!transport.current()) throw new DOMException('The editing session has changed', 'AbortError');
    if (version.formId !== intent.formId || version.checksum !== intent.checksum || !Number.isSafeInteger(version.version) || version.version < 1)
      throw new Error('The frozen form version does not match the saved design');
    intent.version = version.version;
  }
  if (!transport.current()) throw new DOMException('The editing session has changed', 'AbortError');
  const result = await transport.publish(intent.version, {pageId: intent.pageId, idempotencyKey: intent.idempotencyKey});
  if (result.dataPublication && (result.dataPublication.formId !== intent.formId || result.dataPublication.formVersion !== intent.version)
    || result.pageBinding && (result.pageBinding.pageId !== intent.pageId || result.pageBinding.formId !== intent.formId
      || result.pageBinding.formVersion !== intent.version)) throw new Error('The publication result does not match the requested page and version');
  return result;
}

export const releaseIsActive = (release?: FormRelease) => release?.pageBinding?.status === 'ACTIVE'
  && release.dataPublication?.status === 'ACTIVE';

export const releaseIsCancelled = (release?: FormRelease) => release?.pageBinding?.status === 'CANCELLED';

export const releaseIsBlocked = (release?: FormRelease) => Boolean(release?.inspection?.issues.length);
