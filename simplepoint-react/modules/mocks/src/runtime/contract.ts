import type { HttpHandler } from 'msw';

export type BackendModule =
  | 'simplepoint-plugin-rbac-core-rest'
  | 'simplepoint-plugin-rbac-tenant-rest'
  | 'simplepoint-plugin-rbac-router-rest'
  | 'simplepoint-plugin-i18n-rest'
  | 'simplepoint-plugin-auditing-logging-rest'
  | 'simplepoint-plugin-auditing-redis-rest'
  | 'simplepoint-plugin-oidc-rest'
  | 'simplepoint-plugin-storage'
  | 'simplepoint-host';

export type MockScope = 'common' | 'auditing' | 'host' | 'identity' | 'storage';

export interface MockResourceContract {
  id: string;
  scope: MockScope;
  backendModule: BackendModule;
  backendController: string;
  contextPath: string;
  paths: string[];
  entity?: string;
  i18nNamespaces?: string[];
  description?: string;
}

export interface MockResourceModule {
  contract: MockResourceContract;
  handlers: HttpHandler[];
}

export function defineResource(contract: MockResourceContract): MockResourceContract {
  return contract;
}

export function defineMockModule(
  contract: MockResourceContract,
  handlers: HttpHandler[],
): MockResourceModule {
  return { contract, handlers };
}

export function collectHandlers(modules: MockResourceModule[]): HttpHandler[] {
  return modules.flatMap((module) => module.handlers);
}
