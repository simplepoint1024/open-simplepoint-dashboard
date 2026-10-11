import {useMemo, useState} from 'react';
import {Drawer, Tabs, Tag, Typography} from 'antd';
import {useNavigate, useSearchParams} from 'react-router';
import {TableActions} from '@simplepoint/components/Table';
import type {SimpleTableColumnOverride} from '@simplepoint/components/SimpleTable/types';
import {get, useData} from '@simplepoint/shared/api/methods';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {personalApi, safePersonalPath, type PersonalMessage, type PersonalTodo} from '@simplepoint/shared/personal/models';
import {PersonalTable} from './PersonalTable';
import {usePersonalActions} from './usePersonalActions';
import {canPersonalAction} from './actions';
import {ErrorNotice, formatTime, Priority, Status, usePersonalLabels} from './shared';

type Entry = PersonalTodo | PersonalMessage;
type Kind = 'todos' | 'messages';
export function InboxDetail({kind, id, close, readOnly = false}: {kind: Kind; id: string; close: () => void; readOnly?: boolean}) {
  const {t} = usePersonalLabels();
  const navigate = useNavigate();
  const selected = usePageSelected();
  const query = useData(['personal', kind, 'detail', id], ({signal}) => get<Entry>(`${personalApi}/${kind}/${encodeURIComponent(id)}`, undefined, {signal}));
  const entry = query.data;
  const actions = usePersonalActions(kind);
  return <Drawer open={selected} title={kind === 'todos' ? t('personal.todoDetails', '待办详情') : t('personal.messageDetails', '消息详情')}
    size={600} onClose={close} closable={!actions.busy} keyboard={!actions.busy} mask={{closable: !actions.busy}}>
    <ErrorNotice error={query.error} retry={() => void query.refetch()} />
    <ErrorNotice error={actions.error} />
    {entry && <>
      <h2>{entry.title}</h2>
      <div className="sp-personal-detail-meta"><Priority value={entry.priority} />
        {'status' in entry && <Status status={entry.status} />}<Tag>{entry.source === 'MANUAL' ? t('personal.manual', '手工创建') : entry.source === 'PERSONAL_ITEMS' ? t('personal.items', '我的事项') : entry.source.toLowerCase() === 'workflow' ? t('personal.workflow', '工作流') : entry.source}</Tag>
      </div>
      <p>{t('personal.createdAt', '创建时间')}: {formatTime(entry.createdAt)}</p>
      {'dueAt' in entry && <p>{t('personal.dueAt', '截止时间')}: {formatTime(entry.dueAt)}</p>}
      {'completedAt' in entry && entry.completedAt && <p>{t('personal.completedAt', '完成时间')}: {formatTime(entry.completedAt)}</p>}
      <div className="sp-personal-description">{entry.content || '—'}</div>
      {kind === 'todos' && entry.source !== 'MANUAL' && <p>{t('personal.externalTodo', '业务待办需要进入来源系统处理，处理结果由来源系统同步。')}</p>}
      <TableActions buttons={readOnly ? [] : actions.buttons.filter(button => !['add', 'view', 'readAll'].includes(button.key) && canPersonalAction(kind, button.key, entry))}
        selectedKeys={[entry.id]} selectedRows={[entry]} onButtonEvents={actions.events} isButtonDisabled={actions.disabled} />
      {safePersonalPath(entry.linkPath) && <Typography.Link onClick={() => navigate(entry.linkPath!)}>{t('personal.goToSource', '去处理 / 查看来源')}</Typography.Link>}
    </>}
  </Drawer>;
}

function Inbox({kind}: {kind: Kind}) {
  const {t, statuses} = usePersonalLabels();
  const [params, setParams] = useSearchParams();
  const [view, setView] = useState(kind === 'todos' ? 'PENDING' : 'ALL');
  const [creating, setCreating] = useState(false);
  const open = (entry: Entry) => setParams({id: entry.id});
  const columns = useMemo<Record<string, SimpleTableColumnOverride<Entry>>>(() => ({
    title: {width: 320, render: (_, entry) => <Typography.Link className="sp-personal-title" onClick={() => open(entry)}>
      {'readAt' in entry && !entry.readAt ? <strong>● {entry.title}</strong> : entry.title}</Typography.Link>},
    priority: {render: value => <Priority value={value} />},
    source: {render: source => source === 'MANUAL' ? t('personal.manual', '手工创建') : source === 'PERSONAL_ITEMS' ? t('personal.items', '我的事项') : typeof source === 'string' && source.toLowerCase() === 'workflow' ? t('personal.workflow', '工作流') : source},
    [kind === 'todos' ? 'dueAt' : 'createdAt']: {render: (_, entry) => formatTime('dueAt' in entry ? entry.dueAt : entry.createdAt)},
  }), [kind, t, setParams]);
  return <main className="sp-personal" tabIndex={0}>
    <Tabs activeKey={view} onChange={value => {if (!creating) setView(value);}} items={kind === 'todos'
      ? ['PENDING', 'DONE', 'CANCELLED'].map(key => ({key, label: statuses[key as keyof typeof statuses]}))
      : [{key: 'ALL', label: t('personal.inbox', '收件箱')}, {key: 'UNREAD', label: t('personal.unreadMessages', '未读消息')}, {key: 'ARCHIVED', label: t('personal.archived', '已归档')}]} />
    <PersonalTable<Entry> key={view} name={kind} view={view} columns={columns} onOpen={open}
      open={creating} onOpenChange={setCreating} onSaved={() => setView('PENDING')} />
    {params.get('id') && !creating && <InboxDetail key={params.get('id')} kind={kind} id={params.get('id')!} close={() => setParams({})} />}
  </main>;
}
export function PersonalInbox({kind}: {kind: Kind}) {
  const scope = useQueryScope();
  return <Inbox kind={kind} key={JSON.stringify(scope)} />;
}
