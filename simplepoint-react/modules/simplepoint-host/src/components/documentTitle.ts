export const DEFAULT_APP_TITLE = 'Simple·Point Platform';

function normalizeTitle(value?: string): string {
  return value?.trim() ?? '';
}

function isRouteLikeTitle(value: string): boolean {
  return value.startsWith('/');
}

export function resolveDocumentTitle(
  pageTitle?: string,
  appTitle?: string,
): string {
  const normalizedAppTitle = normalizeTitle(appTitle) || DEFAULT_APP_TITLE;
  const normalizedPageTitle = normalizeTitle(pageTitle);

  if (!normalizedPageTitle || isRouteLikeTitle(normalizedPageTitle)) {
    return normalizedAppTitle;
  }
  if (normalizedPageTitle === normalizedAppTitle) {
    return normalizedAppTitle;
  }
  return `${normalizedPageTitle} · ${normalizedAppTitle}`;
}
