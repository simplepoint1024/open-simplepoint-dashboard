import api from '@/api/index';
import SimpleTable from '@simplepoint/components/SimpleTable';
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {Drawer, message} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import ItemConfig from './config/item';

const baseConfig = api['platform.dictionaries'];

const App = () => {
  const [openItemConfig, setOpenItemConfig] = useState(false);
  const [dictionaryCode, setDictionaryCode] = useState<string>('');
  const [drawerHeight, setDrawerHeight] = useState<number>(480);
  const {t, ensure, locale} = useI18n();

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  useEffect(() => {
    if (!openItemConfig) {
      setDrawerHeight(480);
    }
  }, [openItemConfig]);

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

  const customButtonEvents = useMemo(() => ({
    'config.item': (_keys: React.Key[], rows: any[]) => {
      const nextDictionaryCode = String(rows?.[0]?.code ?? '').trim();
      if (!nextDictionaryCode) {
        message.warning(t('table.selectOne', '请先选择一条记录'));
        return;
      }
      setDictionaryCode(nextDictionaryCode);
      setOpenItemConfig(true);
    },
  }), [t]);

  return (
    <div>
      <SimpleTable {...baseConfig} customButtonEvents={customButtonEvents}/>
      <Drawer
        title={t('dictionaries.button.config.item', '配置字典项')}
        open={openItemConfig}
        onClose={() => {
          setOpenItemConfig(false);
          setDictionaryCode('');
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
        {/* 不使用 key 强制重建，避免闪退；组件内部通过 useEffect([dictionaryCode]) 重置状态 */}
        {openItemConfig && dictionaryCode ? <ItemConfig dictionaryCode={dictionaryCode}/> : null}
      </Drawer>
    </div>
  );
};

export default App;
