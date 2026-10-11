// Production-artifact smoke test with isolated API fixtures (no real account or
// backend writes). MF_SMOKE_EDGE tests deployed remotes; MF_SMOKE_HOST_DIST
// points to static/ extracted from the running host JAR (its HTTP paths require login).
import assert from 'node:assert/strict';
import {createPageReloader, createReadyControlClicker} from './cdp-navigation.mjs';
import {smokeWorkflowFormCommit} from './smoke-workflow-form-commit.mjs';
import {personalNavigation, createPersonalFixture} from './personal-fixture.mjs';
const personalFixture = createPersonalFixture();
import {createAssignmentFixture} from './assignment-fixture.mjs';
import {smokeAssignment} from './smoke-assignment.mjs';
import {createOrganizationFixture, organizationRoute} from './organization-fixture.mjs';
import {smokeOrganization} from './smoke-organization.mjs';
import {smokeOrganizationMembers} from './smoke-organization-members.mjs';
const organizationFixture = createOrganizationFixture();
const assignmentFixture = createAssignmentFixture();
import {createMemberFixture, memberRoute} from './member-fixture.mjs';
import {smokeMembers, smokeMemberViewPersistence} from './smoke-members.mjs';
import {smokeMemberLifecycle} from './smoke-member-lifecycle.mjs';
const memberFixture = createMemberFixture();
import {createOrganizationPickerFixture} from './organization-picker-fixture.mjs';
import {smokeOrganizationPicker} from './smoke-organization-picker.mjs';
const organizationPickerFixture = createOrganizationPickerFixture(memberFixture);
import {createRoleAssignmentFixture} from './role-assignment-fixture.mjs';
import {smokeRoleAssignment} from './smoke-role-assignment.mjs';
const roleAssignmentFixture = createRoleAssignmentFixture();
import {createOrganizationSelectionFixture, organizationSelectionRoute} from './organization-selection-fixture.mjs';
import {smokeOrganizationSelection, smokeReadonlyOrganizationSelection} from './smoke-organization-selection.mjs';
const organizationSelectionFixture = createOrganizationSelectionFixture();
import {createCommonMainFixture, commonMainRoutes} from './common-main-fixture.mjs';
import {smokeCommonMain} from './smoke-common-main.mjs';
import {smokeFieldMatrix, smokeFieldPolicy, smokeFieldLifecycle, smokeFieldProtected} from './smoke-field-matrix.mjs';
const commonMainFixture = createCommonMainFixture();
import {createDictionaryLifecycleFixture} from './dictionary-lifecycle-fixture.mjs';
import {smokeDictionaryLifecycle} from './smoke-dictionary-lifecycle.mjs';
const dictionaryLifecycleFixture = createDictionaryLifecycleFixture();
import {createUserPickerFixture} from './user-picker-fixture.mjs';
import {smokeUserPicker} from './smoke-user-picker.mjs';
const userPickerFixture = createUserPickerFixture();
import {createConfigurationLifecycleFixture} from './configuration-lifecycle-fixture.mjs';
import {smokeConfigurationLifecycle} from './smoke-configuration-lifecycle.mjs';
const configurationLifecycleFixture = createConfigurationLifecycleFixture();
import {applicationResourceRoute, createApplicationResourceFixture} from './application-resource-fixture.mjs';
import {smokeApplicationResources} from './smoke-application-resources.mjs';
const applicationResourceFixture = createApplicationResourceFixture();
import {createResourceManagementFixture, resourceManagementRoute} from './resource-management-fixture.mjs';
import {smokeResourceManagement} from './smoke-resource-management.mjs';
const resourceManagementFixture = createResourceManagementFixture();
import {createRoleLifecycleFixture} from './role-lifecycle-fixture.mjs';
import {smokeRoleLifecycle} from './smoke-role-lifecycle.mjs';
const roleLifecycleFixture = createRoleLifecycleFixture();
import {createNotificationLifecycleFixture} from './notification-lifecycle-fixture.mjs';
import {smokeNotificationLifecycle} from './smoke-notification-lifecycle.mjs';
const notificationLifecycleFixture = createNotificationLifecycleFixture();
import {createCatalogFixture, catalogRoute} from './catalog-fixture.mjs';
import {smokeCatalog} from './smoke-catalog.mjs';
const catalogFixture = createCatalogFixture();
import {createBillingFixture, billingRoute} from './billing-fixture.mjs';
import {smokeBilling} from './smoke-billing.mjs';
const billingFixture = createBillingFixture();
import {createMcpLifecycleFixture, mcpLifecycleRoute} from './mcp-lifecycle-fixture.mjs';
import {smokeMcpLifecycle} from './smoke-mcp-lifecycle.mjs';
const mcpLifecycleFixture = createMcpLifecycleFixture();
import {createRuntimeFixture, runtimeRoute} from './runtime-fixture.mjs';
import {smokeRuntime} from './smoke-runtime.mjs';
import {smokeRuntimeMutations} from './smoke-runtime-mutations.mjs';
import {createMcpCapabilitiesFixture, mcpCapabilitiesRoute} from './mcp-capabilities-fixture.mjs';
import {smokeMcpCapabilities} from './smoke-mcp-capabilities.mjs';
const mcpCapabilitiesFixture = createMcpCapabilitiesFixture();
import {createJdbcUserFixture, jdbcUserRoute} from './jdbc-user-fixture.mjs';
import {smokeJdbcUsers} from './smoke-jdbc-users.mjs';
const jdbcUserFixture = createJdbcUserFixture();
import {createStorageCrudFixture, storageCrudRoutes} from './storage-crud-fixture.mjs';
import {smokeStorageCrud} from './smoke-storage-crud.mjs';
const storageCrudFixture = createStorageCrudFixture();
import {createDnaWorkbenchFixture, metadataRoute} from './dna-workbench-fixture.mjs';
import {smokeDnaWorkbench} from './smoke-dna-workbench.mjs';
const dnaWorkbenchFixture = createDnaWorkbenchFixture();
import {createJdbcArtifactFixture, jdbcArtifactRoutes} from './jdbc-artifact-fixture.mjs';
import {smokeJdbcArtifacts} from './smoke-jdbc-artifacts.mjs';
const jdbcArtifactFixture = createJdbcArtifactFixture();
import {createAppWorkbenchFixture, appWorkbenchRoute} from './app-workbench-fixture.mjs';
import {smokeAppWorkbench} from './smoke-app-workbench.mjs';
const appWorkbenchFixture = createAppWorkbenchFixture();
import {createRedisFixture, redisRoute} from './redis-fixture.mjs';
import {smokeRedis} from './smoke-redis.mjs';
import {smokeRedisMutations} from './smoke-redis-mutations.mjs';
import {createKnowledgeFixture, knowledgeRoute} from './knowledge-fixture.mjs';
import {smokeKnowledge} from './smoke-knowledge.mjs';
import {createApiKeyFixture, apiKeyRoute} from './api-key-fixture.mjs';
import {smokeApiKeys} from './smoke-api-keys.mjs';
import {smokeFormSubmission} from './smoke-form-submission.mjs';
import {createDataScopeFixture} from './data-scope-fixture.mjs';
import {smokeDataScope} from './smoke-data-scope.mjs';
const dataScopeFixture = createDataScopeFixture();
import {createIdentityProviderFixture} from './identity-provider-fixture.mjs';
import {smokeIdentityProvider} from './smoke-identity-provider.mjs';
const identityProviderFixture = createIdentityProviderFixture();
import {createAiConfigurationFixture, aiProviderRoute} from './ai-configuration-fixture.mjs';
import {smokeAiConfiguration} from './smoke-ai-configuration.mjs';
const aiConfigurationFixture = createAiConfigurationFixture();
import {createExactDecimalFixture, exactDecimalRoute} from './exact-decimal-fixture.mjs';
import {smokeExactDecimal} from './smoke-exact-decimal.mjs';
const exactDecimalFixture = createExactDecimalFixture();
const apiKeyFixture = createApiKeyFixture();
const knowledgeFixture = createKnowledgeFixture();
const redisFixture = createRedisFixture();
const runtimeFixture = createRuntimeFixture();
import {createEntitySelectFixture} from './entity-select-fixture.mjs';
import {smokeEntitySelect} from './smoke-entity-select.mjs';
import {createWorkflowFixture, workflowRoutes} from './workflow-fixture.mjs';
import {smokeWorkflow} from './smoke-workflow.mjs';
import {smokeWorkflowLifecycle} from './smoke-workflow-lifecycle.mjs';
import {smokeWorkflowPersonal} from './smoke-workflow-personal.mjs';
import {smokeExpressionModes} from './smoke-expression-modes.mjs';
import {smokeConditionModes} from './smoke-condition-modes.mjs';
import {smokeHostPreferences} from './smoke-host-preferences.mjs';
import {createProfileAccessFixture} from './profile-access-fixture.mjs';
import {smokeProfileAccess} from './smoke-profile-access.mjs';
const profileAccessFixture = createProfileAccessFixture();
import {smokeHomeCustomization} from './smoke-home-customization.mjs';
import {createTenantResourceFixture, tenantResourceRoutes} from './tenant-resource-fixture.mjs';
import {smokeTenantResources} from './smoke-tenant-resources.mjs';
const tenantResourceFixture = createTenantResourceFixture();
const workflowFixture = createWorkflowFixture();
const workflowTenant = 'workflow-smoke-tenant';
const initialWorkflowWorkspace = ['MF_SMOKE_WORKFLOW_PERSONAL_ONLY', 'MF_SMOKE_WORKFLOW_LIFECYCLE_ONLY',
  'MF_SMOKE_WORKFLOW_FORM_COMMIT_ONLY', 'MF_SMOKE_WORKFLOW_ONLY'].some(key => process.env[key] === '1');
