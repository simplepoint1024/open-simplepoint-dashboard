import { http, HttpResponse, type HttpHandler } from 'msw';
import type { MockResourceContract } from './contract';
import { pageFromUrl } from './page';
import type { EntityLike } from './store';

export interface CrudStore<T extends EntityLike> {
  all: () => T[];
  create: (item: Partial<T>) => T;
  update: (item: Partial<T>) => T;
  remove: (ids: string[]) => string[];
}

export interface CrudHandlersOptions<T extends EntityLike> {
  schema?: any;
  store: CrudStore<T>;
  extraHandlers?: HttpHandler[];
}

function endpoint(path: string) {
  return path.endsWith('/') ? path.slice(0, -1) : path;
}

function idsFromUrl(url: URL) {
  return (url.searchParams.get('ids') ?? '')
    .split(',')
    .map((id) => id.trim())
    .filter(Boolean);
}

export function createCrudHandlers<T extends EntityLike>(
  contract: MockResourceContract,
  options: CrudHandlersOptions<T>,
): HttpHandler[] {
  const handlers = contract.paths.flatMap((path) => {
    const base = endpoint(`${contract.contextPath}${path}`);
    const resourceHandlers: HttpHandler[] = [
      http.get(base, ({ request }) => {
        return HttpResponse.json(pageFromUrl(new URL(request.url), options.store.all()));
      }),
      http.post(base, async ({ request }) => {
        return HttpResponse.json(options.store.create(await request.json() as Partial<T>));
      }),
      http.put(base, async ({ request }) => {
        return HttpResponse.json(options.store.update(await request.json() as Partial<T>));
      }),
      http.delete(base, ({ request }) => {
        return HttpResponse.json(options.store.remove(idsFromUrl(new URL(request.url))));
      }),
    ];

    if (options.schema !== undefined) {
      resourceHandlers.unshift(
        http.get(`${base}/schema`, () => HttpResponse.json(options.schema)),
      );
    }

    return resourceHandlers;
  });

  return [...handlers, ...(options.extraHandlers ?? [])];
}
