import React, {useCallback, useEffect, useMemo, useState} from 'react';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTableColumnOverride} from '@simplepoint/components/SimpleTable/types';
import api from '@/api';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Tag, Typography} from 'antd';

const baseConfig = api['rbac-resources'];

const RESOURCE_TYPE_CONFIG: Record<string, { color: string; labelKey: string; fallback: string }> = {
  GROUP: {color: 'default', labelKey: 'resources.type.GROUP', fallback: '分组'},
  MODULE: {color: 'blue', labelKey: 'resources.type.MODULE', fallback: '模块'},
  PAGE: {color: 'green', labelKey: 'resources.type.PAGE', fallback: '页面'},
  FEATURE: {color: 'cyan', labelKey: 'resources.type.FEATURE', fallback: '功能'},
  ACTION: {color: 'orange', labelKey: 'resources.type.ACTION', fallback: '操作'},
  API: {color: 'purple', labelKey: 'resources.type.API', fallback: '接口'},
};

const ROUTE_KIND_CONFIG: Record<string, { color: string; labelKey: string; fallback: string }> = {
  item: {color: 'blue', labelKey: 'resources.routeKind.item', fallback: '菜单项'},
  submenu: {color: 'green', labelKey: 'resources.routeKind.submenu', fallback: '子菜单'},
  group: {color: 'purple', labelKey: 'resources.routeKind.group', fallback: '分组'},
  divider: {color: 'default', labelKey: 'resources.routeKind.divider', fallback: '分隔符'},
};

const RESOURCE_SCOPE_CONFIG = {
  SYSTEM: {color: 'red', labelKey: 'resources.scope.SYSTEM', fallback: '系统级'},
  PLATFORM: {color: 'purple', labelKey: 'resources.scope.PLATFORM', fallback: '平台级'},
  TENANT: {color: 'blue', labelKey: 'resources.scope.TENANT', fallback: '租户级'},
  PERSONAL: {color: 'green', labelKey: 'resources.scope.PERSONAL', fallback: '用户级'},
} as const;

type ResourceScopeType = keyof typeof RESOURCE_SCOPE_CONFIG;

const normalizeScopeTypes = (value: unknown): ResourceScopeType[] => {
  if (!Array.isArray(value)) return [];
  return value.filter((scope): scope is ResourceScopeType => (
    typeof scope === 'string' && scope in RESOURCE_SCOPE_CONFIG
  ));
};

