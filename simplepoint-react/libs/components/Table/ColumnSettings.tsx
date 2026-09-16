import React, {useCallback, useEffect, useState} from 'react';
import {
  DndContext,
  DragEndEvent,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
} from '@dnd-kit/core';
import {
  SortableContext,
  arrayMove,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import {CSS} from '@dnd-kit/utilities';
import {Alert, Button, Checkbox, Divider, Drawer, InputNumber, Segmented, Select, Switch, Tag} from 'antd';
import {HolderOutlined} from '@ant-design/icons';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {ColumnFixed, ColumnSetting, TableDisplaySettings} from './settings';
import {DEFAULT_FIXED_COLUMN_WIDTH, normalizeColumnSettings} from './settings';

export type {ColumnFixed, ColumnSetting, TableDisplaySettings} from './settings';
export {DEFAULT_TABLE_DISPLAY_SETTINGS, normalizeColumnSettings} from './settings';

// ─── Sortable row ─────────────────────────────────────────────────────────────

interface SortableRowProps {
  setting: ColumnSetting;
  onChange: (key: string, patch: Partial<ColumnSetting>) => void;
}

const SortableRow: React.FC<SortableRowProps> = ({setting, onChange}) => {
  const {t} = useI18n();
  const {attributes, listeners, setNodeRef, transform, transition, isDragging} = useSortable({
    id: setting.key,
  });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.35 : 1,
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '7px 4px',
    borderRadius: 6,
    background: isDragging ? 'var(--ant-color-primary-bg)' : undefined,
    marginBottom: 2,
  };

  return (
    <div ref={setNodeRef} className="sp-table-column-setting-row" style={style}>
      <span
        {...attributes}
        {...listeners}
        style={{
          cursor: isDragging ? 'grabbing' : 'grab',
          color: '#bbb',
          padding: '0 2px',
          fontSize: 14,
          flexShrink: 0,
          lineHeight: 1,
          touchAction: 'none',
        }}
      >
        <HolderOutlined/>
      </span>
      <Checkbox
        checked={setting.visible}
        onChange={e => onChange(setting.key, {visible: e.target.checked})}
      />
      <span
        style={{
          flex: 1,
          fontSize: 13,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          opacity: setting.visible ? 1 : 0.4,
        }}
      >
        {setting.label}
      </span>
      <Select
        size="small"
        popupMatchSelectWidth={false}
        value={setting.fixed ?? 'none'}
        onChange={v => onChange(setting.key, {
          fixed: v === 'none' ? undefined : v as ColumnFixed,
          ...(
            v !== 'none' && setting.width == null
              ? {width: DEFAULT_FIXED_COLUMN_WIDTH}
              : {}
          ),
        })}
        style={{width: 88, flexShrink: 0}}
        options={[
          {value: 'none', label: t('table.columnSettings.fixed.none', '不固定')},
          {value: 'left', label: t('table.columnSettings.fixed.left', '左固定')},
          {value: 'right', label: t('table.columnSettings.fixed.right', '右固定')},
        ]}
      />
      <InputNumber
        aria-label={t('table.columnSettings.width', '列宽')}
        size="small"
        min={80}
        max={1200}
        step={10}
        controls={false}
        placeholder={t('table.columnSettings.width.auto', '自动')}
        value={setting.width}
        onChange={value => onChange(setting.key, {width: value == null ? undefined : Number(value)})}
        style={{width: 72, flexShrink: 0}}
      />
    </div>
  );
};

// ─── Column settings drawer ───────────────────────────────────────────────────

interface ColumnSettingsProps {
  open: boolean;
  settings: ColumnSetting[];
  displaySettings: TableDisplaySettings;
  onSave: (settings: ColumnSetting[], displaySettings: TableDisplaySettings) => void;
  onClose: () => void;
  onReset: () => void;
}

