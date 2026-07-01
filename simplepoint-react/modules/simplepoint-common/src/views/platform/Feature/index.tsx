import api from '@/api/index';
import SimpleTable from '@simplepoint/components/SimpleTable';
import React, {useCallback, useEffect, useState} from 'react';
import {Drawer} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import PermissionConfig from './config/permission';

const baseConfig = api['platform.features'];

const App = () => {
  const [openPermissionConfig, setOpenPermissionConfig] = useState(false);
  const [featureCode, setFeatureCode] = useState<string>('');
  const [drawerHeight, setDrawerHeight] = useState<number>(480);
  const {t, ensure, locale} = useI18n();

  useEffect(() => {
    void ensure([...baseConfig.i18nNamespaces, 'permissions', 'table', 'common']);
  }, [ensure, locale]);

  useEffect(() => {
    if (!openPermissionConfig) {
      setDrawerHeight(480);
    }
  }, [openPermissionConfig]);

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
    'config.permission': (_keys: React.Key[], rows: any[]) => {
      setOpenPermissionConfig(true);
      setFeatureCode(rows?.[0]?.code ?? '');
    },
  };

  return (
    <div>
      <SimpleTable {...baseConfig} customButtonEvents={customButtonEvents}/>
      <Drawer
        title={t('features.button.config.permission', '配置权限')}
        open={openPermissionConfig}
        onClose={() => {
          setOpenPermissionConfig(false);
          setFeatureCode('');
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
        {/* 不使用 key 强制重建，避免闪退；组件内部通过 useEffect([featureCode]) 重置状态 */}
        {featureCode && <PermissionConfig featureCode={featureCode}/>}
      </Drawer>
    </div>
  );
};

export default App;
