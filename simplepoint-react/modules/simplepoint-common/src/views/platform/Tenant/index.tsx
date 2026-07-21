import api from '@/api/index';
import SimpleTable from '@simplepoint/components/SimpleTable';
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {Drawer, message} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import PackageConfig from './config/package';
import UserConfig from './config/user';
import type {TableButtonProps} from '@simplepoint/components/Table';

const baseConfig = api['platform.tenants'];

const App = () => {
  const [formDrawerOpen, setFormDrawerOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<any | null>(null);
  const [openPackageConfig, setOpenPackageConfig] = useState(false);
  const [openUserConfig, setOpenUserConfig] = useState(false);
  const [tenantId, setTenantId] = useState<string>('');
  const [tenantOwnerId, setTenantOwnerId] = useState<string>('');
  const [drawerHeight, setDrawerHeight] = useState<number>(480);
  const {t, ensure, locale} = useI18n();

  useEffect(() => {
    void ensure([...baseConfig.i18nNamespaces, 'packages', 'users', 'table', 'common']);
  }, [ensure, locale]);

  useEffect(() => {
    if (!openPackageConfig && !openUserConfig) {
      setDrawerHeight(480);
    }
  }, [openPackageConfig, openUserConfig]);

  const formSchemaTransform = useCallback((schema: any) => {
    const next = structuredClone(schema);
    const properties = next?.properties ?? {};
    const tenantTypeField = properties.tenantType;
    if (tenantTypeField) {
      tenantTypeField.oneOf = [
        {const: 'ORGANIZATION', title: t('tenants.type.ORGANIZATION', '组织租户')},
        {const: 'PERSONAL', title: t('tenants.type.PERSONAL', '个人租户')},
      ];
      delete tenantTypeField.enum;
    }
    if (properties.ownerId) {
      delete properties.ownerId.readOnly;
    }
    return next;
  }, [t]);

  const startResize = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    const startY = e.clientY;
    const startHeight = drawerHeight;
    const minHeight = 240;
    const maxHeight = Math.max(320, window.innerHeight - 80);

    const onMove = (me: MouseEvent) => {
      const delta = startY - me.clientY;
      let next = startHeight + delta;
      if (next < minHeight) next = minHeight;
      if (next > maxHeight) next = maxHeight;
      setDrawerHeight(next);
    };
    const onUp = () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  }, [drawerHeight]);

  const openTenantConfig = useCallback((row: any, target: 'package' | 'user') => {
    const nextTenantId = String(row?.id ?? '').trim();
    if (!nextTenantId) {
      message.warning(t('table.selectOne', '请先选择一条记录'));
      return;
    }
    setTenantId(nextTenantId);
    setTenantOwnerId(String(row?.ownerId ?? '').trim());
    setOpenPackageConfig(target === 'package');
    setOpenUserConfig(target === 'user');
  }, [t]);

  const customButtonEvents = useMemo(() => ({
    'config.package': (_keys: React.Key[], rows: any[]) => {
      openTenantConfig(rows?.[0], 'package');
    },
    'config.user': (_keys: React.Key[], rows: any[]) => {
      openTenantConfig(rows?.[0], 'user');
    },
  }), [openTenantConfig]);

  const customButtons = useMemo<TableButtonProps[]>(
    () => [
      {
        key: 'config.user',
        title: 'i18n:tenants.button.config.user',
        text: 'i18n:tenants.button.config.user',
        icon: 'TeamOutlined',
        color: 'orange',
        sort: 4,
        argumentMinSize: 1,
        argumentMaxSize: 1,
      },
    ],
    []
  );

  return (
    <div>
      <SimpleTable
        {...baseConfig}
        customButtons={customButtons}
        customButtonEvents={customButtonEvents}
        drawerOpen={formDrawerOpen}
        onDrawerOpenChange={setFormDrawerOpen}
        editingRecord={editingRecord}
        onEditingRecordChange={setEditingRecord}
        formSchemaTransform={formSchemaTransform}
        i18nNamespaces={[...baseConfig.i18nNamespaces, 'packages', 'users', 'table', 'common']}
      />
      <Drawer
        title={t('tenants.button.config.package', '配置套餐')}
        open={openPackageConfig}
        onClose={() => {
          setOpenPackageConfig(false);
          setTenantId('');
          setTenantOwnerId('');
        }}
        placement="bottom"
        maskClosable={false}
        height={drawerHeight}
        destroyOnHidden
        styles={{body: {position: 'relative', paddingTop: 12}}}
        >
        <div
          style={{position: 'absolute', top: 0, left: 0, right: 0, height: 8, cursor: 'ns-resize', zIndex: 10}}
          onMouseDown={startResize}
        />
        {/* 不使用 key 强制重建，避免闪退；组件内部通过 useEffect([tenantId]) 重置状态 */}
        {openPackageConfig && tenantId ? <PackageConfig tenantId={tenantId}/> : null}
      </Drawer>
      <Drawer
        title={t('tenants.button.config.user', '配置成员')}
        open={openUserConfig}
        onClose={() => {
          setOpenUserConfig(false);
          setTenantId('');
          setTenantOwnerId('');
        }}
        placement="bottom"
        maskClosable={false}
        height={drawerHeight}
        destroyOnHidden
        styles={{body: {position: 'relative', paddingTop: 12}}}
      >
        <div
          style={{position: 'absolute', top: 0, left: 0, right: 0, height: 8, cursor: 'ns-resize', zIndex: 10}}
          onMouseDown={startResize}
        />
        {/* 不使用 key 强制重建，避免闪退；组件内部通过 useEffect([tenantId]) 重置状态 */}
        {openUserConfig && tenantId ? <UserConfig tenantId={tenantId} ownerId={tenantOwnerId}/> : null}
      </Drawer>
    </div>
  );
};

export default App;