const ColumnSettings: React.FC<ColumnSettingsProps> = ({
  open,
  settings: initialSettings,
  displaySettings: initialDisplaySettings,
  onSave,
  onClose,
  onReset,
}) => {
  const {t} = useI18n();
  const [items, setItems] = useState<ColumnSetting[]>(initialSettings);
  const [displaySettings, setDisplaySettings] = useState<TableDisplaySettings>(initialDisplaySettings);

  // Re-sync when drawer opens
  useEffect(() => {
    if (open) {
      setItems(initialSettings);
      setDisplaySettings(initialDisplaySettings);
    }
  }, [open]); // intentionally not including settings to avoid overwriting an in-progress edit

  const sensors = useSensors(
    useSensor(PointerSensor, {activationConstraint: {distance: 4}}),
    useSensor(KeyboardSensor, {coordinateGetter: sortableKeyboardCoordinates}),
  );

  const handleDragEnd = (event: DragEndEvent) => {
    const {active, over} = event;
    if (over && active.id !== over.id) {
      setItems(prev => {
        const oldIdx = prev.findIndex(i => i.key === String(active.id));
        const newIdx = prev.findIndex(i => i.key === String(over.id));
        return arrayMove(prev, oldIdx, newIdx);
      });
    }
  };

  const handleChange = useCallback((key: string, patch: Partial<ColumnSetting>) => {
    setItems(prev => prev.map(item => (item.key === key ? {...item, ...patch} : item)));
  }, []);

  const visibleCount = items.filter(i => i.visible).length;
  const allChecked = visibleCount === items.length;
  const indeterminate = visibleCount > 0 && visibleCount < items.length;

  return (
    <Drawer
      title={t('table.settings.title', '表格设置')}
      rootClassName="sp-table-settings-drawer"
      width="min(460px, 100vw)"
      open={open}
      onClose={onClose}
      styles={{body: {padding: '12px 16px'}}}
      footer={
        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
          <Button type="text" danger size="small" onClick={() => {
            onReset();
            onClose();
          }}>
            {t('table.columnSettings.resetDefault', '重置默认')}
          </Button>
          <div style={{display: 'flex', gap: 8}}>
            <Button onClick={onClose}>{t('cancel', '取消')}</Button>
            <Button type="primary" disabled={visibleCount === 0} onClick={() => {
              onSave(normalizeColumnSettings(items), displaySettings);
              onClose();
            }}>
              {t('table.columnSettings.save', '保存')}
            </Button>
          </div>
        </div>
      }
    >
      <Divider titlePlacement="start" plain style={{margin: '0 0 12px'}}>
        {t('table.settings.columns', '列')}
      </Divider>

      {/* Header: select-all + stats */}
      <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8}}>
        <Checkbox
          indeterminate={indeterminate}
          checked={allChecked}
          onChange={e => setItems(prev => prev.map(i => ({...i, visible: e.target.checked})))}
        >
          {t('table.columnSettings.selectAll', '全选')}
        </Checkbox>
        <Tag color="blue" style={{margin: 0}}>
          {t('table.columnSettings.visibleCount', '{visible} / {total} 显示', {
            visible: visibleCount,
            total: items.length,
          })}
        </Tag>
      </div>

      <div style={{display: 'flex', justifyContent: 'flex-end', gap: 42, marginBottom: 4}}>
        <span style={{fontSize: 11, color: '#999'}}>
          {t('table.columnSettings.fixed.label', '固定')}
        </span>
        <span style={{fontSize: 11, color: '#999', marginRight: 16}}>
          {t('table.columnSettings.width', '列宽')}
        </span>
      </div>

      <Divider style={{margin: '0 0 8px'}}/>

      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <SortableContext items={items.map(i => i.key)} strategy={verticalListSortingStrategy}>
          {items.map(item => (
            <SortableRow key={item.key} setting={item} onChange={handleChange}/>
          ))}
        </SortableContext>
      </DndContext>

      {visibleCount === 0 && (
        <Alert
          type="warning"
          showIcon
          message={t('table.columnSettings.visibleRequired', '至少保留一列可见列')}
          style={{marginTop: 12}}
        />
      )}

      <div style={{marginTop: 12, padding: '8px 4px 0', borderTop: '1px solid rgba(0,0,0,0.06)'}}>
        <span style={{fontSize: 12, color: '#aaa'}}>
          {t('table.columnSettings.dragPrefix', '拖动')} <HolderOutlined style={{fontSize: 11}}/> {t('table.columnSettings.dragSuffix', '可调整列顺序')}
        </span>
      </div>

      <Divider titlePlacement="start" plain>
        {t('table.settings.appearance', '外观')}
      </Divider>
      <SettingRow label={t('table.settings.density', '行密度')}>
        <Segmented
          size="small"
          value={displaySettings.size}
          onChange={value => setDisplaySettings(prev => ({...prev, size: value as TableDisplaySettings['size']}))}
          options={[
            {value: 'small', label: t('table.settings.density.compact', '紧凑')},
            {value: 'middle', label: t('table.settings.density.default', '默认')},
            {value: 'large', label: t('table.settings.density.comfortable', '宽松')},
          ]}
        />
      </SettingRow>
      <SettingSwitch label={t('table.settings.bordered', '显示边框')} checked={displaySettings.bordered}
                     onChange={checked => setDisplaySettings(prev => ({...prev, bordered: checked}))}/>
      <SettingSwitch label={t('table.settings.striped', '斑马纹')} checked={displaySettings.striped}
                     onChange={checked => setDisplaySettings(prev => ({...prev, striped: checked}))}/>
      <SettingSwitch label={t('table.settings.wrapText', '单元格自动换行')} checked={displaySettings.wrapText}
                     onChange={checked => setDisplaySettings(prev => ({...prev, wrapText: checked}))}/>
      <SettingSwitch label={t('table.settings.stickyHeader', '吸顶表头')} checked={displaySettings.stickyHeader}
                     onChange={checked => setDisplaySettings(prev => ({...prev, stickyHeader: checked}))}/>
      <SettingSwitch label={t('table.settings.rowHover', '行悬停高亮')} checked={displaySettings.rowHover}
                     onChange={checked => setDisplaySettings(prev => ({...prev, rowHover: checked}))}/>

      <Divider titlePlacement="start" plain>
        {t('table.settings.pagination', '分页')}
      </Divider>
      <SettingSwitch label={t('table.settings.showSizeChanger', '显示每页条数')} checked={displaySettings.showSizeChanger}
                     onChange={checked => setDisplaySettings(prev => ({...prev, showSizeChanger: checked}))}/>
      <SettingSwitch label={t('table.settings.showQuickJumper', '显示快速跳页')} checked={displaySettings.showQuickJumper}
                     onChange={checked => setDisplaySettings(prev => ({...prev, showQuickJumper: checked}))}/>
    </Drawer>
  );
};

const SettingRow: React.FC<React.PropsWithChildren<{label: string}>> = ({label, children}) => (
  <div className="sp-table-setting-row">
    <span>{label}</span>
    {children}
  </div>
);

const SettingSwitch: React.FC<{label: string; checked: boolean; onChange: (checked: boolean) => void}> = ({
  label,
  checked,
  onChange,
}) => (
  <SettingRow label={label}>
    <Switch size="small" checked={checked} onChange={onChange}/>
  </SettingRow>
);

export default ColumnSettings;
