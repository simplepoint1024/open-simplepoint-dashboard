import {useState} from 'react';
import {Button, Checkbox, Drawer, Input, Pagination, Space, Spin} from 'antd';
import {TableActions} from '@simplepoint/components/Table';
import {usePersonalActions} from './usePersonalActions';
import {canPersonalAction} from './actions';
import {get, post, useData} from '@simplepoint/shared/api/methods';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {usePageSelected, usePageRetention} from '@simplepoint/shared/page/hooks';
import {personalApi, type PersonalEvent, type PersonalItem} from '@simplepoint/shared/personal/models';
import {ErrorNotice, formatTime, Priority, Status, usePersonalLabels, usePersonalWrite} from './shared';

export function ItemDetail({id, trashItem, onClose, onEdit}: {id: string; trashItem?: PersonalItem; onClose: () => void; onEdit: (item: PersonalItem) => void}) {
  const {t, actions} = usePersonalLabels();
  const selected = usePageSelected();
  const [note, setNote] = useState('');
  const [page, setPage] = useState(0);
  const write = usePersonalWrite();
  const query = useData(['personal', 'item', id], ({signal}) => get<PersonalItem>(`${personalApi}/items/${encodeURIComponent(id)}`, undefined, {signal}), {enabled: !trashItem});
  const history = useData(['personal', 'history', id, page], async ({signal}) => normalizePage(await get<RawPage<PersonalEvent>>(
    `${personalApi}/items/${encodeURIComponent(id)}/history`, {page, size: 10}, {signal})));
  const item = trashItem || query.data;
  const operations = usePersonalActions('items', {edit: record => onEdit(record as PersonalItem), changed: key => {if (key === 'delete' || key === 'RESTORE') onClose();}});
  usePageRetention(!note.trim() && !write.busy, !!note.trim() || write.busy);
  async function action(name: string) {
    if (!item) return;
    const result = await write.run(signal => post<PersonalItem>(`${personalApi}/items/${encodeURIComponent(id)}/actions`,
      {version: item.version, action: name, comment: note}, {signal}));
    if (result) { if (name === 'COMMENT') setNote(''); if (['DELETE', 'RESTORE'].includes(name)) onClose(); }
  }
  return <Drawer open={selected} title={t('personal.itemDetails', '事项详情')} size={680}
    onClose={onClose} closable={!note.trim() && !write.busy && !operations.busy} mask={{closable: !note.trim() && !write.busy && !operations.busy}} keyboard={!note.trim() && !write.busy && !operations.busy}>
    <ErrorNotice error={!trashItem && query.error} retry={() => void query.refetch()} />
    <ErrorNotice error={write.error} retry={() => void query.refetch()} />
    <Spin spinning={!trashItem && query.isLoading}>
      {item && <>
        <h2>{item.title}</h2>
        <div className="sp-personal-detail-meta"><Status status={item.status} /><Priority value={item.priority} /><span>{item.category}</span></div>
        <ErrorNotice error={operations.error} />
        <TableActions buttons={operations.buttons.filter(button => !['add', 'view'].includes(button.key) && canPersonalAction('items', button.key, item))}
          selectedKeys={[item.id]} selectedRows={[item]} onButtonEvents={operations.events}
          isButtonDisabled={(button, keys, rows) => !!note.trim() || write.busy || operations.disabled(button, keys, rows)} />
        <p>{t('personal.startAt', '开始时间')}: {formatTime(item.startAt)}</p>
        <p>{t('personal.dueAt', '截止时间')}: {formatTime(item.dueAt)}</p>
        <p>{t('personal.updatedAt', '更新时间')}: {formatTime(item.updatedAt)}</p>
        {item.completedAt && <p>{t('personal.completedAt', '完成时间')}: {formatTime(item.completedAt)}</p>}
        <div className="sp-personal-description">{item.description || '—'}</div>
        {!!item.checklist.length && <><h3>{t('personal.checklist', '子清单')}</h3>
          <div className="sp-personal-checklist">{item.checklist.map(entry => <Checkbox key={entry.id} checked={entry.done} disabled>{entry.title}</Checkbox>)}</div></>}
        {!item.deletedAt && <><Input.TextArea value={note} onChange={event => setNote(event.target.value)} maxLength={2000} rows={3} disabled={write.busy}
          aria-label={t('personal.comment', '添加备注')} placeholder={t('personal.notePlaceholder', '补充进展或备注，保存后将保留在操作记录中')} />
          <Space style={{marginTop: 12}}><Button type="primary" disabled={!note.trim()} loading={write.busy} onClick={() => void action('COMMENT')}>{actions.COMMENT}</Button>
            {!!note && <Button disabled={write.busy} onClick={() => setNote('')}>{t('personal.clearNote', '清空备注')}</Button>}</Space></>}
        <section className="sp-personal-history"><h3>{t('personal.history', '操作记录')}</h3>
          <ErrorNotice error={history.error} retry={() => void history.refetch()} />
          <Spin spinning={history.isLoading}>{history.data?.content.map(entry => <div className="sp-personal-history-entry" key={entry.id}>
            <Space><strong>{actions[entry.action as keyof typeof actions] || entry.action}</strong><span>{formatTime(entry.createdAt)}</span></Space>
            {entry.detail && <div className="sp-personal-description">{entry.detail}</div>}
          </div>)}</Spin>
          <Pagination size="small" current={page + 1} pageSize={10} total={history.data?.page.totalElements || 0} onChange={value => setPage(value - 1)} showSizeChanger={false} hideOnSinglePage />
        </section>
      </>}
    </Spin>
  </Drawer>;
}