const App = () => {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<any | null>(null);
  const [initialValues, setInitialValues] = useState<any>({});
  const [scopeBoundary, setScopeBoundary] = useState<ResourceScopeType[] | null>(null);
  const {t, ensure, locale} = useI18n();

  useEffect(() => {
    void ensure([...baseConfig.i18nNamespaces, 'table', 'common']);
  }, [ensure, locale]);

  const columnOverrides = useMemo<Record<string, SimpleTableColumnOverride<any>>>(() => ({
    code: {
      width: 260,
      ellipsis: true,
      render: (value: string) => value ? <Typography.Text code>{value}</Typography.Text> : '-',
    },
    alias: {
      width: 160,
      ellipsis: true,
      render: (value: string) => value || '-',
    },
    type: {
      width: 120,
      render: (value: string) => {
        const cfg = value ? RESOURCE_TYPE_CONFIG[value] : undefined;
        if (!cfg) return <Tag color="default">{value || '-'}</Tag>;
        return <Tag color={cfg.color}>{t(cfg.labelKey, cfg.fallback)}</Tag>;
      },
    },
    routeKind: {
      width: 120,
      render: (value: string) => {
        const cfg = value ? ROUTE_KIND_CONFIG[value] : undefined;
        if (!cfg) return <Tag color="default">{value || '-'}</Tag>;
        return <Tag color={cfg.color}>{t(cfg.labelKey, cfg.fallback)}</Tag>;
      },
    },
    scopeTypes: {
      width: 240,
      render: (value: ResourceScopeType[]) => {
        const scopes = normalizeScopeTypes(value);
        if (!scopes.length) return '-';
        return scopes.map((scope) => {
          const config = RESOURCE_SCOPE_CONFIG[scope];
          return <Tag key={scope} color={config.color}>{t(config.labelKey, config.fallback)}</Tag>;
        });
      },
    },
    path: {width: 240, ellipsis: true},
    component: {width: 260, ellipsis: true},
    pluginId: {width: 180, ellipsis: true},
    publicAccess: {
      width: 120,
      render: (value: boolean) => (
        <Tag color={value ? 'green' : 'default'}>
          {value ? t('resources.boolean.public', '公开') : t('resources.boolean.protected', '受控')}
        </Tag>
      ),
    },
    grantable: {
      width: 120,
      render: (value: boolean) => (
        <Tag color={value ? 'blue' : 'default'}>
          {value ? t('resources.boolean.grantable', '可授权') : t('resources.boolean.notGrantable', '仅结构')}
        </Tag>
      ),
    },
    disabled: {
      width: 100,
      render: (value: boolean) => (
        <Tag color={value ? 'red' : 'green'}>
          {value ? t('resources.boolean.disabled', '停用') : t('resources.boolean.enabled', '启用')}
        </Tag>
      ),
    },
  }), [t]);

  const formSchemaTransform = useCallback((schema: any) => {
    const next = structuredClone(schema);
    const properties = next?.properties ?? {};
    const setChoices = (
      field: Record<string, any> | undefined,
      choices: Array<{value: string; label: string}>
    ) => {
      if (!field) return;
      const target = field.items && typeof field.items === 'object' ? field.items : field;
      target.oneOf = choices.map(({value, label}) => ({const: value, title: label}));
      delete target.enum;
    };

    setChoices(
      properties.type,
      Object.entries(RESOURCE_TYPE_CONFIG).map(([value, config]) => ({
        value,
        label: t(config.labelKey, config.fallback),
      }))
    );
    setChoices(
      properties.routeKind,
      Object.entries(ROUTE_KIND_CONFIG).map(([value, config]) => ({
        value,
        label: t(config.labelKey, config.fallback),
      }))
    );
    const allowedScopes = scopeBoundary ?? Object.keys(RESOURCE_SCOPE_CONFIG) as ResourceScopeType[];
    setChoices(
      properties.scopeTypes,
      allowedScopes.map((value) => {
        const config = RESOURCE_SCOPE_CONFIG[value];
        return {value, label: t(config.labelKey, config.fallback)};
      })
    );
    if (properties.scopeTypes) {
      properties.scopeTypes.minItems = 1;
      if (scopeBoundary) {
        const scopeLabels = allowedScopes
          .map((value) => {
            const config = RESOURCE_SCOPE_CONFIG[value];
            return t(config.labelKey, config.fallback);
          })
          .join('、');
        properties.scopeTypes.description = t(
          'resources.description.scopeTypesWithParent',
          '子资源只能使用父资源允许的作用域：{scopes}',
          {scopes: scopeLabels}
        );
      }
    }
    return next;
  }, [scopeBoundary, t]);

  const customButtonEvents = {
    add: (_keys: React.Key[], rows: any[]) => {
      const parent = rows?.[0];
      const parentId = parent?.id;
      const parentPath = parent?.path;
      const parentScopes = normalizeScopeTypes(parent?.scopeTypes);
      setEditingRecord(null);
      setScopeBoundary(parentId ? parentScopes : null);
      setInitialValues({
        parentId,
        type: parentId ? 'PAGE' : 'MODULE',
        routeKind: parentId ? 'item' : 'submenu',
        path: parentPath,
        scopeTypes: parentId && parentScopes.length ? parentScopes : ['SYSTEM'],
        grantable: true,
        publicAccess: false,
        disabled: false,
      });
      setDrawerOpen(true);
    },
    edit: (_keys: React.Key[], rows: any[]) => {
      const record = rows?.[0] ?? null;
      const inheritedScopes = normalizeScopeTypes(record?.parentScopeTypes);
      const currentScopes = normalizeScopeTypes(record?.scopeTypes);
      setScopeBoundary(record?.parentId ? (inheritedScopes.length ? inheritedScopes : currentScopes) : null);
      setEditingRecord(record);
      setDrawerOpen(true);
    },
  } as const;

  return (
    <SimpleTable
      {...baseConfig}
      drawerOpen={drawerOpen}
      onDrawerOpenChange={setDrawerOpen}
      editingRecord={editingRecord}
      onEditingRecordChange={setEditingRecord}
      initialValues={initialValues}
      customButtonEvents={customButtonEvents}
      columnOverrides={columnOverrides}
      formSchemaTransform={formSchemaTransform}
      submitRefreshTargets={{page: true, schema: false}}
      deleteRefreshTargets={{page: true, schema: false}}
    />
  );
};

export default App;