const entitySelectFixture = createEntitySelectFixture();
import {createUserAssignmentFixture, tenantAssignmentRoute} from './user-assignment-fixture.mjs';
import {smokeUserAssignment} from './smoke-user-assignment.mjs';
const userAssignmentFixture = createUserAssignmentFixture();
import {spawn} from 'node:child_process';
import {createServer, get as httpGet} from 'node:http';
import {get as httpsGet} from 'node:https';
import {mkdtemp, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {setTimeout as delay} from 'node:timers/promises';

const root = fileURLToPath(new URL('../', import.meta.url));
const edgeCa = process.env.MF_SMOKE_CA_FILE ? await readFile(process.env.MF_SMOKE_CA_FILE) : undefined;
const pages = [
  {remote: 'common', module: 'common', expose: 'platform/Package', api: '/common/platform/packages', label: '套餐包管理'},
  {remote: 'auditing', module: 'audit', expose: 'monitoring/LoginLog', api: '/auditing/logging/login-logs', label: '登录日志'},
  {remote: 'dna', module: 'dna', expose: 'platform/JdbcDataSource', api: '/dna/platform/dna/data-sources', label: '数据源'},
  {remote: 'ai', module: 'ai', expose: 'workbench/Models', api: '/ai/workbench/models', label: '模型管理'},
];
const remotes = [...pages, {remote: 'workflow', module: 'workflow'}, {remote: 'app', module: 'app'}];
const cacheTestRoutes = Array.from({length: 10}, (_, i) => ({
  id: `cache-${i}`, code: 'common.view', type: 'PAGE', routeKind: 'item',
  path: `/smoke/cache-${i}`, component: 'common/platform/Package', title: '',
  label: `缓存测试 ${i}`, icon: 'AppstoreOutlined', scopeTypes: ['PLATFORM'],
}));
const storageRoute = {id: 'storage-test', code: 'common.view', type: 'PAGE', routeKind: 'item', path: '/system/object-storage', component: 'common/platform/ObjectStorage', label: 'OSS 配置测试', scopeTypes: ['PLATFORM']};
const sqlRoute = {id: 'sql-test', code: 'dna.view', type: 'PAGE', routeKind: 'item', path: '/smoke/sql', component: 'dna/platform/SqlConsole', label: 'SQL 控制台', scopeTypes: ['PLATFORM']};
const sqlExecutions = [];
let sqlFailure = false;
let sqlExports = 0;
let sqlTreeFailure = true;
let sqlTreeCalls = 0;
let connectionTestSuccess = false;
let simulateNewRelease = false;
const apiRequests = [];
const network = [];
const exceptions = [];
const warnings = [];
let heldDictionary;
let heldPage;
let activeStreams = 0;
const homePreferences = new Map();
const personalTablePreferences = new Map();
let homeSaveFailure = false;
let queryViewFailure = false;
let homeSaveCount = 0;
let homeExtra = false, homeExtraFailure = false, notificationDelay = 0, notificationPending = 0;
const homeWrites = [], notificationWrites = [], notificationStates = new Map();
const homeFixture = {preferences: homePreferences, writes: homeWrites, notificationWrites, tenantId: tenantResourceFixture.tenantId,
  enable() {homeExtra = true; tenantResourceFixture.enable();}, disable() {homeExtra = false; notificationDelay = 0; tenantResourceFixture.disable();},
  failWrite() {homeExtraFailure = true;}, notificationDelay(value) {notificationDelay = value;}, pendingNotifications() {return notificationPending;}};
let notificationRead = false;
const notification = {id: 'home-notice', title: '首页通知测试', content: '通知正文：请检查工作空间设置。', category: 'SYSTEM', priority: 'NORMAL', publishAt: '2026-09-18T01:00:00Z', linkUrl: '/profile'};
let rejectDictionary = process.env.MF_SMOKE_DICTIONARY_FAILURE === '1';
const expectedDictionaryFailures = [];
let uploadStatus = 200;
let saveStatus = 200;
let invalidUpload = false;
const uploads = [];
const profileSaves = [];
let currentProfile = {id: 'smoke', nickname: 'Smoke user', enabled: true,
  birthdate: '1990-01-02T12:30:41.123Z', gender: 'custom-value', locale: 'fr-FR', zoneinfo: 'UTC'};
const profileFixture = {profileSaves, passwordSaves: [], pictureSaves: [], delay: 0,
  failSave: status => {saveStatus = status;}, failUpload: status => {uploadStatus = status;},
  invalidUpload: value => {invalidUpload = value;}, profile: () => currentProfile};
const expectedUploadFailures = [];
const json = (res, data, status = 200) => {
  res.writeHead(status, {'Content-Type': 'application/json', 'Cache-Control': 'no-store'});
  res.end(JSON.stringify(data));
};
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    const pathname = url.pathname;
    const record = {url: req.url, method: req.method, start: performance.now()};
    const respond = (data, status = 200) => {record.end = performance.now(); json(res, data, status);};
    if (!/\.(js|css|ico|woff2?|png|svg|json|map)$/.test(pathname)) apiRequests.push(record);
    if (await dataScopeFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await exactDecimalFixture.handle(req, url, respond)) return;
    if (await aiConfigurationFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}),
      payload => {record.end = performance.now(); res.writeHead(200, {'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store'}); res.end(payload);})) return;
    if (await identityProviderFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await organizationPickerFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await notificationLifecycleFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await roleLifecycleFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await applicationResourceFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await resourceManagementFixture.handle(req, url, respond)) return;
    if (await configurationLifecycleFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await userPickerFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await tenantResourceFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await workflowFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await dnaWorkbenchFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await jdbcArtifactFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await appWorkbenchFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await mcpCapabilitiesFixture.handle(req, url, respond)) return;
    if (await jdbcUserFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await storageCrudFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await redisFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await knowledgeFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await dictionaryLifecycleFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await apiKeyFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await runtimeFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await entitySelectFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await commonMainFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await catalogFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await billingFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await mcpLifecycleFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await personalFixture.handle(req, url, respond)) return;
    if (await assignmentFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await organizationFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await memberFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await organizationSelectionFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await roleAssignmentFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (await userAssignmentFixture.handle(req, url, respond, status => expectedUploadFailures.push({url: origin + req.url, status}))) return;
    if (pathname === '/common/platform/tenants/users/capabilities') return respond({canConfigureMembers: false});
    if (pathname === '/common/data-scopes/presentation') return respond({pages: {}, dataRules: {'preview-old': '原组织范围', 'preview-new': '新组织范围'}, fieldRules: {}, organizations: {}});
    if (pathname === '/common/data-scopes/expression/catalog') return respond({});
    if (pathname === '/userinfo') return respond({sub: 'smoke', preferred_username: 'smoke', name: 'MF smoke'});
    if (pathname === '/dna/platform/dna/federation/sql-console/workbench-permissions') return respond({canExecute: true, canExplain: true});
    if (pathname === '/common/platform/object-storage/providers') return respond([]);
    if (pathname === '/common/platform/object-storage/provider-configs/schema') return respond({
      ...commonMainFixture.datasets.find(dataset => dataset.name === 'StorageProvider').schema,
      buttons: [{key: 'test', title: '测试连接', sort: 0, argumentMinSize: 1, argumentMaxSize: 1}],
    });
    if (pathname === '/common/platform/object-storage/provider-configs') return respond({
      content: [{id: 'provider-smoke', name: 'Smoke OSS', code: 'smoke', type: 'MINIO', bucket: 'smoke', enabled: true}],
      page: {number: 0, size: 10, totalElements: 1, totalPages: 1},
    });
    if (pathname === '/common/platform/object-storage/provider-configs/provider-smoke/test') {
      return respond({success: connectionTestSuccess, message: '测试连接失败，请检查配置（测试）'});
    }
    if (pathname === '/common/object-storage/images/smoke-upload') {
      res.writeHead(200, {'Content-Type': 'image/svg+xml'});
      res.end('<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"/>');
      return;
    }
    if (pathname === '/common/object-storage/upload') {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const body = Buffer.concat(chunks);
      uploads.push({size: body.length, contentType: req.headers['content-type']});
      if (uploadStatus !== 200) {
        expectedUploadFailures.push({url: origin + req.url, status: uploadStatus});
        res.writeHead(uploadStatus); res.end(); return;
      }
      return respond(invalidUpload ? {} : {id: 'smoke-upload', originalFileName: 'smoke.txt'});
    }
    if (pathname === '/common/users/change-password' && req.method === 'POST') {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      profileFixture.passwordSaves.push(JSON.parse(Buffer.concat(chunks).toString()));
      await delay(profileFixture.delay); return respond({});
    }
    if (pathname === '/common/users/me' || pathname === '/common/users/me/picture') {
      if (req.method === 'PUT') {
        const chunks = [];
        for await (const chunk of req) chunks.push(chunk);
        const values = JSON.parse(Buffer.concat(chunks).toString());
        profileSaves.push(values);
        if (pathname.endsWith('/picture')) profileFixture.pictureSaves.push(values);
        await delay(profileFixture.delay);
        if (saveStatus !== 200) {
          expectedUploadFailures.push({url: origin + req.url, status: saveStatus});
          return json(res, {message: '头像资料保存失败（测试）'}, saveStatus);
        }
        currentProfile = {...currentProfile, ...values};
        return respond(currentProfile);
      }
      return respond(currentProfile);
    }
    if (profileAccessFixture.handle(req, url, respond)) return;
    if (pathname === '/account/security-links') return json(res, {twoFactor: `${origin}/account/2fa`,
      externalIdentities: `${origin}/account/external-identities`});
    if (pathname === '/common/tenants/current') return respond([{tenantId: '__platform__', tenantCode: '__platform__', tenantName: '平台', tenantType: 'PLATFORM'},
      {tenantId: workflowTenant, tenantCode: workflowTenant, tenantName: '工作流测试空间', tenantType: 'ORGANIZATION'}]);
    if (pathname === '/common/tenants/current-profile' && req.headers['x-tenant-id'] === workflowTenant) return respond({
      id: workflowTenant, name: '工作流测试空间', tenantType: 'ORGANIZATION', resourceMode: 'PLATFORM'});
    if (pathname.includes('current-roles')) return respond([]);
    if (pathname.includes('authorization-context-id')) return respond('smoke-context');
    if (pathname === '/common/resources/service-routes') return respond({
      services: remotes.map(p => ({name: p.remote, entry: `/${p.remote}/mf/mf-manifest.json`})),
      entryPoint: '/mf/mf-manifest.json',
      authorizationContext: {scopeType: [tenantResourceFixture.tenantId, workflowTenant].filter(Boolean).includes(req.headers['x-tenant-id']) ? 'TENANT' : 'PLATFORM',
        actorRole: [tenantResourceFixture.tenantId, workflowTenant].filter(Boolean).includes(req.headers['x-tenant-id']) ? 'TENANT_OWNER' : 'PLATFORM_ADMIN',
        resources: [...new Set([...remotes.map(p => `${p.remote}.view`), ...(req.headers['x-tenant-id'] === tenantResourceFixture.tenantId ? tenantResourceRoutes.map(route => route.code) : [])])]},
      routes: [...personalNavigation, ...workflowRoutes, ...pages.map((p, id) => ({id: String(id + 1), code: `${p.remote}.view`, type: 'PAGE', routeKind: 'item', path: `/smoke/${p.remote}`, component: `${p.remote}/${p.expose}`, title: '', label: p.label, icon: 'AppstoreOutlined', scopeTypes: ['PLATFORM']})), ...cacheTestRoutes, storageRoute, sqlRoute, organizationRoute, memberRoute, tenantAssignmentRoute, organizationSelectionRoute, ...commonMainRoutes, aiProviderRoute, exactDecimalRoute, applicationResourceRoute, resourceManagementRoute, runtimeRoute, redisRoute, knowledgeRoute, apiKeyRoute, mcpCapabilitiesRoute, jdbcUserRoute, ...storageCrudRoutes, metadataRoute, ...jdbcArtifactRoutes, appWorkbenchRoute, catalogRoute, billingRoute, mcpLifecycleRoute, ...(tenantResourceFixture.isActive() ? tenantResourceRoutes : [])],
    });
    if (pathname === '/common/i18n/languages/mapping') return respond({'zh-CN': '简体中文', 'en-US': 'English'});
    if (pathname === '/common/i18n/messages/mapping') {
      const locale = url.searchParams.get('locale') === 'en-US' ? 'en-US' : 'zh-CN';
      const bundles = JSON.parse(await readFile(path.join(root, `modules/mocks/src/i18n/bundles/local/${locale}.json`)));
      if (url.searchParams.get('ns')?.includes('packages')) await delay(700);
      return respond({...Object.assign({}, ...Object.values(bundles)), 'smoke.column': locale === 'en-US' ? 'Smoke name' : '测试名称'});
    }
    const notificationIsRead = homeExtra ? !!notificationStates.get(req.headers['x-tenant-id']) : notificationRead;
    if (pathname === '/common/notifications/unread-count') return respond({count: notificationIsRead ? 0 : 1});
    if (pathname === '/common/notifications/inbox') return respond({content: [{...notification, read: notificationIsRead}], page: {number: 0, size: 20, totalElements: 1, totalPages: 1}});
    if (pathname === '/common/notifications/home-notice/read' || pathname === '/common/notifications/read-all') {
      if (homeExtra) {notificationWrites.push({tenantId: req.headers['x-tenant-id'], path: pathname}); notificationStates.set(req.headers['x-tenant-id'], true);
        notificationPending++; try {await delay(notificationDelay);} finally {notificationPending--;}}
      else notificationRead = true;
      return respond(null);
    }
    if (pathname === '/common/notifications/stream') {
      activeStreams++;
      res.once('close', () => activeStreams--);
      res.writeHead(200, {'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store'});
      res.write(': connected\n\n');
      return;
    }
    if (pathname === '/common/platform/dictionaries/options') {
      if (rejectDictionary && url.searchParams.get('dictionaryCode') === 'smoke-status-auditing') {
        expectedDictionaryFailures.push(req.url);
        return json(res, {message: 'Smoke dictionary unavailable'}, 500);
      }
      if (!heldDictionary) {
        heldDictionary = () => respond([{value: 'active', label: '启用'}]);
        return;
      }
      return respond([{value: 'active', label: '启用'}]);
    }
    if (pathname === '/dna/platform/dna/federation/sql-console/execute' || pathname.endsWith('/sql-console/explain')) {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const payload = JSON.parse(Buffer.concat(chunks).toString());
      sqlExecutions.push(payload);
      await delay(700);
      if (sqlFailure) return respond({success: false, message: 'SQL fixture: permission denied'});
      const analysis = {catalogCode: payload.catalogCode, policyCode: 'smoke', maxRows: 200, timeoutMs: 5000,
        dataSources: ['smoke'], planText: 'JdbcTableScan', pushedSqls: ['SELECT 42'], schemaCacheHit: true,
        mountedDataSourceCount: 1, schemaAssemblyTimeMs: 2};
      if (pathname.endsWith('/explain')) return respond(analysis);
      return respond({type: 'QUERY', queryResult: {...analysis, columns: [{name: 'id', typeName: 'INTEGER'}, {name: 'name', typeName: 'VARCHAR'}],
        rows: Array.from({length: 150}, (_, i) => [i + 1, 'sql-row-' + (i + 1)]), returnedRows: 150, executionTimeMs: 8, truncated: false}});
    }
    if (pathname.endsWith('/sql-console/export')) { sqlExports++; return respond([]); }
    if (pathname === '/dna/platform/dna/metadata/smoke-row-0/children') {
      sqlTreeCalls++;
      if (sqlTreeFailure) { sqlTreeFailure = false; return respond({success: false, message: 'SQL fixture: metadata failed'}); }
      return respond([{key: 'table-1', title: 'demo.table', type: 'TABLE', leaf: true, path: [{type: 'SCHEMA', name: 'public'}, {type: 'TABLE', name: 'demo.table'}]}]);
    }
    const page = pages.find(p => pathname === p.api || pathname === `${p.api}/schema`);
    if (page) {
      if (pathname.endsWith('/schema')) return respond({
        schema: {type: 'object', properties: {
          name: {type: 'string', title: 'i18n:smoke.column', 'x-ui': {'x-list-visible': true}},
          status: {type: 'string', title: '状态', 'x-dictionary-code': `smoke-status-${page.remote}`, 'x-ui': {'x-list-visible': true}},
          attachment: {type: 'string', title: '附件', 'x-ui': {widget: 'OssFile'}},
        }},
        buttons: [{key: 'add', title: '新增', icon: 'PlusOutlined', sort: 1, argumentMaxSize: 0}, {key: 'edit', title: '编辑', icon: 'EditOutlined', sort: 2, argumentMinSize: 1, argumentMaxSize: 1},
          ...(page.remote === 'common' ? [{key: 'config.application', title: '配置应用', sort: 3, argumentMinSize: 1, argumentMaxSize: 1}] : [])],
      });
      if (page.remote === 'dna' && (url.searchParams.get('size') === '200' || url.searchParams.has('enabled'))) return respond({
        content: [{id: 'smoke-row-0', code: 'smoke', name: 'smoke-dna', enabled: true}],
        page: {number: 0, size: Number(url.searchParams.get('size')), totalElements: 1, totalPages: 1},
      });
      const pageNumber = Number(url.searchParams.get('page') ?? 0);
      const payload = {content: [{id: `smoke-row-${pageNumber}`, code: 'smoke', name: `smoke-${page.remote}${pageNumber ? '-page2' : ''}`, status: 'active'}], totalElements: 40, totalPages: 2, size: 20, number: pageNumber};
      if (page.remote === 'common' && pageNumber === 1) {
        heldPage = () => respond(payload);
        return;
      }
      return respond(payload);
    }
    if (pathname.startsWith('/common/users/preferences')) {
      const key = url.searchParams.get('key') || '';
      if (key.includes('/common/personal/') || key.startsWith('sp.table.views.')) {
        if (key.startsWith('sp.table.views.') && req.method === 'GET' && queryViewFailure) {
          expectedUploadFailures.push({url: origin + req.url, status: 500});
          return json(res, {message: 'View preference load failed (test)'}, 500);
        }
        if (req.method === 'PUT') {
          const chunks = []; for await (const chunk of req) chunks.push(chunk);
          personalTablePreferences.set(key, JSON.parse(Buffer.concat(chunks).toString()).value);
        } else if (req.method === 'DELETE') personalTablePreferences.delete(key);
        return respond(personalTablePreferences.get(key) ?? null);
      }
      if (!key.startsWith('home.v1.')) return respond(null);
      if (req.method === 'PUT') {
        const chunks = [];
        for await (const chunk of req) chunks.push(chunk);
        homeSaveCount++;
        if (homeExtra) {homeWrites.push({key, body: JSON.parse(Buffer.concat(chunks).toString()), tenantId: req.headers['x-tenant-id']}); await delay(250);}
        if (homeSaveFailure || homeExtraFailure) {
          homeExtraFailure = false;
          expectedUploadFailures.push({url: origin + req.url, status: 500});
          return json(res, {message: '首页保存失败（测试）'}, 500);
        }
        homePreferences.set(key, JSON.parse(Buffer.concat(chunks).toString()).value);
        return respond(null);
      }
      return respond(homePreferences.get(key) ?? null);
    }
    if (/^\/(common|auditing|dna|ai)\//.test(pathname) && !pathname.includes('/mf/')) return respond([]);

    const remote = remotes.find(p => pathname.startsWith(`/${p.remote}/mf/`));
    if (pathname === '/common/mf/mf-manifest.json') await delay(500);
    if (process.env.MF_SMOKE_EDGE && remote) {
      const target = new URL(req.url, process.env.MF_SMOKE_EDGE);
      const upstream = (target.protocol === 'https:' ? httpsGet : httpGet)(target, {
        headers: {Host: 'home.simplepoint.online:8000'},
        ...(edgeCa ? {ca: edgeCa} : {}),
      }, remote => {
        res.writeHead(remote.statusCode, remote.headers);
        remote.pipe(res);
      });
      upstream.on('error', error => {res.writeHead(502); res.end(error.message);});
      return;
    }
    const relative = remote ? pathname.slice(`/${remote.remote}/`.length) : pathname.slice(1);
    const directory = !remote && process.env.MF_SMOKE_HOST_DIST
      ? path.resolve(process.env.MF_SMOKE_HOST_DIST)
      : path.join(root, 'modules', `simplepoint-${remote?.module ?? 'host'}`, 'dist');
    let file = path.resolve(directory, relative || 'index.html');
    if (!file.startsWith(`${directory}/`)) {res.writeHead(403); res.end(); return;}
    if (!remote && !path.extname(relative)) file = path.join(directory, 'index.html');
    const mime = {'.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.html': 'text/html', '.ico': 'image/x-icon'};
    const originalBody = await readFile(file);
    const body = simulateNewRelease && pathname === '/'
      ? originalBody.toString().replace(/\/assets\/js\/index\.[a-f0-9]+\.js/, '/assets/js/index.ffffffffffff.js')
      : originalBody;
    res.writeHead(200, {'Content-Type': mime[path.extname(file)] ?? 'application/octet-stream'});
    res.end(body);
  } catch (error) {res.writeHead(404); res.end(error.message);}
});

