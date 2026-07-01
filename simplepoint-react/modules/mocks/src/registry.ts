import { collectHandlers, type MockResourceModule } from './runtime';
import errorLogs from './auditing/logging/error-logs';
import loginLogs from './auditing/logging/login-logs';
import permissionChangeLogs from './auditing/logging/permission-change-logs';
import redisEntries from './auditing/redis/entries';
import hostRoutes from './host/routes';
import i18nBundles from './i18n/bundles';
import countries from './i18n/countries';
import languages from './i18n/languages';
import messages from './i18n/messages';
import namespaces from './i18n/namespaces';
import regions from './i18n/regions';
import timezones from './i18n/timezones';
import oidcClients from './rbac/core/oidc-clients';
import permissions from './rbac/core/permissions';
import roles from './rbac/core/roles';
import dataScopes from './rbac/core/data-scopes';
import fieldScopes from './rbac/core/field-scopes';
import users from './rbac/core/users';
import menus from './rbac/router/menus';
import microapps from './rbac/router/microapps';
import applications from './rbac/tenant/applications';
import dictionaries from './rbac/tenant/dictionaries';
import features from './rbac/tenant/features';
import organizations from './rbac/tenant/organizations';
import packages from './rbac/tenant/packages';
import tenants from './rbac/tenant/tenants';
import objectStorage from './storage/object-storage';

export const mockModules: MockResourceModule[] = [
  hostRoutes,
  users,
  roles,
  dataScopes,
  fieldScopes,
  permissions,
  oidcClients,
  menus,
  microapps,
  tenants,
  applications,
  packages,
  features,
  organizations,
  dictionaries,
  countries,
  languages,
  messages,
  namespaces,
  regions,
  timezones,
  i18nBundles,
  loginLogs,
  errorLogs,
  permissionChangeLogs,
  redisEntries,
  objectStorage,
];

export const mockResources = mockModules.map((module) => module.contract);
export const mockHandlers = collectHandlers(mockModules);
