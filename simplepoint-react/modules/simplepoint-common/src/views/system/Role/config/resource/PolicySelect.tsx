import {useMemo} from 'react';
import EntitySelect, {type EntitySelectSource} from '@simplepoint/components/EntitySelect';
import {get} from '@simplepoint/shared/api/methods';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {resolveResourceLabel} from '@simplepoint/shared/i18n/resourceLabel';
import {permissionLabels} from '@simplepoint/shared/i18n/permissionLabels';
import type {ResourcePolicyOption} from '@/api/system/role-resource-configuration';
import api from '@/api';

/** Permission-filtered, page-model-specific directory with bounded selected-ID hydration. */
export default function PolicySelect({roleId, resourceCode, kind, value, disabled, revision, onChange}: {
  roleId: string; resourceCode: string; kind: 'data' | 'field'; value?: string | null;
  disabled: boolean; revision: number; onChange: (value?: string) => void;
}) {
  const {t} = useI18n();
  const endpoint = api['rbac-role-resource-configuration'].baseUrl + '/roles/' + encodeURIComponent(roleId) + '/policy-options';
  const source = useMemo<EntitySelectSource>(() => {
    const labels = permissionLabels(t);
    const option = (row: Record<string, unknown>) => typeof row.id === 'string' && row.id !== '***'
      ? {value: row.id, label: resolveResourceLabel(typeof row.name === 'string' ? row.name : undefined,
        t('permissions.unavailable', '名称暂不可用'), t)
        + (typeof row.detail === 'string' ? ' · ' + labels.scope(row.detail) : '')} : undefined;
    return {key: JSON.stringify(['role-policy.v1', roleId, resourceCode, kind, revision]),
      searchParams: (keyword, page, size) => ({resourceCode, kind, keyword, page, size}),
      option,
      resolve: async (ids, signal) => {
        const options: {value: string; label: string}[] = [];
        for (const selectedId of ids) {
          const page = normalizePage(await get<RawPage<ResourcePolicyOption>>(endpoint,
            {resourceCode, kind, selectedId, page: 0, size: 1}, {signal, notifyOnError: false}), 1);
          for (const row of page.content) {const resolved = option(row as unknown as Record<string, unknown>); if (resolved) options.push(resolved);}
        }
        return options;
      },
    };
  }, [roleId, resourceCode, kind, revision, endpoint, t]);
  return <EntitySelect endpoint={endpoint} source={source} value={value} disabled={disabled}
    ariaLabel={kind === 'data' ? t('roles.resourceConfig.dataScope', '数据权限') : t('roles.resourceConfig.fieldScope', '字段权限')}
    className="role-resource-policy-select" placeholder={kind === 'data'
      ? t('roles.resourceConfig.chooseDataRule', '选择数据范围规则') : t('roles.resourceConfig.chooseFieldRule', '选择字段范围规则')}
    onChange={onChange}/>;
}
