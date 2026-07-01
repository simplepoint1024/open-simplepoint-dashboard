import api from '@/api/index';
import SimpleTable from '@simplepoint/components/SimpleTable';
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {Drawer} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import PackageConfig from './config/package';
import UserConfig from './config/user';
import {usePage} from '@simplepoint/shared/api/methods';
import {fetchOwnerItems, UserRelevanceVo} from '@/api/platform/tenant';
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
    void ensure([...baseConfig.i18nNamespaces, 'users']);
  }, [ensure, locale]);

  useEffect(() => {
    if (!openPackageConfig && !openUserConfig) {
      setDrawerHeight(480);
    }
  }, [openPackageConfig, openUserConfig]);

  const {data: ownerItemsPage} = usePage<UserRelevanceVo>(
    ['tenant-owner-items'],
    () => fetchOwnerItems({page: '0', size: '10000000'}),
    {enabled: formDrawerOpen}
  );

  const ownerOptions = useMemo(() => {
    const pageContent = ownerItemsPage?.content ?? [];
    return pageContent.map((item) => ({
      const: item.id,
      title: formatUserLabel(item),
    }));
  }, [ownerItemsPage?.content]);

  const ownerSelectUiSchema = useMemo(
    () => ({
      ownerId: {
        'ui:widget': 'select',
      },
    }),
    []
  );

  const formSchemaTransform = useCallback((schema: any, editingRecord: any | null) => {
    const next = structuredClone(schema);
    const properties = next?.properties ?? {};
    const ownerField = properties.ownerId;
    if (!ownerField) {
      return next;
    }

    const options = [...ownerOptions];
    const currentOwnerId = editingRecord?.ownerId;
    if (currentOwnerId && !options.some((option) => option.const === currentOwnerId)) {
      options.push({const: currentOwnerId, title: currentOwnerId});
    }

    ownerField.oneOf = options;
    delete ownerField.readOnly;
    return next;
  }, [ownerOptions]);

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

  const customButtonEvents = {
    'config.package': (_keys: React.Key[], rows: any[]) => {
      setOpenPackageConfig(true);
      setOpenUserConfig(false);
      setTenantId(rows?.[0]?.id ?? '');
      setTenantOwnerId(rows?.[0]?.ownerId ?? '');
    },
    'config.user': (_keys: React.Key[], rows: any[]) => {
      setOpenUserConfig(true);
      setOpenPackageConfig(false);
      setTenantId(rows?.[0]?.id ?? '');
      setTenantOwnerId(rows?.[0]?.ownerId ?? '');
    },
  };

  const customButtons = useMemo<TableButtonProps[]>(
    () => [
      {
        key: 'config.user',
        title: '配置成员',
        text: '配置成员',
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
        formUiSchema={ownerSelectUiSchema}
        i18nNamespaces={[...baseConfig.i18nNamespaces, 'users']}
      />
      <Drawer
        title={t('table.button.config.package', '配置套餐')}
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
        {tenantId && <PackageConfig tenantId={tenantId}/>}
      </Drawer>
      <Drawer
        title={t('table.button.config.user', '配置成员')}
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
        {tenantId && <UserConfig tenantId={tenantId} ownerId={tenantOwnerId}/>}
      </Drawer>
    </div>
  );
};

function formatUserLabel(item: UserRelevanceVo) {
  const secondary = item.email || item.phoneNumber;
  return secondary ? `${item.name} (${secondary})` : item.name;
}

export default App;
