export type DependencyOptionKind = 'MODEL' | 'AGENT' | 'SKILL';

export type DependencyOption = {
  kind: DependencyOptionKind;
  resourceId: string;
  resourceCode?: string | null;
  resourceName?: string | null;
  resourceVersionId?: string | null;
  resourceVersion?: string | null;
  scopeType?: 'SYSTEM' | 'TENANT' | string | null;
  publishedAt?: string | null;
  selectable: boolean;
  availabilityCode?: string | null;
};

export type DependencyPage = {
  content: DependencyOption[];
  page: {
    size: number;
    totalElements: number;
    totalPages: number;
    number: number;
  };
};

export type DependencyResolutionStatus =
  | 'loading'
  | 'resolved'
  | 'unresolved'
  | 'error';

export type DependencyResolution = {
  status: DependencyResolutionStatus;
  error?: unknown;
};

export type DependencySelectionIssue = {
  id: string;
  reason: 'RESOLVING' | 'UNRESOLVED' | 'RESOLVE_FAILED' | 'NOT_SELECTABLE';
  availabilityCode?: string | null;
};

type RawDependencyPage = Partial<DependencyPage> & {
  size?: number;
  totalElements?: number;
  totalPages?: number;
  number?: number;
};

const finiteNumber = (value: unknown, fallback: number) =>
  typeof value === 'number' && Number.isFinite(value) ? value : fallback;

export const normalizeDependencyQuery = (value?: string) =>
  (value?.trim().replace(/\s+/g, ' ') ?? '').slice(0, 128);

export const dependencyOptionValue = (option: DependencyOption) =>
  (option.kind === 'MODEL'
    ? option.resourceId
    : option.resourceVersionId ?? '').trim();

export const dependencyOptionKey = (option: DependencyOption) =>
  `${option.kind}:${dependencyOptionValue(option)}`;

export const dependencySelectionKey = (
  kind: DependencyOptionKind,
  id: string,
) => `${kind}:${id.trim()}`;

export const uniqueDependencyIds = (ids: readonly (string | null | undefined)[]) => {
  const seen = new Set<string>();
  const result: string[] = [];
  ids.forEach((value) => {
    const id = value?.trim();
    if (!id || seen.has(id)) return;
    seen.add(id);
    result.push(id);
  });
  return result;
};

export const chunkDependencyIds = (
  ids: readonly (string | null | undefined)[],
  size = 50,
) => {
  const normalized = uniqueDependencyIds(ids);
  const safeSize = Math.min(50, Math.max(1, Math.floor(size)));
  const chunks: string[][] = [];
  for (let index = 0; index < normalized.length; index += safeSize) {
    chunks.push(normalized.slice(index, index + safeSize));
  }
  return chunks;
};

export const mergeDependencyOptions = (
  current: readonly DependencyOption[],
  incoming: readonly DependencyOption[],
) => {
  const merged = new Map<string, DependencyOption>();
  [...current, ...incoming].forEach((option) => {
    const value = dependencyOptionValue(option);
    if (value) merged.set(dependencyOptionKey(option), option);
  });
  return Array.from(merged.values());
};

export const normalizeDependencyPage = (
  raw: RawDependencyPage | null | undefined,
  defaultSize = 20,
): DependencyPage => {
  const content = Array.isArray(raw?.content) ? raw.content : [];
  const nested: Partial<DependencyPage['page']> = raw?.page ?? {};
  const size = finiteNumber(nested.size ?? raw?.size, defaultSize);
  const totalElements = finiteNumber(
    nested.totalElements ?? raw?.totalElements,
    content.length,
  );
  const totalPages = finiteNumber(
    nested.totalPages ?? raw?.totalPages,
    size > 0 ? Math.ceil(totalElements / size) : 0,
  );
  const number = finiteNumber(nested.number ?? raw?.number, 0);
  return {
    content,
    page: {size, totalElements, totalPages, number},
  };
};

export const collectDependencySelectionIssues = (
  kind: DependencyOptionKind,
  ids: readonly (string | null | undefined)[],
  options: ReadonlyMap<string, DependencyOption>,
  resolutions: ReadonlyMap<string, DependencyResolution>,
) => uniqueDependencyIds(ids).flatMap((id): DependencySelectionIssue[] => {
  const key = dependencySelectionKey(kind, id);
  const resolution = resolutions.get(key);
  const option = options.get(key);
  if (resolution?.status === 'loading') {
    return [{id, reason: 'RESOLVING'}];
  }
  if (resolution?.status === 'error') {
    return [{id, reason: 'RESOLVE_FAILED'}];
  }
  if (resolution?.status === 'unresolved' || !option) {
    return [{id, reason: 'UNRESOLVED'}];
  }
  if (!option.selectable) {
    return [{
      id,
      reason: 'NOT_SELECTABLE',
      availabilityCode: option.availabilityCode,
    }];
  }
  return [];
});

export const isDependencyAbortError = (error: unknown) => {
  const candidate = error as {kind?: unknown; name?: unknown} | null;
  return candidate?.kind === 'abort' || candidate?.name === 'AbortError';
};
