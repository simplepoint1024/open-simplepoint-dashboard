import React, {useEffect, useMemo, useState} from 'react';
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
  ACTION: {color: 'orange', labelKey: 'resources.type.ACTION', fallback: '动作'},
  API: {color: 'purple', labelKey: 'resources.type.API', fallback: '接口'},
};

const ROUTE_KIND_CONFIG: Record<string, { color: string; labelKey: string; fallback: string }> = {
  item: {color: 'blue', labelKey: 'resources.routeKind.item', fallback: '菜单项'},
  submenu: {color: 'green', labelKey: 'resources.routeKind.submenu', fallback: '子菜单'},
  group: {color: 'purple', labelKey: 'resources.routeKind.group', fallback: '分组'},
  divider: {color: 'default', labelKey: 'resources.routeKind.divider', fallback: '分隔符'},
};

const App = () => {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<any | null>(null);
  const [initialValues, setInitialValues] = useState<any>({});
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

  const customButtonEvents = {
    add: (_keys: React.Key[], rows: any[]) => {
      const parentId = rows?.[0]?.id;
      const parentPath = rows?.[0]?.path;
      setEditingRecord(null);
      setInitialValues({
        parentId,
        type: parentId ? 'PAGE' : 'GROUP',
        routeKind: parentId ? 'item' : 'submenu',
        path: parentPath,
        grantable: true,
        publicAccess: false,
        disabled: false,
      });
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
      submitRefreshTargets={{page: true, schema: false}}
      deleteRefreshTargets={{page: true, schema: false}}
    />
  );
};

export default App;
