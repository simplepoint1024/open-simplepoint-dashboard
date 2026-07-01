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
import {Button, Checkbox, Divider, Drawer, Select, Tag} from 'antd';
import {HolderOutlined} from '@ant-design/icons';

export type ColumnFixed = 'left' | 'right' | undefined;

export type ColumnSetting = {
  key: string;
  label: string;
  visible: boolean;
  fixed?: ColumnFixed;
};

// ─── Sortable row ─────────────────────────────────────────────────────────────

interface SortableRowProps {
  setting: ColumnSetting;
  onChange: (key: string, patch: Partial<ColumnSetting>) => void;
}

const SortableRow: React.FC<SortableRowProps> = ({setting, onChange}) => {
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
    background: isDragging ? 'var(--ant-color-primary-bg)' : 'transparent',
    marginBottom: 2,
  };

  return (
    <div ref={setNodeRef} style={style}>
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
        onChange={v => onChange(setting.key, {fixed: v === 'none' ? undefined : v as ColumnFixed})}
        style={{width: 82, flexShrink: 0}}
        options={[
          {value: 'none', label: '不固定'},
          {value: 'left', label: '← 左固定'},
          {value: 'right', label: '右固定 →'},
        ]}
      />
    </div>
  );
};

// ─── Column settings drawer ───────────────────────────────────────────────────

interface ColumnSettingsProps {
  open: boolean;
  settings: ColumnSetting[];
  onSave: (settings: ColumnSetting[]) => void;
  onClose: () => void;
  onReset: () => void;
}

const ColumnSettings: React.FC<ColumnSettingsProps> = ({
  open,
  settings: initialSettings,
  onSave,
  onClose,
  onReset,
}) => {
  const [items, setItems] = useState<ColumnSetting[]>(initialSettings);

  // Re-sync when drawer opens
  useEffect(() => {
    if (open) setItems(initialSettings);
  }, [open]); // intentionally not including initialSettings to avoid mid-edit reset

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
      title="列设置"
      width={320}
      open={open}
      onClose={onClose}
      styles={{body: {padding: '12px 16px'}}}
      footer={
        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
          <Button type="text" danger size="small" onClick={onReset}>
            重置默认
          </Button>
          <div style={{display: 'flex', gap: 8}}>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" onClick={() => {
              onSave(items);
              onClose();
            }}>
              保存
            </Button>
          </div>
        </div>
      }
    >
      {/* Header: select-all + stats */}
      <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8}}>
        <Checkbox
          indeterminate={indeterminate}
          checked={allChecked}
          onChange={e => setItems(prev => prev.map(i => ({...i, visible: e.target.checked})))}
        >
          全选
        </Checkbox>
        <Tag color="blue" style={{margin: 0}}>{visibleCount} / {items.length} 显示</Tag>
      </div>

      <div style={{display: 'flex', justifyContent: 'flex-end', marginBottom: 4}}>
        <span style={{fontSize: 11, color: '#999', marginRight: 8}}>固定</span>
      </div>

      <Divider style={{margin: '0 0 8px'}}/>

      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <SortableContext items={items.map(i => i.key)} strategy={verticalListSortingStrategy}>
          {items.map(item => (
            <SortableRow key={item.key} setting={item} onChange={handleChange}/>
          ))}
        </SortableContext>
      </DndContext>

      <div style={{marginTop: 12, padding: '8px 4px 0', borderTop: '1px solid rgba(0,0,0,0.06)'}}>
        <span style={{fontSize: 12, color: '#aaa'}}>
          💡 拖动 <HolderOutlined style={{fontSize: 11}}/> 可调整列顺序
        </span>
      </div>
    </Drawer>
  );
};

export default ColumnSettings;
