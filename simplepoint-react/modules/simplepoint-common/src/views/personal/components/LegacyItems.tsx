import {useMemo, useState} from 'react';
import {Space, Tabs, Tag, Typography} from 'antd';
import {useSearchParams} from 'react-router';
import type {SimpleTableColumnOverride} from '@simplepoint/components/SimpleTable/types';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import type {PersonalItem} from '@simplepoint/shared/personal/models';
import {PersonalTable} from '../components/PersonalTable';
import {ItemDetail} from '../components/ItemDetail';
import {formatTime, Priority, Status, usePersonalLabels} from '../components/shared';

function Items() {
  const {t} = usePersonalLabels();
  const [params, setParams] = useSearchParams();
  const [view, setView] = useState(() => params.get('view') === 'OVERDUE' ? 'OVERDUE' : 'ACTIVE');
  const [editing, setEditing] = useState<PersonalItem>();
  const [formOpen, setFormOpen] = useState(false);
  const [trashItem, setTrashItem] = useState<PersonalItem>();
  const open = (item: PersonalItem) => {setTrashItem(item.deletedAt ? item : undefined); setParams({legacy: '1', item: item.id});};
  const columns = useMemo<Record<string, SimpleTableColumnOverride<PersonalItem>>>(() => ({
    title: {width: 310, render: (_, item) => <Space>
      {item.pinned && <Tag>{t('personal.pinned', '置顶')}</Tag>}
      <Typography.Link className="sp-personal-title" onClick={() => open(item)}>{item.title}</Typography.Link>
    </Space>},
    status: {render: status => <Status status={status} />},
    priority: {render: priority => <Priority value={priority} />},
    dueAt: {render: (_, item) => <span className={item.dueAt && new Date(item.dueAt).getTime() < Date.now() && ['TODO', 'IN_PROGRESS'].includes(item.status) ? 'sp-personal-due-over' : ''}>{formatTime(item.dueAt)}</span>},
    checklist: {sorter: false, filterDropdown: undefined, filterIcon: undefined,
      render: (_, item) => item.checklist.length ? `${item.checklist.filter(entry => entry.done).length}/${item.checklist.length}` : '—'},
  }), [t, setParams]);
  return <main className="sp-personal" tabIndex={0}>
    <Tabs activeKey={view} onChange={value => {if (!formOpen) setView(value);}} items={[
      {key: 'ACTIVE', label: t('personal.active', '事项列表')}, {key: 'OVERDUE', label: t('personal.overdue', '逾期事项')},
      {key: 'ARCHIVED', label: t('personal.archived', '已归档')}, {key: 'TRASH', label: t('personal.trash', '回收站')},
    ]} />
    <PersonalTable<PersonalItem> key={view} name="items" view={view} columns={columns} onOpen={open}
      editing={editing} onEdit={setEditing} open={formOpen} onOpenChange={setFormOpen}
      onSaved={item => {setEditing(undefined); setTrashItem(undefined); setParams({legacy: '1', item: item.id});}} />
    {params.get('item') && !formOpen && <ItemDetail key={params.get('item')} id={params.get('item')!} trashItem={trashItem}
      onClose={() => setParams({legacy: '1'})} onEdit={item => {setEditing(item); setFormOpen(true);}} />}
  </main>;
}
export default function LegacyPersonalItems() {
  const scope = useQueryScope();
  return <Items key={JSON.stringify(scope)} />;
}
