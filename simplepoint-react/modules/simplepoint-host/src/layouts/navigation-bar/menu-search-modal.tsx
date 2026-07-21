import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {Input, List, Modal, Typography} from 'antd';
import {SearchOutlined} from '@ant-design/icons';
import {createIcon} from '@simplepoint/shared/types/icon.ts';
import type {RouteInfo} from '@/store/routes';
import {flattenRoutes} from '@/store/routes';

interface MenuSearchModalProps {
  open: boolean;
  onClose: () => void;
  menus: RouteInfo[];
  onNavigate: (path: string) => void;
  t: (key: string, fallback: string) => string;
}

const MenuSearchModal: React.FC<MenuSearchModalProps> = ({open, onClose, menus, onNavigate, t}) => {
  const [keyword, setKeyword] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  const inputRef = useRef<ReturnType<typeof Input> | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);

  const leafMenus = useMemo(() => flattenRoutes(menus), [menus]);

  const filtered = useMemo(() => {
    if (!keyword.trim()) return leafMenus;
    const lower = keyword.toLowerCase();
    return leafMenus.filter(m => {
      const label = (m.label || m.title || '').toLowerCase();
      const path = (m.path || '').toLowerCase();
      return label.includes(lower) || path.includes(lower);
    });
  }, [keyword, leafMenus]);

  useEffect(() => { setActiveIndex(0); }, [filtered]);

  useEffect(() => {
    if (!open) {
      setKeyword('');
      setActiveIndex(0);
    }
  }, [open]);

  // 高亮关键词
  const highlightText = useCallback((text: string): React.ReactNode => {
    if (!keyword.trim()) return text;
    const idx = text.toLowerCase().indexOf(keyword.toLowerCase());
    if (idx === -1) return text;
    return (
      <>
        {text.slice(0, idx)}
        <mark style={{ background: 'rgba(22,119,255,0.15)', color: 'var(--ant-color-primary,#1677ff)', padding: 0, borderRadius: 2 }}>
          {text.slice(idx, idx + keyword.length)}
        </mark>
        {text.slice(idx + keyword.length)}
      </>
    );
  }, [keyword]);

  // 自动滚动 active 项到可见区域
  useEffect(() => {
    if (!listRef.current) return;
    const item = listRef.current.querySelector(`[data-idx="${activeIndex}"]`) as HTMLElement | null;
    item?.scrollIntoView({ block: 'nearest' });
  }, [activeIndex]);

  const handleSelect = useCallback((menu: RouteInfo) => {
    if (menu.path) {
      onNavigate(menu.path);
      onClose();
    }
  }, [onNavigate, onClose]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActiveIndex(prev => Math.min(prev + 1, filtered.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveIndex(prev => Math.max(prev - 1, 0));
    } else if (e.key === 'Enter' && filtered[activeIndex]) {
      handleSelect(filtered[activeIndex]);
    }
  }, [filtered, activeIndex, handleSelect]);

  return (
    <Modal
      open={open}
      onCancel={onClose}
      footer={null}
      closable={false}
      width={520}
      styles={{
        body: { padding: 0 },
        container: { padding: 0, borderRadius: 8, overflow: 'hidden' },
      }}
    >
      {/* 搜索输入 */}
      <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--ant-color-border-secondary, rgba(0,0,0,0.06))' }}>
        <Input
          ref={inputRef as React.RefObject<any>}
          autoFocus
          size="large"
          prefix={<SearchOutlined style={{ color: 'var(--ant-color-text-quaternary)', fontSize: 16 }} />}
          placeholder={t('menu.search.placeholder', '搜索菜单…')}
          value={keyword}
          onChange={e => setKeyword(e.target.value)}
          onKeyDown={handleKeyDown}
          allowClear
          bordered={false}
          style={{ fontSize: 15, padding: 0 }}
        />
      </div>

      {/* 结果列表 */}
      <div ref={listRef} style={{ maxHeight: 380, overflow: 'auto', padding: '4px 0' }}>
        <List
          size="small"
          dataSource={filtered.slice(0, 50)}
          locale={{ emptyText: (
            <div style={{ padding: '32px 0', textAlign: 'center', opacity: 0.4 }}>
              {t('menu.search.empty', '没有匹配的菜单')}
            </div>
          )}}
          renderItem={(item, index) => (
            <List.Item
              key={item.path || String(item.id)}
              data-idx={index}
              onClick={() => handleSelect(item)}
              onMouseEnter={() => setActiveIndex(index)}
              style={{
                cursor: 'pointer',
                padding: '6px 16px',
                margin: '0 4px',
                borderRadius: 4,
                background: index === activeIndex ? 'var(--ant-color-primary-bg, #e6f4ff)' : 'transparent',
                borderBottom: 'none',
                transition: 'background 0.12s',
              }}
            >
              <List.Item.Meta
                avatar={item.icon
                  ? <span style={{ fontSize: 16, opacity: 0.75 }}>{createIcon(item.icon)}</span>
                  : undefined
                }
                title={
                  <span style={{ fontSize: 13, fontWeight: index === activeIndex ? 600 : 400 }}>
                    {highlightText(t(item.title || '', item.label || item.title || ''))}
                  </span>
                }
                description={
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    {highlightText(item.path || '')}
                  </Typography.Text>
                }
              />
            </List.Item>
          )}
        />
      </div>

      {/* 底部快捷键说明 */}
      <div style={{
        padding: '6px 16px',
        borderTop: '1px solid var(--ant-color-border-secondary, rgba(0,0,0,0.06))',
        fontSize: 11,
        opacity: 0.45,
        display: 'flex',
        gap: 12,
      }}>
        <span>↑↓ {t('menu.search.hint.navigate', '导航')}</span>
        <span>↵ {t('menu.search.hint.open', '打开')}</span>
        <span>Esc {t('menu.search.hint.close', '关闭')}</span>
        <span style={{ marginLeft: 'auto' }}>{filtered.length} {t('menu.search.hint.results', '个结果')}</span>
      </div>
    </Modal>
  );
};

export default MenuSearchModal;
