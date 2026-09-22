import {useCallback} from 'react';
import {App} from 'antd';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTableColumnOverride, SimpleTablePageRequest} from '@simplepoint/components/SimpleTable/types';
import {get, post, put} from '@simplepoint/shared/api/methods';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {personalApi, type PersonalItem} from '@simplepoint/shared/personal/models';
import {canPersonalAction, type PersonalKind, type PersonalRecord} from './actions';
import {usePersonalActions} from './usePersonalActions';
import {usePersonalLabels, usePersonalWrite, ErrorNotice} from './shared';

const namespaces = ['personal', 'common', 'table'];
interface Props<T extends PersonalRecord> {
  name: PersonalKind;
  view: string;
  onOpen: (record: T) => void;
  editing?: PersonalItem;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onEdit?: (record?: PersonalItem) => void;
  onSaved: (record: T) => void;
  columns: Record<string, SimpleTableColumnOverride<T>>;
}

/** Resource-style annotation-driven SimpleTable/SForm; only domain adapters differ. */
export function PersonalTable<T extends PersonalRecord>({name, view, onOpen, editing, open, onOpenChange, onEdit, onSaved, columns}: Props<T>) {
  const {t, priorities} = usePersonalLabels();
  const {message} = App.useApp();
  const write = usePersonalWrite();
  const actions = usePersonalActions(name, {view: record => onOpen(record as T)});
  const loadPage = useCallback(async ({page, size, filters, sort, signal}: SimpleTablePageRequest) => normalizePage(
    await get<RawPage<T>>(`${personalApi}/${name}`, {
      page, size, filters: JSON.stringify(filters), sort,
      ...(name === 'items' ? {view} : name === 'todos' ? {status: view}
        : {unreadOnly: view === 'UNREAD', archived: view === 'ARCHIVED'}),
    }, {signal})), [name, view]);
  const priorityLabels = priorities.join('|');
  const formSchemaTransform = useCallback((schema: any) => {
    const next = structuredClone(schema);
    const fields = name === 'items' ? ['title', 'description', 'category', 'priority', 'startAt', 'dueAt', 'pinned', 'checklist']
      : ['title', 'content', 'priority', 'dueAt'];
    next.properties = Object.fromEntries(Object.entries(next.properties ?? {}).filter(([key]) => fields.includes(key)));
    next.required = ['title'];
    next.properties.title.pattern = '\\S';
    next.properties.priority = {...next.properties.priority, type: 'integer',
      oneOf: priorityLabels.split('|').map((title, value) => ({const: value, title}))};
    if (name === 'items') next.properties.checklist = {
      type: 'array', title: t('personal.checklist', '子清单'), maxItems: 50,
      items: {type: 'object', required: ['title'], properties: {
        id: {type: 'string'}, title: {type: 'string', title: t('personal.subtaskTitle', '子项标题'), minLength: 1, maxLength: 200, pattern: '\\S'},
        done: {type: 'boolean', title: t('personal.completeSubtask', '完成子项'), default: false},
      }},
    };
    return next;
  }, [name, priorityLabels, t]);
  return <section className="sp-personal-table">
    <ErrorNotice error={actions.error} />
    <SimpleTable<T> name={`personal.${name}`} baseUrl={`${personalApi}/${name}`} i18nNamespaces={namespaces}
      loadPage={loadPage} schemaStaleTime={60_000} queryContext={view} columnOverrides={columns} confirmDiscard
      drawerTitle={name === 'items' ? editing ? t('personal.editItem', '编辑事项') : t('personal.createItem', '新建事项') : t('personal.createTodo', '新建待办')}
      drawerOpen={open} onDrawerOpenChange={onOpenChange}
      editingRecord={editing ? {title: editing.title, description: editing.description, category: editing.category,
        priority: editing.priority, pinned: editing.pinned, startAt: editing.startAt || undefined, dueAt: editing.dueAt || undefined, checklist: editing.checklist} : null} onEditingRecordChange={record => onEdit?.(record ?? undefined)}
      initialValues={name === 'items' ? {priority: 1, pinned: false, checklist: []} : {priority: 1}}
      customButtonEvents={{...actions.events,
        add: () => {onEdit?.(undefined); onOpenChange(true);},
        edit: (_keys, rows) => {if (rows[0] && canPersonalAction(name, 'edit', rows[0])) {onEdit?.(rows[0] as PersonalItem); onOpenChange(true);}},
      }}
      isButtonDisabled={(button, keys, rows) => write.busy || actions.disabled(button, keys, rows)}
      onRowDoubleClick={record => {if (!write.busy && actions.buttons.some(button => button.key === 'view')) onOpen(record);}}
      formSchemaTransform={formSchemaTransform}
      formUiSchema={{checklist: {items: {id: {'ui:widget': 'hidden'}}}, 'ui:submitButtonOptions': {submitText: t('personal.save', '保存')}}}
      formCustomValidate={(data, errors) => {
        if (data?.startAt && data?.dueAt && new Date(data.dueAt) < new Date(data.startAt)) errors.dueAt?.addError(t('personal.invalidDates', '截止时间不能早于开始时间'));
        return errors;
      }}
      onSubmit={async (_action, data) => {
        const body = name === 'items' ? {title: data.title, description: data.description, category: data.category,
          priority: data.priority, pinned: data.pinned ?? false, startAt: data.startAt || null, dueAt: data.dueAt || null,
          version: editing?.version, checklist: (data.checklist ?? []).map((entry: {id?: string; title: string; done?: boolean}) =>
            ({id: entry.id || crypto.randomUUID(), title: entry.title, done: entry.done ?? false}))}
          : {title: data.title, content: data.content, priority: data.priority, dueAt: data.dueAt || null};
        const result = await write.run(signal => editing
          ? put<T>(`${personalApi}/items/${encodeURIComponent(editing.id)}`, body, {signal})
          : post<T>(`${personalApi}/${name}`, body, {signal}), true);
        if (!result) throw new Error(t('personal.failed', '操作失败，请重试'));
        return result;
      }}
      afterSubmit={({result}) => {message.success(t('personal.saved', '已保存')); onSaved(result as T);}}
      submitRefreshTargets={{page: true, schema: false}} deleteRefreshTargets={{page: true, schema: false}} />
  </section>;
}