await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const profile = await mkdtemp(path.join(tmpdir(), 'simplepoint-mf-smoke-'));
const chrome = spawn(process.env.CHROME_BIN ?? '/usr/bin/google-chrome-stable', [
  '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-extensions', '--disable-background-networking', '--no-first-run',
  `--user-data-dir=${profile}`, '--remote-debugging-port=0', 'about:blank',
], {stdio: ['ignore', 'ignore', 'pipe']});
let socket;
let stderr = '';
chrome.stderr.on('data', data => {stderr += data;});
const poll = async (check, description, timeout = 20000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await check();
    if (value) return value;
    await delay(50);
  }
  throw new Error(`Timed out: ${description}`);
};
try {
  const port = await poll(async () => {
    try {return (await readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0];} catch {return undefined;}
  }, 'Chrome DevTools startup');
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
  await new Promise(resolve => socket.addEventListener('open', resolve, {once: true}));
  const pending = new Map();
  let sequence = 0;
  socket.addEventListener('message', ({data}) => {
    const message = JSON.parse(data);
    navigation.onEvent(message);
    if (message.id) {
      const handlers = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) handlers.reject(new Error(JSON.stringify(message.error)));
      else handlers.resolve(message.result);
    }
    // Reload deliberately discards the SQL draft tested above; acknowledge its native guard.
    if (message.method === 'Page.javascriptDialogOpening' && message.params.type === 'beforeunload') {
      void send('Page.handleJavaScriptDialog', {accept: true});
    }
    if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails);
    if (message.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(message.params.type)) warnings.push(message.params.args.map(arg => arg.value ?? arg.description).join(' '));
    if (message.method === 'Network.responseReceived') network.push({url: message.params.response.url, status: message.params.response.status});
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    pending.set(id, {resolve, reject});
    socket.send(JSON.stringify({id, method, params}));
  });
  const navigation = createPageReloader({send, poll});
  const {reload} = navigation;
  const evaluate = async expression => {
    const result = await send('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  const clickText = async (selector, text) => {
    const match = `[...document.querySelectorAll(${JSON.stringify(selector)})].find(e=>!e.disabled && e.getClientRects().length && e.textContent.replaceAll(' ','')===${JSON.stringify(text.replaceAll(' ', ''))})`;
    await poll(() => evaluate(`!!(${match})`), 'enabled visible action ' + text);
    await evaluate(`(${match}).click()`);
  };
  const clickReady = createReadyControlClicker({evaluate, poll});
  const fill = async (selector, value) => evaluate(`(() => {const e=document.querySelector(${JSON.stringify(selector)});Object.getOwnPropertyDescriptor(e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set.call(e,${JSON.stringify(value)});e.dispatchEvent(new Event('input',{bubbles:true}));})()`);
  await send('Runtime.enable');
  await send('Network.enable');
  await send('Network.setCacheDisabled', {cacheDisabled: true});
  await send('Page.enable');
  await send('Page.setLifecycleEventsEnabled', {enabled: true});
  await send('Page.addScriptToEvaluateOnNewDocument', {source: `
    localStorage.setItem('sp.tenantId', ${JSON.stringify(initialWorkflowWorkspace ? workflowTenant : '__platform__')});
    localStorage.setItem(${JSON.stringify('sp.contextId:' + (initialWorkflowWorkspace ? workflowTenant : '__platform__'))}, 'smoke-context');
    localStorage.setItem('sp.nav.tabs', JSON.stringify(${JSON.stringify(cacheTestRoutes.map(route => ({key: route.path, location: route.path, label: route.label, closable: true})))}));
  `});
  await send('Page.navigate', {url: origin});
  const clickMenu = async label => {
    await poll(() => evaluate(`!![...document.querySelectorAll('.ant-menu-item')].find(e => e.textContent.trim() === ${JSON.stringify(label)})`), `menu ${label}`);
    await evaluate(`[...document.querySelectorAll('.ant-menu-item')].find(e => e.textContent.trim() === ${JSON.stringify(label)}).click()`);
  };
  const chooseFile = async (selector, size, type, name) => {
    await poll(() => evaluate(`!!document.querySelector(${JSON.stringify(selector)})`), 'upload input');
    await evaluate(`(() => {
      const input = document.querySelector(${JSON.stringify(selector)});
      const transfer = new DataTransfer();
      transfer.items.add(new File([new Uint8Array(${size})], ${JSON.stringify(name)}, {type: ${JSON.stringify(type)}}));
      input.files = transfer.files;
      input.dispatchEvent(new Event('change', {bubbles: true}));
    })()`);
  };
  if (process.env.MF_SMOKE_DATA_SCOPE_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeDataScope({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: dataScopeFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && expectedUploadFailures.some(failure => message.includes(new URL(failure.url).pathname))));
    assert.deepEqual(network.filter(record => record.status >= 400 && !expectedUploadFailures.some(f => f.url === record.url && f.status === record.status)), []);
    console.log('PASS: targeted DataScope lifecycle with genuine production form engine, exported actual HTTP contracts and isolated writes');
  } else if (process.env.MF_SMOKE_EXACT_DECIMAL_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeExactDecimal({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: exactDecimalFixture, ai: aiConfigurationFixture});
    assert.deepEqual(exceptions, []); assert.deepEqual(warnings, []);
    assert.deepEqual(network.filter(record => record.status >= 400), []);
    console.log('PASS: targeted exact decimal production forms, queries and protected default CRUD');
  } else if (process.env.MF_SMOKE_AI_CONFIGURATION_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeAiConfiguration({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: aiConfigurationFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && expectedUploadFailures.some(failure => message.includes(new URL(failure.url).pathname))));
    assert.deepEqual(network.filter(record => record.status >= 400 && !expectedUploadFailures.some(f => f.url === record.url && f.status === record.status)), []);
    console.log('PASS: targeted AI provider/model ownership, real native forms, bounded selector and SSE lifecycle');
  } else if (process.env.MF_SMOKE_IDENTITY_PROVIDER_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeIdentityProvider({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: identityProviderFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && expectedUploadFailures.some(failure => message.includes(new URL(failure.url).pathname))));
    assert.deepEqual(network.filter(record => record.status >= 400 && !expectedUploadFailures.some(f => f.url === record.url && f.status === record.status)), []);
    console.log('PASS: targeted IdentityProvider lifecycle with actual production components and isolated API fixtures');
  } else if (process.env.MF_SMOKE_FORM_SUBMISSION_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeFormSubmission({clickMenu, clickReady, clickText, fill, poll, evaluate,
      fixture: apiKeyFixture, dictionaryFixture: dictionaryLifecycleFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/(?:platform\/dictionar|ai\/workbench\/api-keys)/.test(message)));
    assert.deepEqual(network.filter(record => record.status >= 400 && !expectedUploadFailures.some(f => f.url === record.url && f.status === record.status)), []);
    console.log('PASS: targeted shared SForm/SimpleTable prevalidation lifecycle with actual production resource pages');
  } else if (process.env.MF_SMOKE_NOTIFICATION_LIFECYCLE_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeNotificationLifecycle({clickMenu, clickReady, clickText, fill, poll, evaluate, reload, fixture: notificationLifecycleFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/(system\/notifications|users\/picker)/.test(message)));
    assert.deepEqual(network.filter(record => record.status >= 400 && !expectedUploadFailures.some(f => f.url === record.url && f.status === record.status)), []);
    console.log('PASS: targeted Notification lifecycle with actual production components and isolated API fixtures');
  } else if (process.env.MF_SMOKE_MEMBER_LIFECYCLE_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeMemberLifecycle({clickMenu, clickReady, clickText, fill, poll, evaluate, reload,
      fixture: memberFixture, roles: roleAssignmentFixture, requests: apiRequests});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/(tenant-members|users\/(authorize|unauthorized))/.test(message)));
    assert.deepEqual(network.filter(record => record.status >= 400 && !expectedUploadFailures.some(f => f.url === record.url && f.status === record.status)), []);
    console.log('PASS: targeted TenantMembers retained lifecycle with real production components and isolated HTTP fixtures');
  } else if (process.env.MF_SMOKE_ROLE_LIFECYCLE_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeRoleLifecycle({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: roleLifecycleFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/role-resource-configuration\//.test(message)));
    assert.deepEqual(network.filter(record => record.status >= 400 && !expectedUploadFailures.some(f => f.url === record.url && f.status === record.status)), []);
    console.log('PASS: targeted Role retained lifecycle with actual production components and isolated HTTP fixtures');
  } else if (process.env.MF_SMOKE_RESOURCE_MANAGEMENT_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeResourceManagement({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: resourceManagementFixture});
    assert.deepEqual(exceptions, []); assert.deepEqual(warnings, []);
    assert.deepEqual(network.filter(record => record.status >= 400 && !expectedUploadFailures.some(f => f.url === record.url && f.status === record.status)), []);
    console.log('PASS: targeted management Resource production page with actual schema and query contracts');
  } else if (process.env.MF_SMOKE_APPLICATION_RESOURCES_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeApplicationResources({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: applicationResourceFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/(platform\/applications|resources)/.test(message)));
    assert.deepEqual(network.filter(record => record.status >= 400 && !expectedUploadFailures.some(f => f.url === record.url && f.status === record.status)), []);
    console.log('PASS: targeted application resource lifecycle with production artifacts and actual HTTP contracts');
  } else if (process.env.MF_SMOKE_CONFIGURATION_LIFECYCLE_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeConfigurationLifecycle({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: configurationLifecycleFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message=>message.startsWith('[API]') && /\/platform\/(tenants|packages|applications)/.test(message)));
    assert.deepEqual(network.filter(record=>record.status >= 400 && !expectedUploadFailures.some(f=>f.url === record.url && f.status === record.status)), []);
    console.log('PASS: targeted shared configuration lifecycle with actual backend schemas and isolated API fixtures');
  } else if (process.env.MF_SMOKE_ORGANIZATION_PICKER_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeOrganizationPicker({clickMenu, clickReady, clickText, fill, poll, evaluate,
      fixture: organizationPickerFixture, member: memberFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && message.includes('/organizations/options')));
    assert.deepEqual(network.filter(record => record.status >= 400 && !expectedUploadFailures.some(f => f.url === record.url && f.status === record.status)), []);
    console.log('PASS: targeted OrganizationSelect with actual production components and isolated count-free HTTP pages');
  } else if (process.env.MF_SMOKE_USER_PICKER_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeUserPicker({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: userPickerFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/users\/picker\//.test(message)));
    assert.deepEqual(network.filter(r=>r.status >= 400 && !expectedUploadFailures.some(f=>f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted UserPicker with production artifacts and actual single/multiple schema contracts');
  } else if (process.env.MF_SMOKE_DICTIONARY_LIFECYCLE_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeDictionaryLifecycle({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: dictionaryLifecycleFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/platform\/(dictionaries|dictionary-items)/.test(message)));
    assert.deepEqual(network.filter(r=>r.status >= 400 && !expectedUploadFailures.some(f=>f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted Dictionary configuration lifecycle with actual schemas and isolated fixtures');
  } else if (process.env.MF_SMOKE_API_KEYS_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeApiKeys({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: apiKeyFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/(api-keys|platform\/dictionaries)/.test(message)));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted API keys and shared default CRUD lifecycle with isolated fixtures');
  } else if (process.env.MF_SMOKE_KNOWLEDGE_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeKnowledge({clickMenu, clickReady, clickText, fill, poll, evaluate, chooseFile, fixture: knowledgeFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/knowledge-bases/.test(message)));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted knowledge lifecycle with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_REDIS_MUTATIONS_ONLY === '1' || process.env.MF_SMOKE_REDIS_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    if (process.env.MF_SMOKE_REDIS_MUTATIONS_ONLY === '1') await smokeRedisMutations({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: redisFixture});
    else await smokeRedis({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: redisFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/redis\/entries/.test(message)));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted Redis production artifacts with isolated API fixtures');
  } else if (process.env.MF_SMOKE_APP_WORKBENCH_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeAppWorkbench({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: appWorkbenchFixture, workspace: tenantResourceFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/app\/applications/.test(message)));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted application workbench with production artifacts and isolated tenant API fixtures');
  } else if (process.env.MF_SMOKE_JDBC_ARTIFACTS_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeJdbcArtifacts({clickMenu, clickReady, clickText, fill, poll, evaluate, chooseFile, fixture: jdbcArtifactFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/dna\/(drivers|dialects)/.test(message)));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted JDBC artifact lifecycle with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_RUNTIME_MUTATIONS_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeRuntimeMutations({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: runtimeFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/runtime\/(pools|workloads|secrets)/.test(message)));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted Runtime writes with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_RUNTIME_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeRuntime({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: runtimeFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/runtime\/(nodes|pools|workloads|secrets)/.test(message)));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted Runtime standard layout and queries with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_MCP_LIFECYCLE_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeMcpLifecycle({clickMenu, clickReady, clickText, poll, evaluate, fixture: mcpLifecycleFixture, workspace: tenantResourceFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/(mcp\/servers\/workbench-permissions|runtime\/pools\/actual-pool\/redeploy)/.test(message)));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted MCP lifecycle with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_BILLING_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeBilling({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: billingFixture, workspace: tenantResourceFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && message.includes('/billing/summary')));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted billing with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_WORKFLOW_PERSONAL_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeWorkflowPersonal({clickText, clickReady, fill, poll, evaluate, send, reload, fixture: workflowFixture,
      capturePreview: process.env.MF_SMOKE_WORKFLOW_PERSONAL_SCREENSHOT ? async () => {
        await poll(() => evaluate(`!document.getAnimations().some(a=>a.playState==='running' && a.effect?.getComputedTiming().iterations!==Infinity && a.effect?.target?.closest?.('.ant-drawer'))`), 'progress preview settled');
        const screenshot = await send('Page.captureScreenshot', {format: 'png'});
        await writeFile(process.env.MF_SMOKE_WORKFLOW_PERSONAL_SCREENSHOT, Buffer.from(screenshot.data, 'base64'));
      } : undefined});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && message.includes('/workflow/tasks/taskreject/reject')));
    console.log('PASS: targeted consolidated personal workflow with production artifacts and isolated tenant APIs');
  } else if (process.env.MF_SMOKE_WORKFLOW_LIFECYCLE_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeWorkflowLifecycle({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: workflowFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && /\/workflow\/(definitions\/definition1\/versions\/1\/copy-to-draft|tasks\/task1\/complete|instances\/instancecancel\/cancel)/.test(message)));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted workflow lifecycle with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_CATALOG_ONLY) {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeCatalog({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: catalogFixture,
      mode: process.env.MF_SMOKE_CATALOG_ONLY, workspace: tenantResourceFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && message.includes('/catalog')));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted catalog with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_ORGANIZATION_MEMBERS_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeOrganizationMembers({clickMenu, clickReady, clickText, fill, poll, evaluate, send, reload, fixture: memberFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && message.includes('/tenant-members/by-organization/')));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted organization members with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_HOME_CUSTOMIZATION_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeHomeCustomization({clickMenu, clickReady, clickText, fill, poll, evaluate, reload, fixture: homeFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && message.includes('/users/preferences')));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted home customization with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_FIELD_PROTECTED_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeFieldProtected({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: commonMainFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && message.includes('/field-scopes')));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted protected field matrix and native metadata-only CRUD');
  } else if (process.env.MF_SMOKE_FIELD_LIFECYCLE_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeFieldLifecycle({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: commonMainFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && message.includes('/field-scopes/')));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted native field matrix lifecycle and actual HTTP metadata contracts');
  } else if (process.env.MF_SMOKE_FIELD_POLICY_ONLY) {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeFieldPolicy({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: commonMainFixture, mode: process.env.MF_SMOKE_FIELD_POLICY_ONLY});
    assert.deepEqual(exceptions, []);
    assert.deepEqual(warnings, []);
    assert.deepEqual(network.filter(r => r.status >= 400), []);
    console.log('PASS: targeted field policies with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_FIELD_MATRIX_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeFieldMatrix({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: commonMainFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && message.includes('/field-scopes/configuration')));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted field matrix with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_TENANT_RESOURCES_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await clickMenu('首页');
    await smokeTenantResources({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: tenantResourceFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && message.includes('/tenants/current-')));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted tenant resources with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_PROFILE_ACCESS_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeProfileAccess({clickMenu, clickReady, poll, evaluate, send, fixture: profileAccessFixture});
    assert.deepEqual(exceptions, []);
    assert.deepEqual(warnings, []);
    assert.deepEqual(network.filter(r => r.status >= 400), []);
    console.log('PASS: targeted profile access with production artifacts and native HTTP contracts');
  } else if (process.env.MF_SMOKE_HOST_PREFERENCES_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeHostPreferences({clickMenu, clickReady, clickText, fill, poll, evaluate, send, chooseFile, fixture: profileFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && (message.includes('/users/me') || message.includes('/object-storage/upload'))));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted host preferences with production artifacts and isolated API fixtures');
  } else if (process.env.MF_SMOKE_ROLE_AUTHORITY_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeExpressionModes({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: workflowFixture, roleOnly: true});
    assert.deepEqual(exceptions, []); assert.deepEqual(warnings, []);
    assert.deepEqual(network.filter(r => r.status >= 400), []);
    console.log('PASS: unsupported comma-delimited role authority cannot be misselected as distinct native groups');
  } else if (process.env.MF_SMOKE_DUE_DATE_CALENDAR_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeExpressionModes({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: workflowFixture, calendarOnly: true});
    assert.deepEqual(exceptions, []); assert.deepEqual(warnings, []);
    assert.deepEqual(network.filter(r => r.status >= 400), []);
    console.log('PASS: existing calendar spelling is preserved without browser-specific date normalization');
  } else if (process.env.MF_SMOKE_DUE_DATE_LITERAL_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeExpressionModes({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: workflowFixture, dueDateOnly: true});
    assert.deepEqual(exceptions, []); assert.deepEqual(warnings, []);
    assert.deepEqual(network.filter(r => r.status >= 400), []);
    console.log('PASS: actual due-date input/save rejects native-invalid scalars without changing temporal expressions');
  } else if (process.env.MF_SMOKE_PRIORITY_LITERAL_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeExpressionModes({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: workflowFixture, priorityOnly: true});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && expectedUploadFailures.some(failure => message.includes(new URL(failure.url).pathname))));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: priority literal ordinary preview matches the native task field conversion');
  } else if (process.env.MF_SMOKE_WORKFLOW_FORM_COMMIT_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeWorkflowFormCommit({clickMenu, clickText, poll, evaluate, fixture: workflowFixture});
    assert.deepEqual(exceptions, []); assert.deepEqual(warnings, []);
    assert.deepEqual(network.filter(r => r.status >= 400), []);
    console.log('PASS: actual workflow runtime form input and submission share a consistent commit boundary');
  } else if (process.env.MF_SMOKE_EXPRESSION_NUMERIC_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeExpressionModes({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: workflowFixture, numericOnly: true});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && expectedUploadFailures.some(failure => message.includes(new URL(failure.url).pathname))));
    assert.deepEqual(network.filter(r => r.status >= 400 && !expectedUploadFailures.some(f => f.url === r.url && f.status === r.status)), []);
    console.log('PASS: targeted numeric identities with real workflow designer and isolated APIs');
  } else if (process.env.MF_SMOKE_EXPRESSION_STRINGS_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeConditionModes({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: workflowFixture, stringsOnly: true});
    assert.deepEqual(exceptions, []); assert.deepEqual(warnings, []);
    assert.deepEqual(network.filter(r => r.status >= 400), []);
    console.log('PASS: targeted expression strings with real workflow designer and isolated APIs');
  } else if (process.env.MF_SMOKE_CONDITION_MODES_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeConditionModes({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: workflowFixture});
    assert.deepEqual(exceptions, []); assert.deepEqual(warnings, []);
    assert.deepEqual(network.filter(r => r.status >= 400), []);
    console.log('PASS: targeted condition modes with real workflow designer, draft requests and isolated APIs');
  } else if (process.env.MF_SMOKE_EXPRESSION_MODES_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeExpressionModes({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: workflowFixture});
    assert.deepEqual(exceptions, []); assert.deepEqual(warnings, []);
    assert.deepEqual(network.filter(r => r.status >= 400), []);
    console.log('PASS: targeted expression modes with real workflow designer, draft requests and isolated APIs');
  } else if (process.env.MF_SMOKE_WORKFLOW_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeWorkflow({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: workflowFixture,
      capturePreview: process.env.MF_SMOKE_WORKFLOW_SCREENSHOT ? async () => {
        const screenshot = await send('Page.captureScreenshot', {format: 'png'});
        await writeFile(process.env.MF_SMOKE_WORKFLOW_SCREENSHOT, Buffer.from(screenshot.data, 'base64'));
      } : undefined});
    assert.deepEqual(exceptions, []);
    console.log('PASS: targeted workflow production artifacts with isolated API fixtures');
    assert.deepEqual(warnings, [], 'workflow interaction must not emit unexpected warnings');
  } else if (process.env.MF_SMOKE_DNA_WORKBENCH_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeDnaWorkbench({clickMenu, clickReady, clickText, fill, poll, evaluate, send, reload, fixture: dnaWorkbenchFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && message.includes('/metadata/')));
    console.log('PASS: targeted DNA workbench production artifacts with isolated API fixtures');
  } else if (process.env.MF_SMOKE_COMMON_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeCommonMain({clickMenu, clickReady, clickText, fill, poll, evaluate, reload, fixture: commonMainFixture});
    assert.deepEqual(exceptions, []);
    console.log('PASS: targeted Common resource production artifacts with isolated API fixtures');
  } else if (process.env.MF_SMOKE_STORAGE_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeStorageCrud({clickMenu, clickReady, clickText, fill, poll, evaluate, reload, fixture: storageCrudFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && message.includes('/provider-configs')));
    console.log('PASS: targeted storage CRUD production artifacts with isolated API fixtures');
  } else if (process.env.MF_SMOKE_JDBC_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeJdbcUsers({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: jdbcUserFixture});
    assert.deepEqual(exceptions, []);
    assert.ok(warnings.every(message => message.startsWith('[API]') && message.includes('/permissions')));
    console.log('PASS: targeted JDBC assignment production artifacts with isolated API fixtures');
  } else if (process.env.MF_SMOKE_MCP_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await smokeMcpCapabilities({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: mcpCapabilitiesFixture});
    assert.deepEqual(exceptions, []); assert.deepEqual(warnings, []);
    console.log('PASS: targeted MCP production artifacts with isolated API fixtures');
  } else if (process.env.MF_SMOKE_RESOURCE_ONLY === '1') {
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    commonMainFixture.enable();
    await smokeEntitySelect({clickMenu, clickReady, clickText, fill, poll, evaluate, reload, fixture: entitySelectFixture, common: commonMainFixture,
      capturePreview: process.env.MF_SMOKE_RESOURCE_SCREENSHOT ? async () => {
        await delay(300);
        const screenshot = await send('Page.captureScreenshot', {format: 'png'});
        await writeFile(process.env.MF_SMOKE_RESOURCE_SCREENSHOT, Buffer.from(screenshot.data, 'base64'));
      } : undefined});
    assert.deepEqual(exceptions, []);
    console.log('PASS: targeted role resource workflow; production frontend artifacts and isolated API fixtures');
  } else {
  await poll(() => evaluate(`!![...document.querySelectorAll('.ant-menu-item')].find(e => e.textContent.trim() === ${JSON.stringify(pages[0].label)})`), 'restored tab navigation ready');
  assert.equal(apiRequests.some(r => r.url.startsWith('/common/platform/packages')), false, 'restoring tab labels must not fetch unvisited pages');
  await clickMenu(pages[0].label);
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .sp-loading-progress')`), 'delayed route progress');
  await evaluate(`window.__smokeProgress = document.querySelector('.nb-tab-panel-active .sp-loading-progress'); true`);
  await poll(() => apiRequests.some(r => r.url.includes('/messages/mapping') && r.url.includes('packages')), 'schema/namespace loading phase');
  assert.equal(await evaluate(`window.__smokeProgress === document.querySelector('.nb-tab-panel-active .sp-loading-progress')`), true, 'MF and schema share the same indicator without remounting');
  const frameBefore = await evaluate(`(() => {const r=document.querySelector('.nb-tab-panel-active').getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height};})()`);
  assert.equal(await evaluate(`!!document.querySelector('.nb-tab-panel-active .ant-skeleton')`), false);
  await poll(() => evaluate(`document.querySelector('.ant-table-tbody')?.textContent.includes('smoke-common')`), 'package table before dictionary completion');
  assert.ok(heldDictionary, 'dictionary request should start without blocking table');
  assert.ok(await evaluate(`document.querySelector('.ant-table-thead')?.textContent.includes('测试名称')`), 'schema labels translated after namespace load');
  const schemaRequest = apiRequests.find(r => r.url === `${pages[0].api}/schema`);
  const namespaceRequest = apiRequests.find(r => r.url.includes('/messages/mapping') && r.url.includes('packages'));
  assert.ok(namespaceRequest && schemaRequest.start < namespaceRequest.end, 'schema and namespaces must overlap');
  console.log('PASS: Package table rendered with dictionary still pending; schema/i18n requests overlap');
  assert.equal(await evaluate(`!!document.querySelector('.nb-tab-panel-active .sp-loading-progress')`), false, 'progress clears when the table is ready');
  const frameLoaded = await evaluate(`(() => {const r=document.querySelector('.nb-tab-panel-active').getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height};})()`);
  assert.deepEqual(frameLoaded, frameBefore, 'loading and loaded page keep the same frame geometry');
  await evaluate(`[...document.querySelectorAll('button')].find(e => e.textContent.includes('新增')).click()`);
  await poll(() => evaluate(`!!document.querySelector('.ant-drawer-open .sp-loading-progress')`), 'form waiting for dictionary');
  heldDictionary();
  await poll(() => evaluate(`!!document.querySelector('.ant-drawer-open form')`), 'lazy RJSF form');
  assert.ok(await evaluate(`document.querySelector('.ant-drawer-open form').textContent.includes('测试名称')`));
  await fill('.ant-drawer-open #root_name', 'Draft survives locale change');
  await evaluate(`window.spI18n.setLocale('en-US')`);
  await poll(() => evaluate(`window.spI18n.locale === 'en-US' && document.querySelector('.ant-drawer-open form')?.textContent.includes('Smoke name')`), 'English form labels without remount');
  assert.equal(await evaluate(`document.querySelector('.ant-drawer-open #root_name')?.value`), 'Draft survives locale change');
  await evaluate(`window.spI18n.setLocale('zh-CN')`);
  await poll(() => evaluate(`window.spI18n.locale === 'zh-CN' && document.querySelector('.ant-drawer-open form')?.textContent.includes('测试名称')`), 'Chinese form labels restored');
  assert.equal(await evaluate(`document.querySelector('.ant-drawer-open #root_name')?.value`), 'Draft survives locale change');
  console.log('PASS: language changes update open form labels and retain unsaved values without remounting');
  uploadStatus = 413;
  await chooseFile('.ant-drawer-open input[type=file]', 2 * 1024 * 1024, 'text/plain', 'smoke.txt');
  await poll(() => evaluate(`document.querySelector('.ant-drawer-open [role=alert]')?.textContent.includes('服务端上传大小限制')`), 'file upload 413 inline error');
  uploadStatus = 200;
  await chooseFile('.ant-drawer-open input[type=file]', 2 * 1024 * 1024, 'text/plain', 'smoke.txt');
  await poll(() => evaluate(`!!document.querySelector('.ant-drawer-open a[href="/common/object-storage/objects/smoke-upload/content"]')`), 'file upload retry success');
  assert.ok(uploads.every(upload => upload.size > 2 * 1024 * 1024 && upload.contentType.startsWith('multipart/form-data; boundary=')));
  console.log('PASS: 2 MB attachment uses multipart boundary, shows HTTP 413 inline and retries successfully');
  await evaluate(`document.querySelector('.ant-drawer-open .ant-drawer-close').click()`);
  await poll(()=>evaluate(`!!document.querySelector('.sp-simple-table-discard .ant-modal')`), 'real Package unsaved draft confirmation');
  await clickText('.sp-simple-table-discard button', '放弃修改');
  console.log('PASS: Package create drawer loads RJSF and dictionary controls');
  await poll(() => evaluate(`!document.querySelector('.ant-drawer-open') && getComputedStyle(document.body).overflow !== 'hidden'`), 'drawer close transition');
  await smokeAssignment({evaluate, poll, clickReady, clickText, fill, fixture: assignmentFixture, requests: apiRequests, preferences: personalTablePreferences});
  await evaluate(`document.querySelector('.nb-tab-panel-active .ant-pagination-item-2').click()`);
  await poll(() => !!heldPage, 'second page request');
  assert.ok(await evaluate(`document.querySelector('.nb-tab-panel-active .ant-table-tbody')?.textContent.includes('smoke-common')`), 'previous table stays visible');
  assert.equal(await evaluate(`!!document.querySelector('.nb-tab-panel-active .sp-loading-placeholder')`), false);
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .ant-spin-spinning')`), 'local table loading feedback');
  heldPage();
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .ant-table-tbody')?.textContent.includes('smoke-common-page2')`), 'second page replaces old data');
  console.log('PASS: stable page geometry; pagination keeps the table and uses delayed local feedback');
  await smokeOrganization({clickMenu, clickReady, clickText, fill, poll, evaluate, requests: apiRequests});
  await smokeMembers({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: memberFixture, requests: apiRequests});
  await smokeRoleAssignment({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: roleAssignmentFixture, requests: apiRequests});
  await smokeUserAssignment({clickMenu, clickReady, clickText, fill, poll, evaluate, requests: apiRequests, fixture: userAssignmentFixture});
  for (const page of pages.slice(1)) {
    await clickMenu(page.label);
    await poll(() => evaluate(`[...document.querySelectorAll('.ant-table-tbody')].some(e => e.textContent.includes(${JSON.stringify(`smoke-${page.remote}`)}))`), `${page.remote} table`);
    console.log(`PASS: ${page.remote}/${page.expose} table renders`);
    if (page.remote === 'auditing' && rejectDictionary) {
      await evaluate(`[...document.querySelectorAll('button')].find(e => e.offsetParent && e.textContent.includes('新增')).click()`);
      await poll(() => evaluate(`!!document.querySelector('.ant-drawer-open .ant-alert-error')`), 'dictionary error in drawer only');
      assert.equal(await evaluate(`!!document.querySelector('.ant-drawer-open form')`), false);
      rejectDictionary = false;
      await evaluate(`document.querySelector('.ant-drawer-open .ant-alert-error button').click()`);
      await poll(() => evaluate(`!!document.querySelector('.ant-drawer-open form')`), 'dictionary retry recovers form');
      await evaluate(`document.querySelector('.ant-drawer-open .ant-drawer-close').click()`);
      console.log('PASS: dictionary failure leaves table visible, blocks incomplete form and retries successfully');
    }
  }
  const exposes = [];
  const requiredProviders = new Set();
  for (const page of pages) {
    const manifest = await (await fetch(`${origin}/${page.remote}/mf/mf-manifest.json`)).json();
    exposes.push(...manifest.exposes.map(expose => `${page.remote}/${expose.name}`));
    manifest.shared.forEach(shared => requiredProviders.add(shared.name));
  }
  const loaded = await evaluate(`(async () => {
    const host = __FEDERATION__.__INSTANCES__.find(instance => instance.name === 'host');
    const result = [];
    for (const spec of ${JSON.stringify(exposes)}) {
      const mod = await host.loadRemote(spec);
      if (!mod?.default) throw new Error('Missing default export: ' + spec);
      result.push(spec);
    }
    return result;
  })()`);
  assert.equal(loaded.length, exposes.length);
  const missingProviders = await evaluate(`(() => {
    const host = __FEDERATION__.__INSTANCES__.find(instance => instance.name === 'host');
    const scope = host.shareScopeMap.default;
    return ${JSON.stringify([...requiredProviders])}.filter(name =>
      !Object.values(scope[name] ?? {}).some(provider => provider.from === 'host' && typeof provider.get === 'function'));
  })()`);
  assert.deepEqual(missingProviders, [], 'remote dependencies must have actual host provider getters');
  await evaluate(`location.hash = '#/profile'`);
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .profile-navigation')`), 'profile navigation');
  assert.equal(await evaluate(`document.querySelectorAll('.nb-tab-panel-active .profile-nav-links button').length`), 4);
  const profileRects = await evaluate(`(() => {const nav=document.querySelector('.nb-tab-panel-active .profile-navigation')?.getBoundingClientRect();
    const main=document.querySelector('.nb-tab-panel-active .profile-main')?.getBoundingClientRect();
    return {navWidth: nav?.width, navHeight: nav?.height, navTop: nav?.top, mainWidth: main?.width,
      mainHeight: main?.height, mainTop: main?.top};})()`);
  assert.equal(!!profileRects.navWidth && (Math.abs(profileRects.navHeight-profileRects.mainHeight) < 2
    || (Math.abs(profileRects.navWidth-profileRects.mainWidth) < 2 && profileRects.navTop < profileRects.mainTop)), true,
    `profile navigation fills the left column alongside the single right content panel: ${JSON.stringify(profileRects)}`);
  assert.equal(await evaluate(`document.querySelectorAll('.nb-tab-panel-active .profile-main .ant-card').length`), 0,
    'profile content uses grouped sections inside one panel');
  assert.equal(await evaluate(`document.querySelector('.nb-tab-panel-active .profile-detail-actions button')?.disabled`), true,
    'detail save is disabled before editing');
  assert.equal(await evaluate(`(() => {const page=document.querySelector('.nb-tab-panel-active .profile-page');
    const nav=page.querySelector('.profile-navigation');const content=page.querySelector('.profile-main');
    const navTop=nav.getBoundingClientRect().top;content.scrollTop=180;
    const contained=content.scrollTop>0 && page.scrollTop===0 && nav.scrollTop===0
      && Math.abs(nav.getBoundingClientRect().top-navTop)<1;
    content.scrollTop=0;return contained;})()`), true,
    'profile details scroll inside the right card while the page and navigation stay fixed');
  if (process.env.MF_SMOKE_PROFILE_SCREENSHOT) {
    const viewport = await evaluate(`({width: innerWidth, height: innerHeight})`);
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    const screenshot = await send('Page.captureScreenshot', {format: 'png', captureBeyondViewport: false});
    await writeFile(process.env.MF_SMOKE_PROFILE_SCREENSHOT, Buffer.from(screenshot.data, 'base64'));
    await send('Emulation.setDeviceMetricsOverride', {...viewport, deviceScaleFactor: 1, mobile: false});
  }
  await clickReady('.nb-tab-panel-active .profile-nav-links button:nth-child(2)');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .profile-permission-grid')?.textContent.includes('原生权限页面')`), 'effective access');
  assert.equal(await evaluate(`document.querySelector('.nb-tab-panel-active .profile-permission-grid')?.textContent.includes('platform.access')`), false);
  assert.equal(await evaluate(`document.querySelector('.nb-tab-panel-active .profile-permission-directory')?.textContent.includes('原生权限目录')`), true);
  await clickReady('.nb-tab-panel-active .profile-permission-item');
  await poll(() => evaluate(`document.querySelector('.profile-access-detail')?.textContent.includes('编辑权限')`), 'page access detail');
  assert.equal(await evaluate(`document.querySelector('.profile-access-detail')?.textContent.includes('规则能力暂未开放')`), true);
  assert.equal(await evaluate(`/profile-(page|second|edit)|i18n:/.test(document.querySelector('.profile-access-detail')?.textContent ?? '')`), false);
  await clickReady('.ant-drawer-close');
  await clickReady('.nb-tab-panel-active .profile-nav-links button:nth-child(3)');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .profile-workspaces-group')?.textContent.includes('平台')`), 'organization section');
  await clickReady('.nb-tab-panel-active .profile-nav-links button:nth-child(4)');
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .profile-password-form')`), 'security section');
  await poll(() => evaluate(`!document.querySelector('.nb-tab-panel-active .profile-security-row .ant-btn')?.disabled`), 'security account links');
  assert.equal(await evaluate(`document.querySelector('.nb-tab-panel-active .profile-security-row .ant-btn')?.getAttribute('href')?.endsWith('/account/2fa')`), true);
  assert.equal(await evaluate(`document.querySelectorAll('.nb-tab-panel-active .profile-security-row .ant-btn')[1]?.getAttribute('href')?.endsWith('/account/external-identities')`), true);
  await clickReady('.nb-tab-panel-active .profile-nav-links button:nth-child(1)');
  const imageInput = '.nb-tab-panel-active .oss-image-upload input[type=file]';
  uploadStatus = 413;
  await chooseFile(imageInput, 2 * 1024 * 1024, 'image/png', 'avatar.png');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .oss-image-upload [role=alert]')?.textContent.includes('服务端上传大小限制')`), 'avatar upload error visible');
  assert.equal(profileSaves.length, 0, 'failed upload must not save profile');
  uploadStatus = 200;
  invalidUpload = true;
  await chooseFile(imageInput, 1024, 'image/png', 'avatar.png');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .oss-image-upload [role=alert]')?.textContent.includes('响应无效')`), 'invalid upload response visible');
  invalidUpload = false;
  saveStatus = 500;
  await chooseFile(imageInput, 2 * 1024 * 1024, 'image/png', 'avatar.png');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .oss-image-upload [role=alert]')?.textContent.includes('头像资料保存失败')`), 'avatar persistence error visible');
  assert.equal(await evaluate(`[...document.querySelectorAll('.ant-message-success')].some(e => e.textContent.includes('图片上传成功') || e.textContent.includes('头像已更新'))`), false, 'no premature upload success when profile save fails');
  saveStatus = 200;
  await chooseFile(imageInput, 2 * 1024 * 1024, 'image/png', 'avatar.png');
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .oss-image-upload img[src="/common/object-storage/images/smoke-upload"]')`), 'avatar retry updates image');
  assert.equal(profileSaves.at(-1).picture, '/common/object-storage/images/smoke-upload');
  const uploadedBefore = uploads.length;
  await chooseFile(imageInput, 6 * 1024 * 1024, 'image/png', 'oversized.png');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .oss-image-upload [role=alert]')?.textContent.includes('5 MB')`), 'client size error visible');
  assert.equal(uploads.length, uploadedBefore, 'client rejects oversized image before sending');
  await fill('.nb-tab-panel-active .profile-detail-form input#root_nickname', 'Smoke user edited');
  await poll(() => evaluate(`!document.querySelector('.nb-tab-panel-active .profile-detail-actions button')?.disabled`),
    'detail save enables after editing');
  await clickReady('.nb-tab-panel-active .profile-detail-actions button');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .profile-detail-actions button')?.disabled`),
    'detail save disables after successful save');
  assert.equal(profileSaves.at(-1).nickname, 'Smoke user edited');
  console.log('PASS: avatar upload/save failures visible; invalid response rejected; retry saves profile before success; 5 MB validation retained');
  await clickMenu(pages[0].label);
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .ant-table-tbody')?.textContent.includes('smoke-common-page2')`), 'pagination survives tab switch');
  await poll(() => evaluate(`[...document.querySelectorAll('.nb-tab-panel-active button')].some(e => e.textContent.includes('新增') && e.disabled)`), 'resumed stale table revalidates');
  heldPage();
  await poll(() => evaluate(`[...document.querySelectorAll('.nb-tab-panel-active button')].some(e => e.textContent.includes('新增') && !e.disabled)`), 'resume revalidation finishes');
  await evaluate(`window.dispatchEvent(new Event('offline'))`);
  const reconnectStart = apiRequests.length;
  await evaluate(`window.dispatchEvent(new Event('online'))`);
  await poll(() => apiRequests.slice(reconnectStart).some(r => r.url.startsWith('/common/platform/packages?')), 'active query refresh after reconnect');
  assert.equal(apiRequests.slice(reconnectStart).some(r => pages.slice(1).some(p => r.url.startsWith(p.api + '?'))), false, 'inactive tables do not refresh on reconnect');
  // The earlier pagination fixture holds page 2 requests; finish this revalidation.
  heldPage();
  await poll(() => evaluate(`[...document.querySelectorAll('.nb-tab-panel-active button')].some(e => e.textContent.includes('新增') && !e.disabled)`), 'active table finishes revalidation');
  await evaluate(`[...document.querySelectorAll('.nb-tab-panel-active button')].find(e => e.textContent.includes('新增')).click()`);
  await poll(() => evaluate(`!!document.querySelector('.ant-drawer-open form input')`), 'draft form');
  await evaluate(`(() => {
    const input = document.querySelector('.ant-drawer-open form input');
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, 'keep-my-draft');
    input.dispatchEvent(new Event('input', {bubbles: true}));
  })()`);
  await evaluate(`document.querySelector('.ant-tabs-tab-active .ant-tabs-tab-remove').click()`);
  await poll(() => evaluate(`!!document.querySelector('.ant-modal-confirm')`), 'unsaved tab close confirmation');
  await evaluate(`document.querySelector('.ant-modal-confirm .ant-btn-default').click()`);
  await poll(() => evaluate(`!document.querySelector('.ant-modal-confirm')`), 'cancel closing draft');
  assert.equal(await evaluate(`document.querySelector('.ant-drawer-open form input')?.value`), 'keep-my-draft');
  for (const route of cacheTestRoutes) {
    await evaluate(`location.hash = ${JSON.stringify('#' + route.path)}`);
    await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .ant-table-tbody')`), route.path);
  }
  assert.ok(await evaluate(`document.querySelectorAll('.nb-tab-panel').length <= 8`), 'safe LRU pages keep the mounted cache bounded');
  assert.equal(await evaluate(`!!document.querySelector('.ant-drawer-open')`), false, 'inactive editor must not cover the active page');
  await clickMenu(pages[0].label);
  await poll(() => evaluate(`!!document.querySelector('.ant-drawer-open form input')`), 'draft drawer restored');
  assert.equal(await evaluate(`document.querySelector('.ant-drawer-open form input')?.value`), 'keep-my-draft', 'draft survives cache pressure');
  await evaluate(`document.querySelector('.ant-drawer-open .ant-drawer-close').click()`);
  console.log('PASS: inactive queries pause; pagination survives switching; 10 additional menus respect cache budget without evicting the draft');
  await smokeOrganizationSelection({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: organizationSelectionFixture});
  await evaluate(`location.hash = '#/system/object-storage'`);
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active')?.textContent.includes('Smoke OSS')`), 'storage provider list');
  await clickReady('.nb-tab-panel-active tr[data-row-key="provider-smoke"] input[type="checkbox"]');
  await evaluate(`[...document.querySelectorAll('.nb-tab-panel-active button')].find(e => e.textContent.includes('测试连接')).click()`);
  await poll(() => evaluate(`[...document.querySelectorAll('.ant-message-error')].some(e => e.textContent.includes('测试连接失败'))`), 'HTTP 200 business failure remains a failure');
  connectionTestSuccess = true;
  await evaluate(`[...document.querySelectorAll('.nb-tab-panel-active button')].find(e => e.textContent.includes('测试连接')).click()`);
  await poll(() => evaluate(`[...document.querySelectorAll('.ant-message-success')].some(e => e.textContent.includes('Bucket'))`), 'explicit connection retry succeeds');
  console.log('PASS: storage connection business failure is visible and can be retried without unhandled promises');
  await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
  await evaluate(`location.hash = '#/smoke/sql'`);
  await poll(() => evaluate(`!!document.querySelector('.dna-sql-editor .cm-content') && document.querySelector('.dna-sql-run')?.disabled===false`), 'SQL console ready from datasource API');
  assert.equal(apiRequests.some(request => request.url.startsWith('/dna/platform/dna/federation/catalogs')), false, 'console never loads retired catalog selector');
  assert.equal(await evaluate(`Math.round(document.querySelector('.ant-layout-sider').getBoundingClientRect().width)`), 240, 'wider default navigation');
  const setSql = async (text, from, to = from) => evaluate(`(() => {
    const view = document.querySelector('.dna-sql-editor .cm-content').cmTile.root.view;
    view.dispatch({changes:{from:0,to:view.state.doc.length,insert:${JSON.stringify(text)}},selection:{anchor:${from},head:${to}}});
    view.focus();
  })()`);
  await setSql('SELECT 1;\nSELECT 42;', 12);
  for (let i = 0; i < 3; i++) {
    await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, modifiers: 2});
    await send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, modifiers: 2});
  }
  await poll(() => evaluate(`document.querySelector('.dna-sql-console-result-table')?.textContent.includes('sql-row-1')`), 'SQL result grid');
  assert.equal(sqlExecutions.length, 1, 'shortcut cannot submit concurrently');
  assert.equal(sqlExecutions[0].sql, 'SELECT 42;', 'only caret statement executes');
  assert.equal(sqlExecutions[0].catalogCode, 'smoke');
  await evaluate(`document.querySelector('.dna-sql-output .ant-pagination-item-2').click()`);
  await poll(() => evaluate(`document.querySelector('.dna-sql-console-result-table')?.textContent.includes('sql-row-51')`), 'SQL result pagination');
  assert.equal(await evaluate(`document.querySelector('.dna-sql-output .sp-data-table-toolbar')?.textContent.includes('仅筛选当前已返回的结果集')`), true, 'bounded SQL result scope is explicit');
  await evaluate(`[...document.querySelectorAll('.dna-sql-console-result-table th')].find(e=>e.textContent.includes('name')).querySelector('.ant-table-filter-trigger').click()`);
  await poll(() => evaluate(`!!document.querySelector('.ant-dropdown:not(.ant-dropdown-hidden) input.ant-input')`), 'SQL local header filter');
  await fill('.ant-dropdown:not(.ant-dropdown-hidden) input.ant-input', 'sql-row-150');
  await clickText('.ant-dropdown:not(.ant-dropdown-hidden) button', '应用');
  await poll(() => evaluate(`document.querySelectorAll('.dna-sql-console-result-table tr[data-row-key]').length===1 && document.querySelector('.dna-sql-console-result-table')?.textContent.includes('sql-row-150')`), 'local filter finds a row outside the previous page');
  await evaluate(`[...document.querySelectorAll('.dna-sql-console-result-table th')].find(e=>e.textContent.includes('name')).querySelector('.ant-table-filter-trigger').click()`);
  await clickText('.ant-dropdown:not(.ant-dropdown-hidden) button', '重置');
  await poll(() => evaluate(`document.querySelectorAll('.dna-sql-console-result-table tr[data-row-key]').length===50`), 'local header reset restores pagination');
  await clickReady('.dna-sql-output button[aria-label="筛选与视图"]');
  await clickText('.sp-query-drawer button', '添加条件');
  await fill('.sp-query-drawer input.ant-input-number-input', '150');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '应用');
  await poll(() => evaluate(`document.querySelectorAll('.dna-sql-console-result-table tr[data-row-key]').length===1 && document.querySelector('.dna-sql-console-result-table')?.textContent.includes('sql-row-150')`), 'typed local advanced condition applied');
  await clickReady('.dna-sql-output button[aria-label="筛选与视图"]');
  await fill('.sp-query-drawer input[placeholder*="视图名称"]', 'SQL 结果视图');
  await clickText('.sp-query-drawer button', '保存当前视图');
  await poll(() => [...personalTablePreferences.values()].some(value => JSON.parse(value).views?.some(view => view.name === 'SQL 结果视图')), 'local view uses scoped server preference API');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '清空高级条件');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '应用');
  await poll(() => evaluate(`document.querySelectorAll('.dna-sql-console-result-table tr[data-row-key]').length===50`), 'advanced local reset restores result');
  await clickReady('.dna-sql-output button[aria-label="筛选与视图"]');
  await clickText('.sp-query-drawer button', 'SQL 结果视图');
  await poll(() => evaluate(`document.querySelectorAll('.dna-sql-console-result-table tr[data-row-key]').length===1`), 'local saved view is actually evaluated');
  await clickReady('.dna-sql-output button[aria-label="筛选与视图"]');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '清空高级条件');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '应用');
  await poll(() => evaluate(`document.querySelectorAll('.dna-sql-console-result-table tr[data-row-key]').length===50`), 'local view clear restores full snapshot');
  for (let i = 0; i < 2; i++) await evaluate(`[...document.querySelectorAll('.dna-sql-console-result-table th')].find(e=>e.textContent.includes('id')).querySelector('.ant-table-column-sorters').click()`);
  await poll(() => evaluate(`document.querySelector('.dna-sql-console-result-table tr[data-row-key] td:not(.ant-table-selection-column)')?.textContent==='150'`), 'local numeric descending sort before pagination');
  assert.equal(sqlExecutions.length, 1, 'result filters and saved views never re-execute SQL');
  console.log('PASS: bounded result scope, local typed header/advanced filters before pagination, numeric sort, saved views and reset without SQL reexecution');
  await setSql('DELETE FROM should_not_run;', 0);
  await evaluate(`[...document.querySelectorAll('.dna-sql-output button')].find(e=>e.textContent==='CSV').click()`);
  assert.equal(sqlExecutions.length, 1);
  assert.equal(sqlExports, 0, 'export uses displayed snapshot without executing edited SQL');
  await setSql('SELECT 9; SELECT 8;', 0, 8);
  sqlFailure = true;
  await evaluate(`document.querySelector('.dna-sql-run').click()`);
  await poll(() => evaluate(`document.querySelector('.dna-sql-output .ant-alert-error')?.textContent.includes('permission denied')`), 'persistent SQL error');
  assert.equal(await evaluate(`!!document.querySelector('.dna-sql-console-result-table')`), false, 'failure cannot show stale success rows');
  assert.equal(sqlExecutions[1].sql, 'SELECT 9');
  sqlFailure = false;
  await evaluate(`document.querySelector('.dna-sql-run').click()`);
  await poll(() => evaluate(`document.querySelector('.dna-sql-console-result-table')?.textContent.includes('sql-row-1')`), 'SQL retry and pagination reset');
  await evaluate(`document.querySelector('.dna-sql-tree .ant-tree-switcher').click()`);
  await poll(() => evaluate(`document.querySelector('.dna-sql-explorer .ant-alert-error')?.textContent.includes('metadata failed')`), 'metadata failure visible');
  await evaluate(`document.querySelector('.dna-sql-explorer .ant-alert-error button').click()`);
  await poll(() => evaluate(`document.querySelector('.dna-sql-tree')?.textContent.includes('demo.table')`), 'failed tree node retries');
  assert.equal(sqlTreeCalls, 2);
  await evaluate(`document.querySelector('.dna-sql-tree .ant-tree-treenode:last-child .ant-tree-node-content-wrapper').dispatchEvent(new MouseEvent('dblclick',{bubbles:true}))`);
  assert.ok(await evaluate(`document.querySelector('.dna-sql-editor .cm-content').textContent.includes('"demo.table"')`), 'metadata insertion quotes identifiers');
  await poll(() => evaluate(`(() => {
    const outputElement=document.querySelector('.dna-sql-output');
    const pagerElement=document.querySelector('.dna-sql-output .ant-pagination');
    if (!outputElement || !pagerElement) return false;
    const output=outputElement.getBoundingClientRect();
    const pager=pagerElement.getBoundingClientRect();
    return pager.bottom <= output.bottom + 1;
  })()`), 'SQL pagination stays inside results pane');
  const beforeDrag = await evaluate(`document.querySelector('.dna-sql-editor').getBoundingClientRect().height`);
  const dragPoint = await evaluate(`(() => {const r=document.querySelector('.dna-sql-editor-split .ant-splitter-bar-dragger').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
  await send('Input.dispatchMouseEvent', {type:'mouseMoved', ...dragPoint});
  await send('Input.dispatchMouseEvent', {type:'mousePressed', ...dragPoint, button:'left', clickCount:1});
  await send('Input.dispatchMouseEvent', {type:'mouseMoved', x:dragPoint.x, y:dragPoint.y-50, button:'left', buttons:1});
  await send('Input.dispatchMouseEvent', {type:'mouseReleased', x:dragPoint.x, y:dragPoint.y-50, button:'left', clickCount:1});
  await poll(() => evaluate(`Math.abs(document.querySelector('.dna-sql-editor').getBoundingClientRect().height - ${beforeDrag}) > 30`), 'editor/result divider resizes');
  if (process.env.MF_SMOKE_SQL_SCREENSHOT) {
    const screenshot = await send('Page.captureScreenshot', {format: 'png'});
    await writeFile(process.env.MF_SMOKE_SQL_SCREENSHOT, Buffer.from(screenshot.data, 'base64'));
  }
  const geometry = await evaluate(`(() => {const q=s=>document.querySelector(s).getBoundingClientRect();const a=q('.dna-sql-workbench'),b=q('.dna-sql-editor'),c=q('.dna-sql-output');return {workspace:a.height,editor:b.height,output:c.height,overflow:document.querySelector('.dna-sql-workbench').scrollWidth>a.width+2};})()`);
  assert.ok(geometry.editor > 100 && geometry.output > 120 && !geometry.overflow, JSON.stringify(geometry));
  await evaluate(`document.querySelector('.nb-tabs-bar .ant-tabs-tab-active .ant-tabs-tab-remove').click()`);
  await poll(() => evaluate(`!!document.querySelector('.ant-modal-confirm')`), 'SQL draft close confirmation');
  await evaluate(`document.querySelector('.ant-modal-confirm .ant-btn-default').click()`);
  await poll(() => evaluate(`!document.querySelector('.ant-modal-confirm')`), 'cancel SQL draft close');
  await evaluate(`location.hash = '#/smoke/dna'`);
  await poll(() => evaluate(`!document.querySelector('.nb-tab-panel-active .dna-sql-workbench')`), 'leave SQL console');
  await evaluate(`location.hash = '#/smoke/sql'`);
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .cm-content')?.textContent.includes('"demo.table"')`), 'SQL draft survives menu switch');
  console.log('PASS: SQL execution selection/lock, error/retry, tree retry, quoted insertion, snapshot export, pagination, layout and draft retention');

  await evaluate(`document.querySelector('.nb-tabs-bar .ant-tabs-tab-active .ant-tabs-tab-remove').click()`);
  await poll(() => evaluate(`!!document.querySelector('.ant-modal-confirm')`), 'discard SQL draft before reload');
  await evaluate(`document.querySelector('.ant-modal-confirm .ant-btn-primary').click()`);
  await poll(() => evaluate(`!document.querySelector('.ant-modal-confirm') && !document.querySelector('.dna-sql-editor')`), 'SQL draft explicitly discarded');
  const measurements = await evaluate(`window.spPerformance.snapshot()`);
  await evaluate(`location.hash = '#/dashboard'`);
  await poll(() => evaluate(`!!document.querySelector('.sp-home') && [...document.querySelectorAll('.sp-home button')].some(e => e.textContent.includes('自定义首页') && !e.disabled)`), 'personal home ready');
  assert.ok(await evaluate(`document.title.startsWith('首页')`), 'dashboard is renamed to home');
  await poll(() => evaluate(`document.querySelector('.sp-home')?.textContent.includes('首页通知测试')`), 'home notifications');
  assert.equal(activeStreams, 1, 'home and notification bell share one stream');
  const homeViewport = await evaluate(`({width: innerWidth, height: innerHeight})`);
  await send('Emulation.setDeviceMetricsOverride', {width: 2560, height: 900, deviceScaleFactor: 1, mobile: false});
  assert.equal(await evaluate(`(() => {const panel=document.querySelector('.nb-tab-panel-active').getBoundingClientRect();
    const home=document.querySelector('.sp-home').getBoundingClientRect();
    return Math.abs(panel.width-home.width)<2 && Math.abs(panel.left-home.left)<2;})()`), true,
    'home occupies the full tab panel width on a wide screen');
  await send('Emulation.setDeviceMetricsOverride', {...homeViewport, deviceScaleFactor: 1, mobile: false});
  const shellPosition = await evaluate(`JSON.stringify(['.nb-header','.ant-layout-sider','.nb-tabs-bar'].map(s=>{const r=document.querySelector(s).getBoundingClientRect();return [r.x,r.y,r.width,r.height]}))`);
  const homePoint = await evaluate(`(() => {const e=document.querySelector('.sp-home'),r=e.getBoundingClientRect();return {x:r.x+80,y:r.y+80,overflow:e.scrollHeight>e.clientHeight};})()`);
  assert.equal(homePoint.overflow, true, 'home fixture exceeds the available panel height');
  await send('Input.dispatchMouseEvent', {type: 'mouseMoved', x: homePoint.x, y: homePoint.y});
  await send('Input.dispatchMouseEvent', {type: 'mouseWheel', x: homePoint.x, y: homePoint.y, deltaX: 0, deltaY: 550});
  await poll(() => evaluate(`document.querySelector('.sp-home').scrollTop > 100`), 'home content scrolls with a real mouse wheel');
  await evaluate(`document.querySelector('.sp-home').focus()`);
  await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'End', code: 'End', windowsVirtualKeyCode: 35});
  await send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'End', code: 'End', windowsVirtualKeyCode: 35});
  await poll(() => evaluate(`(() => {const e=document.querySelector('.sp-home');return e.scrollTop+e.clientHeight>=e.scrollHeight-2;})()`), 'keyboard reaches the bottom of home');
  assert.equal(await evaluate(`(() => {const e=document.querySelector('.sp-home'),r=e.getBoundingClientRect(),last=[...e.querySelectorAll('.sp-home-panel')].map(n=>n.getBoundingClientRect()).sort((a,b)=>b.bottom-a.bottom)[0];return last.bottom<=r.bottom+1;})()`), true, 'last quick action is reachable');
  assert.equal(await evaluate(`document.scrollingElement.scrollTop`), 0, 'home does not scroll the document');
  assert.equal(await evaluate(`JSON.stringify(['.nb-header','.ant-layout-sider','.nb-tabs-bar'].map(s=>{const r=document.querySelector(s).getBoundingClientRect();return [r.x,r.y,r.width,r.height]}))`), shellPosition, 'navigation shell stays fixed');
  const homeScrollTop = await evaluate(`document.querySelector('.sp-home').scrollTop`);
  await evaluate(`location.hash='#/smoke/dna'`);
  await poll(() => evaluate(`!document.querySelector('.nb-tab-panel-active .sp-home')`), 'leave scrolled home');
  await evaluate(`location.hash='#/dashboard'`);
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .sp-home')`), 'return to scrolled home');
  assert.equal(await evaluate(`document.querySelector('.sp-home').scrollTop`), homeScrollTop, 'cached home retains scroll position');
  await send('Emulation.setDeviceMetricsOverride', {width: 900, height: 600, deviceScaleFactor: 1, mobile: false});
  await evaluate(`document.querySelector('.sp-home').scrollTo(0,document.querySelector('.sp-home').scrollHeight)`);
  await poll(() => evaluate(`(() => {const e=document.querySelector('.sp-home'),r=e.getBoundingClientRect(),last=[...e.querySelectorAll('.sp-home-panel')].map(n=>n.getBoundingClientRect()).sort((a,b)=>b.bottom-a.bottom)[0];return e.scrollTop>0 && last.bottom<=r.bottom+1 && e.scrollWidth<=e.clientWidth+1;})()`), 'short viewport can reach the bottom without horizontal overflow');
  await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
  await evaluate(`document.querySelector('.sp-home').scrollTo(0,0)`);
  console.log('PASS: home wheel/keyboard scrolling, reachable bottom, stable shell, cached scroll position and short viewport');
  const calendarMonth = await evaluate(`document.querySelector('.sp-home-calendar-nav strong').textContent`);
  const calendarHeight = await evaluate(`document.querySelector('.sp-home-calendar').getBoundingClientRect().height`);
  assert.equal(await evaluate(`document.querySelectorAll('.sp-home-calendar tbody button').length`), 42);
  assert.equal(await evaluate(`document.querySelectorAll('.sp-home-calendar tbody button[tabindex="0"]').length`), 1, 'calendar has one keyboard entry point');
  await evaluate(`document.querySelector('.sp-home-calendar [aria-label="下个月"]').click()`);
  await poll(() => evaluate(`document.querySelector('.sp-home-calendar-nav strong').textContent !== ${JSON.stringify(calendarMonth)}`), 'calendar next month');
  assert.equal(await evaluate(`document.querySelector('.sp-home-calendar').getBoundingClientRect().height`), calendarHeight, 'month switching keeps sidebar geometry stable');
  await evaluate(`document.querySelector('.sp-home-calendar [aria-label="上个月"]').click()`);
  await poll(() => evaluate(`document.querySelector('.sp-home-calendar-nav strong').textContent === ${JSON.stringify(calendarMonth)}`), 'calendar previous month');
  const todayDay = await evaluate(`document.querySelector('.sp-home-calendar [aria-current="date"]').dataset.day`);
  await evaluate(`document.querySelector('.sp-home-calendar tbody button:not(.is-other-month):not([aria-current="date"])').click()`);
  await poll(() => evaluate(`document.querySelector('.sp-home-calendar tbody button[aria-pressed="true"]')?.dataset.day !== ${JSON.stringify(todayDay)}`), 'calendar selects a date');
  await evaluate(`document.querySelector('.sp-home-calendar tbody button[aria-pressed="true"]').focus()`);
  const beforeArrow = await evaluate(`document.activeElement.dataset.day`);
  await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39});
  await send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39});
  await poll(() => evaluate(`document.activeElement?.dataset.day && document.activeElement.dataset.day !== ${JSON.stringify(beforeArrow)} && document.activeElement.getAttribute('aria-pressed') === 'true'`), 'calendar keyboard date selection');
  await evaluate(`[...document.querySelectorAll('.sp-home-calendar button')].find(e=>e.textContent.includes('回到今天')).click()`);
  await poll(() => evaluate(`document.querySelector('.sp-home-calendar tbody button[aria-pressed="true"]')?.dataset.day === ${JSON.stringify(todayDay)}`), 'calendar returns to today');
  console.log('PASS: home calendar month navigation, fixed six-week layout, date selection, roving keyboard focus and today shortcut');
  await evaluate(`[...document.querySelectorAll('.sp-home button')].find(e=>e.textContent.includes('自定义首页')).click()`);
  await poll(() => evaluate(`!!document.querySelector('.sp-home-editor form.rjsf')`), 'home settings editor');
  await evaluate(`[...document.querySelectorAll('.sp-home-editor button')].find(e=>e.textContent.includes('添加分组')).click()`);
  await poll(() => evaluate(`!!document.querySelector('.sp-home-editor input#root_groups_0_name')`), 'new standard home group field');
  await fill('.sp-home-editor input#root_groups_0_name', '我的工作');
  await evaluate(`document.querySelector('.sp-home-edit-group .ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
  await poll(() => evaluate(`[...document.querySelectorAll('.ant-select-item-option')].some(e=>e.textContent.includes('套餐包管理'))`), 'authorized app selector');
  await evaluate(`[...document.querySelectorAll('.ant-select-item-option')].find(e=>e.textContent.includes('套餐包管理')).click();document.querySelector('.sp-home-editor input#root_groups_0_name').focus()`);
  homeSaveFailure = true;
  await evaluate(`[...document.querySelectorAll('.ant-modal-footer button')].find(e=>e.textContent.replaceAll(' ','')==='保存').click()`);
  await poll(() => evaluate(`document.querySelector('.sp-home-editor .ant-alert-error')?.textContent.includes('首页保存失败')`), 'home save error stays visible');
  assert.equal(await evaluate(`document.querySelector('.sp-home-editor input#root_groups_0_name').value`), '我的工作', 'failed save retains draft');
  assert.equal(homePreferences.size, 0);
  homeSaveFailure = false;
  await evaluate(`[...document.querySelectorAll('.ant-modal-footer button')].find(e=>e.textContent.replaceAll(' ','')==='保存').click()`);
  await poll(() => evaluate(`!!document.querySelector('.sp-home-app') && !document.querySelector('.sp-home-editor')`), 'saved favorites render');
  assert.equal(homeSaveCount, 2, 'explicit save and retry each issue exactly one write');
  assert.deepEqual(JSON.parse(homePreferences.get('home.v1.__platform__')).groups[0].paths, ['/smoke/common']);
  assert.equal(await evaluate(`document.querySelector('.sp-home-app').getAttribute('href')`), '#/smoke/common', 'favorite supports hash routing and opening a new tab');
  await evaluate(`document.querySelector('.sp-home .sp-notification-item').click()`);
  await poll(() => notificationRead, 'notification marked read');
  await poll(() => evaluate(`!!document.querySelector('.ant-modal .sp-notification-detail')`), 'notification detail');
  await evaluate(`[...document.querySelectorAll('.ant-modal-footer button')].find(e=>e.textContent.replaceAll(' ','')==='关闭').click()`);
  await poll(() => evaluate(`!document.querySelector('.sp-notification-detail')`), 'notification detail closes');
  if (process.env.MF_SMOKE_HOME_SCREENSHOT) {
    const screenshot = await send('Page.captureScreenshot', {format: 'png'});
    await writeFile(process.env.MF_SMOKE_HOME_SCREENSHOT, Buffer.from(screenshot.data, 'base64'));
  }
  memberFixture.reorderSchema();
  organizationSelectionFixture.readonly();
  await reload();
  await poll(() => evaluate(`document.querySelector('.sp-home-group h3')?.textContent === '我的工作'`), 'home preferences survive reload');
  await poll(() => activeStreams === 1, 'reload keeps one live notification stream');
  await poll(() => evaluate(`document.querySelectorAll('.sp-home-app').length === 1`), 'authorized favorites restore after route bootstrap');
  console.log('PASS: personal home, scoped server preferences, grouped app selection, failed save/retry, persistence after reload, shared notification feed and read state');

  // New personal-work pages use real built MF modules against isolated server state.
  const previousViewRequests = apiRequests.filter(r => r.url.includes('sp.table.views.')).length;
  await poll(() => evaluate(`document.querySelector('.sp-home-personal')?.textContent.includes('请切换到组织工作空间') && document.querySelector('.sp-home-personal')?.textContent.includes('我的消息测试')`), 'platform home explains tenant workflow scope and preserves personal messages');
  await evaluate(`location.hash='#/my/items?legacy=1'`);
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .sp-personal')?.textContent.includes('首页事项测试')`), 'my items list');
  assert.equal(await evaluate(`(() => {const page=document.querySelector('.nb-tab-panel-active .sp-personal').getBoundingClientRect();
    const tabs=document.querySelector('.nb-tab-panel-active .sp-personal > .ant-tabs').getBoundingClientRect();
    return tabs.top-page.top<4;})()`), true, 'my items tabs start at the top of the content area');
  assert.deepEqual(await evaluate(`[...document.querySelector('.ant-layout-sider .ant-menu-root').children].slice(0,2).map(e=>(e.querySelector(':scope > .ant-menu-submenu-title') || e).textContent.trim())`), ['首页', '我的'], 'home then my workspace are the first root menus');
  assert.equal(await evaluate(`!!document.querySelector('.nb-tab-panel-active .sp-table-root') && !!document.querySelector('.nb-tab-panel-active .sp-table-resize-handle')`), true, 'items use platform table and resizable headers');
  assert.equal(await evaluate(`!!document.querySelector('.nb-tab-panel-active .sp-personal-filters, .nb-tab-panel-active .sp-personal .ant-input-search')`), false, 'header filtering replaces the top search bar');
  assert.equal(await evaluate(`document.querySelector('.nb-tab-panel-active .sp-table-toolbar button[title="编辑事项"]').disabled`), true, 'platform single-row edit requires selection');
  await evaluate(`document.querySelector('.nb-tab-panel-active tr[data-row-key] input[type="checkbox"]').click()`);
  await poll(() => evaluate(`!document.querySelector('.nb-tab-panel-active .sp-table-toolbar button[title="编辑事项"]').disabled`), 'selection enables platform edit');
  await evaluate(`document.querySelector('.nb-tab-panel-active tr[data-row-key] input[type="checkbox"]').click()`);
  await evaluate(`document.querySelector('.nb-tab-panel-active [aria-label="表格设置"]').click()`);
  await poll(() => evaluate(`document.querySelectorAll('.sp-table-column-setting-row').length===6`), 'platform item column settings');
  await evaluate(`[...document.querySelectorAll('.sp-table-column-setting-row')].find(e=>e.textContent.includes('分类')).querySelector('input[type="checkbox"]').click()`);
  await clickText('.ant-drawer-open button', '保存');
  await poll(() => evaluate(`![...document.querySelectorAll('.nb-tab-panel-active .ant-table-thead th')].some(e=>e.textContent==='分类')`), 'column visibility setting applied');
  await poll(() => [...personalTablePreferences.keys()].some(key=>key.startsWith('sp.table.cols.') && key.includes('personal.items')), 'item preferences persisted with distinct platform key');
  await evaluate(`document.querySelector('.nb-tab-panel-active [aria-label="表格设置"]').click()`);
  await poll(() => evaluate(`document.querySelectorAll('.sp-table-column-setting-row').length===6`), 'reopen column settings');
  await evaluate(`[...document.querySelectorAll('.sp-table-column-setting-row')].find(e=>e.textContent.includes('分类')).querySelector('input[type="checkbox"]').click()`);
  await clickText('.ant-drawer-open button', '保存');
  await poll(() => evaluate(`!document.querySelector('.ant-drawer-open')`), 'column settings closed');
  personalFixture.records.items.push(...Array.from({length: 25}, (_, i) => ({...personalFixture.records.items[0], id: 'paging-' + i, title: '分页事项 ' + i})));
  await evaluate(`document.querySelector('.nb-tab-panel-active .sp-table-root [aria-label="刷新"]').click()`);
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .ant-pagination-item-2')`), 'platform server pagination appears');
  await evaluate(`[...document.querySelectorAll('.nb-tab-panel-active tr[data-row-key] input[type="checkbox"]')].slice(0,2).forEach(e=>e.click())`);
  assert.equal(await evaluate(`document.querySelector('.nb-tab-panel-active .sp-table-toolbar button[title="编辑事项"]').disabled`), true, 'platform single-row edit rejects multi-selection');
  await evaluate(`[...document.querySelectorAll('.nb-tab-panel-active tr[data-row-key] input[type="checkbox"]:checked')].forEach(e=>e.click())`);
  await evaluate(`document.querySelector('.nb-tab-panel-active .ant-pagination-item-2').click()`);
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .ant-table-tbody')?.textContent.includes('分页事项 24')`), 'platform pagination loads the second server page');
  assert.ok(apiRequests.some(r=>r.url.startsWith('/common/personal/items?') && new URL(r.url, origin).searchParams.get('page') === '1'));
  // A header condition must filter the server dataset, reset page 2 and preserve platform preferences.
  await evaluate(`[...document.querySelectorAll('.nb-tab-panel-active th')].find(e=>e.textContent.includes('标题')).querySelector('.ant-table-filter-trigger').click()`);
  await poll(() => evaluate(`!!document.querySelector('.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown input.ant-input')`), 'platform title filter');
  await fill('.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown input.ant-input', '分页事项 0');
  await clickText('.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown button', '应用');
  await poll(() => evaluate(`document.querySelectorAll('.nb-tab-panel-active .ant-table-tbody tr[data-row-key]').length===1 && document.querySelector('.nb-tab-panel-active .ant-table-tbody')?.textContent.includes('分页事项 0')`), 'header filters whole server dataset and resets page');
  assert.ok(apiRequests.some(r => r.url.startsWith('/common/personal/items?') && new URL(r.url, origin).searchParams.get('page') === '0' &&
    JSON.parse(JSON.parse(new URL(r.url, origin).searchParams.get('filters') || '{}').title || '{}').where?.value === '分页事项 0'));
  // Advanced tools are lazy and failed preference reads must never overwrite saved views.
  assert.equal(apiRequests.filter(r => r.url.includes('sp.table.views.')).length, previousViewRequests, 'no view request on the menu critical path');
  queryViewFailure = true;
  assert.equal(await evaluate(`document.querySelector('.nb-tab-panel-active button[aria-label="筛选与视图"]')?.textContent.trim()`), '', 'query toolbar uses only an accessible icon');
  await clickReady('.nb-tab-panel-active button[aria-label="筛选与视图"]');
  await poll(() => evaluate(`document.querySelector('.sp-query-drawer .ant-alert')?.textContent.includes('视图加载失败')`), 'failed preference read is visible');
  await fill('.sp-query-drawer input[placeholder*="视图名称"]', '标题筛选视图');
  assert.equal(await evaluate(`[...document.querySelectorAll('.sp-query-drawer button')].find(e=>e.textContent.includes('保存当前视图'))?.disabled`), true, 'failed load blocks saving');
  queryViewFailure = false;
  await clickText('.sp-query-drawer .ant-alert button', '重试');
  await poll(() => evaluate(`[...document.querySelectorAll('.sp-query-drawer button')].some(e=>e.textContent.includes('保存当前视图')&&!e.disabled)`), 'view load retry enables explicit save');
  await clickText('.sp-query-drawer button', '保存当前视图');
  await poll(() => [...personalTablePreferences].some(([key, value]) => key.startsWith('sp.table.views.') && JSON.parse(value).views[0]?.name === '标题筛选视图'), 'current view persisted to user preference API');
  await evaluate(`document.querySelector('.sp-query-drawer .ant-drawer-close').click()`);
  await evaluate(`[...document.querySelectorAll('.nb-tab-panel-active th')].find(e=>e.textContent.includes('标题')).querySelector('.ant-table-filter-trigger').click()`);
  await poll(() => evaluate(`document.querySelector('.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown input.ant-input')?.value==='分页事项 0'`), 'reopen retains active filter');
  await clickText('.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown button', '重置');
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .ant-pagination-item-2')`), 'reset header filter restores total count');
  await clickReady('.nb-tab-panel-active button[aria-label="筛选与视图"]');
  await clickText('.sp-query-drawer button', '标题筛选视图');
  await poll(() => evaluate(`document.querySelectorAll('.nb-tab-panel-active .ant-table-tbody tr[data-row-key]').length===1`), 'saved view reapplies server filters');
  await clickReady('.nb-tab-panel-active button[aria-label="清除全部筛选"]');
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .ant-pagination-item-2')`), 'clear filters restores all rows');
  await clickReady('.nb-tab-panel-active button[aria-label="筛选与视图"]');
  await clickText('.sp-query-drawer button', '添加条件');
  await fill('.sp-query-drawer .ant-drawer-body input.ant-input:not([placeholder])', '分页事项 1');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '应用');
  await poll(() => apiRequests.some(r => {
    if (!r.url.startsWith('/common/personal/items?')) return false;
    const f = JSON.parse(new URL(r.url, origin).searchParams.get('filters') || '{}');
    return f.$query && JSON.parse(f.$query).where.children?.[0]?.value === '分页事项 1';
  }), 'advanced AND condition submitted through platform controller');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .ant-table-tbody')?.textContent.includes('分页事项 1')`), 'advanced filter renders matching rows');
  personalFixture.failNextRead = true;
  await clickReady('.nb-tab-panel-active button[aria-label="筛选与视图"]');
  await fill('.sp-query-drawer .ant-drawer-body input.ant-input:not([placeholder])', '分页事项 2');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '应用');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .ant-alert-error')?.textContent.includes('筛选值与字段类型不匹配')`), 'safe query validation message is visible');
  assert.equal(await evaluate(`!!document.querySelector('.nb-tab-panel-active button[aria-label="筛选与视图"]')`), true, 'query editor remains reachable on failure');
  await clickText('.nb-tab-panel-active .ant-alert-error button', '重置筛选与排序');
  await poll(() => evaluate(`!document.querySelector('.nb-tab-panel-active .ant-alert-error') && !!document.querySelector('.nb-tab-panel-active .ant-pagination-item-2')`), 'reset recovers failed query without refreshing page');
  assert.equal(personalFixture.readFailures.length, 1);
  assert.equal(apiRequests.filter(r => r.url === personalFixture.readFailures[0]).length, 1, '400 query failure is not automatically retried');
  console.log('PASS: invalid query keeps editor visible, shows safe cause, skips automatic retries and resets successfully');
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .ant-pagination-item-2')`), 'advanced clear restores count');
  console.log('PASS: structured header/advanced filters, lazy saved views, failed-read protection, retry, server persistence and view restore');
  await evaluate(`[...document.querySelectorAll('.nb-tab-panel-active th')].find(e=>e.textContent.includes('标题')).querySelector('.ant-table-column-sorters').click()`);
  await poll(() => apiRequests.some(r => r.url.startsWith('/common/personal/items?') && new URL(r.url, origin).searchParams.get('sort') === 'title,asc'), 'server ascending title sort');
  await evaluate(`[...document.querySelectorAll('.nb-tab-panel-active th')].find(e=>e.textContent.includes('优先级')).querySelector('.ant-table-column-sorters').click()`);
  await poll(() => apiRequests.some(r => r.url.startsWith('/common/personal/items?') &&
    JSON.stringify(new URL(r.url, origin).searchParams.getAll('sort')) === JSON.stringify(['title,asc', 'priority,asc'])), 'multi-sort preserves explicit priority using repeated Spring sort parameters');
  await evaluate(`[...document.querySelectorAll('.nb-tab-panel-active th')].find(e=>e.textContent.includes('优先级')).querySelector('.ant-table-column-sorters').click()`);
  await poll(() => apiRequests.some(r => r.url.startsWith('/common/personal/items?') &&
    new URL(r.url, origin).searchParams.getAll('sort').includes('priority,desc')), 'secondary descending sort');
  const multiSortReset = apiRequests.length;
  await evaluate(`[...document.querySelectorAll('.nb-tab-panel-active th')].find(e=>e.textContent.includes('优先级')).querySelector('.ant-table-column-sorters').click()`);
  await poll(() => apiRequests.slice(multiSortReset).some(r => r.end && r.url.startsWith('/common/personal/items?') &&
    JSON.stringify(new URL(r.url, origin).searchParams.getAll('sort')) === JSON.stringify(['title,asc'])), 'clearing one sort retains the other');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active tr[data-row-key] .sp-personal-title')?.textContent==='分页事项 0'`), 'ascending rows rendered');
  await evaluate(`[...document.querySelectorAll('.nb-tab-panel-active th')].find(e=>e.textContent.includes('标题')).querySelector('.ant-table-column-sorters').click()`);
  await poll(() => apiRequests.some(r => r.url.startsWith('/common/personal/items?') && new URL(r.url, origin).searchParams.get('sort') === 'title,desc'), 'server descending title sort');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active tr[data-row-key] .sp-personal-title')?.textContent==='首页事项测试'`), 'descending rows rendered');
  const personalSortResetStart = apiRequests.length;
  await evaluate(`[...document.querySelectorAll('.nb-tab-panel-active th')].find(e=>e.textContent.includes('标题')).querySelector('.ant-table-column-sorters').click()`);
  await poll(() => apiRequests.slice(personalSortResetStart).some(request => request.end && request.url.startsWith('/common/personal/items?')
    && !new URL(request.url, origin).searchParams.get('sort')), 'sort reset revalidation finishes');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .ant-table-tbody')?.textContent.includes('首页事项测试')`), 'return to personal first page');
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .sp-table-root [aria-label="刷新"]:not(:disabled):not(.ant-btn-loading)')`), 'cached page revalidation finishes before another refresh');
  // Large flat pages use the platform's virtual body; ordinary 20-row pages retain native layout.
  personalFixture.records.items.push(...Array.from({length: 120}, (_, i) => ({...personalFixture.records.items[0], id: 'virtual-' + i, title: '虚拟事项 ' + i})));
  await clickReady('.nb-tab-panel-active .sp-table-root [aria-label="刷新"]');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .ant-pagination-total-text')?.textContent.includes('146')`), 'large-page fixture count');
  await evaluate(`document.querySelector('.nb-tab-panel-active .ant-pagination-options-size-changer input').focus()`);
  await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40});
  await send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40});
  await poll(() => evaluate(`[...document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')].some(e=>e.textContent.trim().startsWith('100'))`), '100 row page option');
  await evaluate(`[...document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')].find(e=>e.textContent.trim().startsWith('100')).click()`);
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .ant-table-tbody-virtual')`), 'platform virtual body enabled for 100 rows');
  assert.ok(await evaluate(`document.querySelectorAll('.nb-tab-panel-active [data-row-key]').length < 100`), 'virtual body does not mount all 100 rows');
  await evaluate(`document.querySelector('.nb-tab-panel-active .ant-pagination-options-size-changer input').focus()`);
  await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40});
  await send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40});
  await poll(() => evaluate(`[...document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')].some(e=>e.textContent.trim().startsWith('20'))`), '20 row page option');
  await evaluate(`[...document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')].find(e=>e.textContent.trim().startsWith('20')).click()`);
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .ant-table-tbody') && !document.querySelector('.nb-tab-panel-active .ant-table-tbody-virtual')`), 'normal body restored for small pages');
  personalFixture.records.items.splice(26, 120);
  console.log('PASS: bounded DOM virtualization for large flat pages; native body restored for normal pages');
  personalFixture.records.items.splice(1, 25);
  await clickReady('.nb-tab-panel-active .sp-table-root [aria-label="刷新"]');
  await poll(() => evaluate(`!document.querySelector('.nb-tab-panel-active .ant-pagination-item-2')`), 'pagination returns to one page after data refresh');
  for (const type of ['cols', 'widths', 'display']) {
    assert.equal(apiRequests.filter(r => r.method === 'GET' && r.url.startsWith('/common/users/preferences?') &&
      new URL(r.url, origin).searchParams.get('key') === 'sp.table.' + type + './common/personal/items:personal.items').length, 1,
      'query refresh/pagination must not reload ' + type + ' preferences');
  }
  await clickReady('.nb-tab-panel-active .sp-table-toolbar button[title="新建事项"]');
  await poll(() => evaluate(`!!document.querySelector('.sp-simple-table-drawer.ant-drawer-open input#root_title')`), 'item editor');
  await fill('.sp-simple-table-drawer.ant-drawer-open input#root_title', '未保存草稿');
  await clickReady('.sp-simple-table-drawer.ant-drawer-open .ant-drawer-close');
  await poll(() => evaluate(`[...document.querySelectorAll('.ant-modal')].some(e=>e.textContent.includes('放弃未保存的修改'))`), 'platform drawer confirms unsaved changes');
  await clickText('.ant-modal button', '继续编辑');
  assert.equal(await evaluate(`document.querySelector('.sp-simple-table-drawer.ant-drawer-open input#root_title').value`), '未保存草稿', 'cancel close retains schema form draft');
  await fill('.sp-simple-table-drawer.ant-drawer-open input#root_title', '浏览器新增事项');
  await fill('.sp-simple-table-drawer.ant-drawer-open textarea#root_description', '保留事项说明');
  await evaluate(`document.querySelector('.sp-simple-table-drawer.ant-drawer-open .rjsf-array-item-add').click()`);
  await fill('.sp-simple-table-drawer.ant-drawer-open input#root_checklist_0_title', '子任务');
  await evaluate(`document.querySelector('.sp-simple-table-drawer.ant-drawer-open input#root_checklist_0_done').click()`);
  await clickReady('.sp-simple-table-drawer.ant-drawer-open button[type="submit"]');
  await poll(() => evaluate(`document.querySelector('.ant-drawer-open h2')?.textContent==='浏览器新增事项'`), 'saved item details');
  assert.equal(personalFixture.records.items.find(r=>r.title==='浏览器新增事项').checklist[0].done, true);
  await fill('.ant-drawer-open textarea', '浏览器进展备注');
  await clickText('.ant-drawer-open button', '添加备注');
  await poll(() => evaluate(`document.querySelector('.sp-personal-history')?.textContent.includes('浏览器进展备注')`), 'persisted note history');
  await clickReady('.ant-drawer-open button[title="编辑事项"]');
  await poll(() => evaluate(`!!document.querySelector('.sp-simple-table-drawer.ant-drawer-open input#root_title')`), 'edit existing item');
  await fill('.sp-simple-table-drawer.ant-drawer-open input#root_title', '浏览器修改事项');
  personalFixture.failNextWrite = true;
  await clickReady('.sp-simple-table-drawer.ant-drawer-open button[type="submit"]');
  await poll(() => evaluate(`document.querySelector('.sp-simple-table-drawer.ant-drawer-open .ant-alert-error')?.textContent.includes('事项保存冲突')`), 'failed item save is visible');
  assert.equal(await evaluate(`document.querySelector('.sp-simple-table-drawer.ant-drawer-open input#root_title').value`), '浏览器修改事项', 'conflict retains item draft');
  await clickReady('.sp-simple-table-drawer.ant-drawer-open button[type="submit"]');
  await poll(() => evaluate(`document.querySelector('.ant-drawer-open h2')?.textContent==='浏览器修改事项'`), 'updated item details');
  await clickText('.ant-drawer-open button', '完成');
  await poll(() => evaluate(`!!document.querySelector('.ant-modal-confirm .ant-btn-primary')`), 'complete confirmation');
  await evaluate(`document.querySelector('.ant-modal-confirm .ant-btn-primary').click()`);
  await poll(() => evaluate(`document.querySelector('.ant-drawer-open .ant-tag')?.textContent==='已完成'`), 'item completed');
  await clickReady('.ant-drawer-open button[title="移至回收站"]');
  await poll(() => evaluate(`!!document.querySelector('.ant-modal-confirm .ant-btn-primary')`), 'trash confirmation');
  await evaluate(`document.querySelector('.ant-modal-confirm .ant-btn-primary').click()`);
  await poll(() => evaluate(`!document.querySelector('.ant-drawer-open')`), 'trashed detail closed');
  await clickText('.nb-tab-panel-active .ant-tabs-tab-btn', '回收站');
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .ant-table')?.textContent.includes('浏览器修改事项')`), 'trash lists deleted item');
  await clickText('.nb-tab-panel-active .ant-table .sp-personal-title', '浏览器修改事项');
  await poll(() => evaluate(`!!document.querySelector('.ant-drawer-open')`), 'trash details');
  await clickText('.ant-drawer-open button', '恢复');
  await poll(() => evaluate(`!!document.querySelector('.ant-modal-confirm .ant-btn-primary')`), 'restore confirmation');
  await evaluate(`document.querySelector('.ant-modal-confirm .ant-btn-primary').click()`);
  await poll(() => evaluate(`!document.querySelector('.ant-drawer-open')`), 'restored detail closed');
  await evaluate(`location.hash='#/my/todos?id=todo-home'`);
  await poll(() => evaluate(`document.querySelector('.ant-drawer-open h2')?.textContent==='工作流待办测试'`), 'workflow todo detail');
  assert.equal(await evaluate(`[...document.querySelectorAll('.ant-drawer-open button')].some(e=>e.textContent==='完成')`), false, 'workflow outcomes cannot be changed manually');
  await evaluate(`document.querySelector('.ant-drawer-open .ant-drawer-close').click()`);
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active')?.textContent.includes('请切换到组织工作空间')`), 'platform workspace explains workflow scope before querying tenant data');
  assert.equal(await evaluate(`!!document.querySelector('.nb-tab-panel-active .sp-personal-filters, .nb-tab-panel-active .sp-personal .ant-input-search')`), false, 'header filtering replaces the top search bar');
  assert.equal(await evaluate(`!!document.querySelector('.nb-tab-panel-active button[title="新建待办"]')`), false, 'manual creation is removed from the primary workflow inbox');
  assert.equal(await evaluate(`!!document.querySelector('.nb-tab-panel-active .ant-table-tbody')`), false, 'platform scope never fabricates a tenant inbox');
  await evaluate(`location.hash='#/my/messages?id=message-home'`);
  await poll(() => evaluate(`document.querySelector('.ant-drawer-open h2')?.textContent==='我的消息测试'`), 'personal message detail');
  assert.equal(await evaluate(`(() => {const page=document.querySelector('.nb-tab-panel-active .sp-personal').getBoundingClientRect();
    const tabs=document.querySelector('.nb-tab-panel-active .sp-personal > .ant-tabs').getBoundingClientRect();
    return tabs.top-page.top<4;})()`), true, 'my messages tabs start at the top of the content area');
  assert.equal(await evaluate(`!!document.querySelector('.nb-tab-panel-active .sp-table-root [aria-label="表格设置"]')`), true, 'messages use platform table');
  assert.equal(await evaluate(`!!document.querySelector('.nb-tab-panel-active .sp-personal-filters, .nb-tab-panel-active .sp-personal .ant-input-search')`), false, 'header filtering replaces the top search bar');
  assert.equal(apiRequests.filter(r => r.url === '/common/personal/items/schema').length, 1, 'platform schema is shared by table and details');
  await clickText('.ant-drawer-open button', '标为已读');
  await poll(() => evaluate(`!!document.querySelector('.ant-modal-confirm .ant-btn-primary')`), 'read confirmation');
  await evaluate(`document.querySelector('.ant-modal-confirm .ant-btn-primary').click()`);
  await poll(() => personalFixture.records.messages[0].readAt, 'message read persisted');
  await poll(() => evaluate(`!!document.querySelector('.ant-drawer-open .ant-drawer-close')`), 'message write finishes before close');
  await evaluate(`document.querySelector('.ant-drawer-open .ant-drawer-close').click();location.hash='#/dashboard'`);
  await poll(() => evaluate(`!document.querySelector('.sp-home-personal')?.textContent.includes('我的消息测试')`), 'home summary invalidated after message read');
  console.log('PASS: historical item compatibility; readonly legacy todo deep link; primary workflow replacement; message read and shared home summaries');

  assert.ok(measurements.some(sample => sample.outcome === 'ready' && sample.durationMs >= 0));
  assert.ok(measurements.length <= 60);
  assert.ok(measurements.every(sample => !('url' in sample) && !('body' in sample)), 'diagnostics do not retain API payloads');
  console.log('PASS: draft close cancellation and inactive-overlay protection; bounded local navigation diagnostics');
  await smokeMemberViewPersistence({clickMenu, clickReady, clickText, poll, evaluate, fixture: memberFixture});
  await smokeReadonlyOrganizationSelection({clickMenu, clickReady, clickText, poll, evaluate});
  await smokeCommonMain({clickMenu, clickReady, clickText, fill, poll, evaluate, reload, fixture: commonMainFixture});
  await smokeWorkflow({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: workflowFixture});
  await smokeRuntime({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: runtimeFixture});
  await smokeMcpCapabilities({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture: mcpCapabilitiesFixture});
  await smokeStorageCrud({clickMenu, clickReady, clickText, fill, poll, evaluate, reload, fixture: storageCrudFixture});
  await smokeJdbcUsers({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: jdbcUserFixture});
  await smokeDnaWorkbench({clickMenu, clickReady, clickText, fill, poll, evaluate, send, reload, fixture: dnaWorkbenchFixture});
  await smokeRedis({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: redisFixture});
  await smokeEntitySelect({clickMenu, clickReady, clickText, fill, poll, evaluate, reload, fixture: entitySelectFixture, common: commonMainFixture});
  // Route-heavy preference checks follow retained-page tests to keep their original tab preconditions.
  await smokeHostPreferences({clickMenu, clickReady, clickText, fill, poll, evaluate, send, chooseFile, fixture: profileFixture});
  await smokeFieldMatrix({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: commonMainFixture});
  await smokeHomeCustomization({clickMenu, clickReady, clickText, fill, poll, evaluate, reload, fixture: homeFixture});
  await smokeOrganizationMembers({clickMenu, clickReady, clickText, fill, poll, evaluate, send, reload, fixture: memberFixture});
  await smokeTenantResources({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture: tenantResourceFixture});
  simulateNewRelease = true;
  await evaluate(`window.__smokeDateNow = Date.now; Date.now = () => window.__smokeDateNow() + 301000; document.dispatchEvent(new Event('visibilitychange'));`);
  await poll(() => evaluate(`document.body.textContent.includes('有新版本可用')`), 'release update prompt');
  await evaluate(`Date.now = window.__smokeDateNow; delete window.__smokeDateNow`);
  assert.equal(await evaluate(`[...document.scripts].some(script => script.src.includes('index.ffffffffffff.js'))`), false, 'release detection never reloads automatically');
  console.log('PASS: new host entry prompts an update without reloading the current page');
  await poll(() => activeStreams === 1, 'old SSE connections closed');
  assert.deepEqual(exceptions, []);
  const expectedWarning = message => message.startsWith('[API]') && (
    (expectedDictionaryFailures.length > 0 && message.includes('/common/platform/dictionaries/options'))
    || message.includes('/dna/platform/dna/federation/sql-console/') || message.includes('/dna/platform/dna/metadata/')
    || personalFixture.expectedFailures.some(url => message.includes(new URL(url, origin).pathname))
    || personalFixture.readFailures.some(url => message.includes(new URL(url, origin).pathname))
    || expectedUploadFailures.some(failure => message.includes(new URL(failure.url).pathname)));
  assert.deepEqual(warnings.filter(message => !expectedWarning(message)), []);
  assert.deepEqual(network.filter(r => r.status >= 400 && !(r.status === 400 && personalFixture.readFailures.some(url => r.url === origin + url)) && !(r.status === 409 && personalFixture.expectedFailures.some(url => r.url === origin + url)) && !(r.status === 500 && expectedDictionaryFailures.some(url => r.url === origin + url)) && !expectedUploadFailures.some(failure => failure.url === r.url && failure.status === r.status)), []);
  console.log(`PASS: ${loaded.length} exposed modules resolve with host providers; one live SSE connection; no unexpected browser exceptions, warnings or failed HTTP responses`);
  console.log(`Artifacts: ${process.env.MF_SMOKE_EDGE ? 'remotes through deployed edge' : 'local production remotes'}, ${process.env.MF_SMOKE_HOST_DIST ? 'extracted deployed host' : 'local production host'}; APIs: isolated fixtures; cache: disabled`);
  }
} catch (error) {
  console.error('Browser exception descriptions:', exceptions.map(exception => exception.exception?.description ?? exception.text));
  console.error({sqlTreeCalls, sqlExecutions, exceptions, warnings, mfResponses: network.filter(r => r.url.includes('/mf/')), failedHttp: network.filter(r => r.status >= 400), apiRequests});
  console.error(stderr.slice(-2000));
  throw error;
} finally {
  socket?.close();
  chrome.kill('SIGTERM');
  await new Promise(resolve => chrome.once('exit', resolve));
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
  await rm(profile, {recursive: true, force: true, maxRetries: 5, retryDelay: 100});
}
