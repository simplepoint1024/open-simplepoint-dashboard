import React, {useEffect, useState, useCallback} from 'react';
import SimpleTable from "@simplepoint/components/SimpleTable";
import api from '@/api/index';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Drawer, Tag} from "antd";
import PermissionConfig from "@/views/system/Menu/config/permission";
import type {SimpleTableColumnOverride} from "@simplepoint/components/SimpleTable/types";

const baseConfig = api['rbac-menus'];

const MENU_TYPE_CONFIG: Record<string, { color: string; label: string }> = {
  item:    {color: 'blue',    label: '菜单项'},
  submenu: {color: 'green',   label: '子菜单'},
  group:   {color: 'purple',  label: '分组'},
  divider: {color: 'default', label: '分隔符'},
};

const menuColumnOverrides: Record<string, SimpleTableColumnOverride<any>> = {
  type: {
    render: (value: string) => {
      if (!value) return <Tag color="default">-</Tag>;
      const cfg = MENU_TYPE_CONFIG[value];
      if (cfg) return <Tag color={cfg.color}>{cfg.label}</Tag>;
      return <Tag color="warning">{value}</Tag>;
    },
  },
};

const App = () => {
  // 受控抽屉与编辑数据
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<any | null>(null);
  const [initialValues, setInitialValues] = useState<any>({});
  const [openRoleConfig, setOpenRoleConfig] = useState(false);
  const [menuId, setMenuId] = useState<string | null>(null);
  // 新增：权限配置抽屉可拖拽高度
  const [permDrawerHeight, setPermDrawerHeight] = useState<number>(480);

  useEffect(() => {
    if (!openRoleConfig) {
      setPermDrawerHeight(480);
    }
  }, [openRoleConfig]);

  const startResize = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    const startY = e.clientY;
    const startHeight = permDrawerHeight;
    const minHeight = 240;
    const maxHeight = Math.max(320, window.innerHeight - 80);
    const onMove = (me: MouseEvent) => {
      const delta = startY - me.clientY; // bottom 抽屉向上拖动增高
      let next = startHeight + delta;
      if (next < minHeight) next = minHeight;
      if (next > maxHeight) next = maxHeight;
      setPermDrawerHeight(next);
    };
    const onUp = () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  }, [permDrawerHeight]);

  // 国际化
  const {t, ensure, locale} = useI18n();
  // 确保本页所需命名空间加载（roles），语言切换后也会自动增量加载
  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);
  // 自定义添加：若选中一行，则将其 id 作为 parent，默认类型为 item
  const customButtonEvents = {
    add: (_keys: React.Key[], rows: any[]) => {
      const parent = rows && rows.length > 0 ? rows[0]?.id : undefined;
      const path = rows && rows.length > 0 ? rows[0]?.path : undefined;
      setEditingRecord(null);
      setInitialValues({path, parent, type: 'item'});
      setDrawerOpen(true);
    },
    'config.permission':(_keys: React.Key[], rows: any[]) => {
      setOpenRoleConfig(true);
      setMenuId(rows[0].id);
    },
  } as const;

  return (
    <div>
      <SimpleTable
        {...api['rbac-menus']}
        drawerOpen={drawerOpen}
        onDrawerOpenChange={setDrawerOpen}
        editingRecord={editingRecord}
        onEditingRecordChange={setEditingRecord}
        initialValues={initialValues}
        customButtonEvents={customButtonEvents}
        columnOverrides={menuColumnOverrides}
      />
      <Drawer
        title={t("menus.config.permission", "功能配置")}
        open={openRoleConfig}
        // width 对 bottom 抽屉无效，使用高度控制
        height={permDrawerHeight}
        onClose={() => { setOpenRoleConfig(false); setMenuId(null); }}
        placement={"bottom"}
        maskClosable={false}
        destroyOnHidden
        styles={{ body: { position: 'relative', paddingTop: 12 } }}
      >
        <div
          style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 8, cursor: 'ns-resize', zIndex: 10 }}
          onMouseDown={startResize}
        />
        {/* 不使用 key 强制重建，避免闪退；组件内部通过 useEffect([menuId]) 重置状态 */}
        {menuId && <PermissionConfig menuId={menuId}/>}
      </Drawer>
    </div>
  );
};

export default App;