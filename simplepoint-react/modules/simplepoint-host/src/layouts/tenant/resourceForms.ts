import type {RJSFSchema} from '@rjsf/utils';
import type {LocalizedText} from '@simplepoint/shared/i18n/localizedText';
export type ResourceType = 'GROUP' | 'MODULE' | 'PAGE' | 'FEATURE' | 'ACTION' | 'API';
export type TenantResource = {id: string; platformResourceId?: string | null; targetResourceId?: string | null;
  parentId?: string | null; code: string; name: string; nameLocalization?: LocalizedText | null;
  title?: string | null; label?: string | null; alias?: string | null; type: ResourceType; path?: string | null;
  icon?: string | null; sort?: number | null; description?: string | null; enabled: boolean; grantable: boolean;
  allowedActions?: string[]; managedOwner?: string | null; origin?: 'PLATFORM' | 'TENANT' | 'APP' | 'REPORT';
  availability?: string; entitlementKind?: string; entitlementStartsAt?: string | null; entitlementExpiresAt?: string | null;
  createdAt?: string; updatedAt?: string; lastSyncedAt?: string | null};
export type ResourceDraft = Partial<Pick<TenantResource, 'name' | 'nameLocalization' | 'type' | 'path' | 'parentId' | 'icon' | 'sort' | 'description' | 'targetResourceId' | 'enabled'>>;
type Translate = (key: string, fallback?: string) => string;
export function resourceDraft(row: TenantResource | null): ResourceDraft {
  return row ? {name: row.name, ...(row.nameLocalization ? {nameLocalization: row.nameLocalization} : {}), type: row.type, path: row.path ?? undefined, parentId: row.parentId ?? undefined,
    targetResourceId: row.targetResourceId ?? undefined, icon: row.icon ?? undefined, sort: row.sort,
    description: row.description ?? undefined, enabled: row.enabled}
    : {name: '', type: 'GROUP', sort: 0, enabled: true};
}
export function resourceFormSchema(t: Translate, row: TenantResource | null, type?: ResourceType, localized?: RJSFSchema): RJSFSchema {
  const localPage = type === 'PAGE' && !row?.platformResourceId;
  const result: RJSFSchema = {type: 'object', additionalProperties: false, required: ['name', 'type', ...(localPage ? ['targetResourceId', 'path'] : [])], properties: {
    name: {type: 'string', title: t('tenant.resources.name', '资源名称'), minLength: 1, pattern: '\\S', maxLength: 100},
    type: {type: 'string', title: t('tenant.resources.type', '类型'), readOnly: !!row?.platformResourceId, oneOf: [
      {const: 'GROUP', title: t('tenant.resources.group', '目录')}, {const: 'MODULE', title: t('tenant.resources.module', '模块')},
      {const: 'PAGE', title: t('tenant.resources.page', '页面')}, ...(row?.platformResourceId ? [
        {const: 'FEATURE', title: t('tenant.resources.feature', '资源')}, {const: 'ACTION', title: t('tenant.resources.action', '操作')},
        {const: 'API', title: t('tenant.resources.api', '接口')},
      ] : []),
    ]},
    parentId: {type: ['string', 'null'], title: t('tenant.resources.parent', '上级资源'), maxLength: 36},
    targetResourceId: {type: localPage ? 'string' : ['string', 'null'], title: t('tenant.resources.target', '绑定的平台页面'),
      minLength: localPage ? 1 : 0, maxLength: 36},
    path: {type: localPage ? 'string' : ['string', 'null'], title: t('tenant.resources.path', '页面路径'), maxLength: 200,
      ...(localPage ? {minLength: 1, pattern: '^/(?!/)[A-Za-z0-9/_-]+$'} : {})},
    icon: {type: ['string', 'null'], title: t('tenant.resources.icon', '图标'), maxLength: 100},
    sort: {type: ['integer', 'null'], title: t('tenant.resources.sort', '排序'), minimum: -2147483648, maximum: 2147483647},
    description: {type: ['string', 'null'], title: t('tenant.resources.description', '说明'), maxLength: 255},
    enabled: {type: 'boolean', title: t('tenant.resources.enabled', '启用资源')},
  }};
  if (localized) {
    result.properties = {nameLocalization: localized, ...result.properties};
    delete result.properties.name;
    result.required = result.required?.map(key => key === 'name' ? 'nameLocalization' : key);
  }
  return result;
}
export function resourcePayload(draft: ResourceDraft) {
  const optional = (value?: string | null) => value?.trim() || null;
  return {name: draft.nameLocalization?.defaultValue ?? draft.name?.trim() ?? '',
    ...(draft.nameLocalization ? {nameLocalization: draft.nameLocalization} : {}), type: draft.type, parentId: optional(draft.parentId),
    targetResourceId: optional(draft.targetResourceId), path: optional(draft.path), icon: optional(draft.icon),
    sort: draft.sort ?? null, description: optional(draft.description), enabled: draft.enabled};
}
