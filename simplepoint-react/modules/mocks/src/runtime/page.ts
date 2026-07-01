export interface MockPage<T> {
  content: T[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

export function toPage<T>(content: T[], page = 0, size = 20): MockPage<T> {
  const safePage = Number.isFinite(page) && page >= 0 ? page : 0;
  const safeSize = Number.isFinite(size) && size > 0 ? size : 20;
  const start = safePage * safeSize;
  const slice = content.slice(start, start + safeSize);

  return {
    content: slice,
    page: {
      size: safeSize,
      number: safePage,
      totalElements: content.length,
      totalPages: Math.ceil(content.length / safeSize),
    },
  };
}

export function pageFromUrl<T>(url: URL, content: T[]): MockPage<T> {
  return toPage(
    content,
    Number(url.searchParams.get('page') ?? '0'),
    Number(url.searchParams.get('size') ?? '20'),
  );
}
