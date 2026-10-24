import {useQueryClient} from '@tanstack/react-query';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useEffect, useMemo, useRef, useState} from 'react';
import {Alert, Modal, Tabs} from 'antd';
import ResourceDrawer from '@simplepoint/components/ResourceDrawer';
import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import EntitySelect from '@simplepoint/components/EntitySelect';
import SForm, {type SFormHandle} from '@simplepoint/components/SForm';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {createIcon} from '@simplepoint/shared/types/icon';
import {get, post} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {localizedTextSchema} from '@simplepoint/shared/i18n/localizedSchema';
import {resolveLocalizedText, type LocalizedText} from '@simplepoint/shared/i18n/localizedText';

type Directory = {id: string; parentId?: string | null; name: LocalizedText; icon?: string};
type Page = {id: string; directoryId?: string | null; name: LocalizedText};
type Configuration = {tenantParentCode?: string | null; name: LocalizedText; directories: Directory[]; pages: Page[]};
type View = {revision: number; pending: boolean; configuration: Configuration};
const canonical = (value: unknown) => JSON.stringify(value, (_key, child) => child && typeof child === 'object' && !Array.isArray(child)
  ? Object.fromEntries(Object.entries(child).sort(([a], [b]) => a.localeCompare(b))) : child);
type Editing = {kind: 'directory' | 'page'; value: Directory | Page; creating?: boolean};

/** One navigation draft and one durable save command; row editors never perform independent writes. */
export default function ApplicationNavigation({applicationId, onClose, onSaved}: {
  applicationId: string; onClose: () => void; onSaved: () => void;
}) {
  const {t, locale} = useI18n();
  const operation = useOperationScope(applicationId);
  const client = useQueryClient(), scope = useQueryScope();
  const committed = () => {void client.invalidateQueries({queryKey: scopedQueryKey(scope, [])}); onSaved();};
  const endpoint = `/app/applications/${encodeURIComponent(applicationId)}/navigation`;
  const query = useOwnedData<View>(['app-navigation', applicationId], ({signal}) => get(endpoint, undefined, {signal}), {retry: false});
  const [baseline, setBaseline] = useState<View>();
  const [configuration, setConfiguration] = useState<Configuration>();
  const [editing, setEditing] = useState<Editing>();
  const [error, setError] = useState('');
  const [discard, setDiscard] = useState(false);
  const basicForm = useRef<SFormHandle>(null), rowForm = useRef<SFormHandle>(null);
  useEffect(() => {
    if (!baseline && query.data) {setBaseline(query.data); setConfiguration(structuredClone(query.data.configuration));}
  }, [baseline, query.data]);
  const label = (name: LocalizedText) => resolveLocalizedText(name, locale).text;
  const dirty = !!baseline && canonical(configuration) !== canonical(baseline.configuration);
  const locked = operation.busy || !!editing;
  const close = () => {if (locked) return; if (dirty) setDiscard(true); else onClose();};
  const write = async (cancel = false) => {
    if (!configuration || !baseline) return;
    const controller = operation.begin(); if (!controller) return;
    setError('');
    try {
      const updated = await post<View>(endpoint + (cancel ? '/cancel' : ''), {
        expectedRevision: baseline.revision, configuration: baseline.pending ? baseline.configuration : configuration,
      }, {signal: controller.signal, notifyOnError: false});
      if (!operation.current(controller.signal)) return;
      setBaseline(updated); setConfiguration(structuredClone(updated.configuration)); committed();
    } catch (cause) {
      if (!operation.current(controller.signal)) return;
      setError(resolveApiErrorMessage(cause, t('app.navigation.failed', '目录同步未完成，请重试或取消待同步操作')));
      // The failed response may mean the remote write succeeded. Load the durable intent before allowing edits.
      const fresh = await query.refetch();
      if (operation.current(controller.signal) && fresh.data) {
        setBaseline(fresh.data); setConfiguration(structuredClone(fresh.data.configuration));
        if (!cancel && !fresh.data.pending && fresh.data.revision === baseline.revision + 1
          && canonical(fresh.data.configuration) === canonical(configuration)) {setError(''); committed();}
      }
    } finally {operation.finish(controller);}
  };
  const choices = useMemo(() => {
    if (!configuration) return [];
    const excluded = new Set(editing?.kind === 'directory' ? [editing.value.id] : []);
    let count: number;
    do {count = excluded.size; configuration.directories.forEach(item => {if (item.parentId && excluded.has(item.parentId)) excluded.add(item.id);});} while (count !== excluded.size);
    return [{const: '', title: t('app.navigation.root', '应用根目录')}, ...configuration.directories.filter(item => !excluded.has(item.id))
      .map(item => ({const: item.id, title: label(item.name)}))];
  }, [configuration, editing, locale, t]);
  const disabled = locked || !operation.selected || baseline?.pending || !!query.error;
  const edit = (kind: Editing['kind'], value: Directory | Page) => {if (!disabled) setEditing({kind, value: structuredClone(value)});};
  const rowActions = (kind: Editing['kind'], row: Directory | Page) => <TableActions selectedKeys={[row.id]} selectedRows={[row]} buttons={[
    {key: 'edit', sort: 0, icon: 'EditOutlined', text: t('app.navigation.edit', '编辑'), disabled, type: 'link'},
    ...(kind === 'directory' ? [{key: 'delete', sort: 1, icon: 'DeleteOutlined', text: t('app.navigation.delete', '删除'), danger: true, type: 'link' as const,
      disabled: disabled || configuration!.directories.some(item => item.parentId === row.id) || configuration!.pages.some(item => item.directoryId === row.id)}] : []),
  ]} onButtonEvents={{edit: () => edit(kind, row), delete: () => {
    if (!disabled) setConfiguration(current => current && ({...current, directories: current.directories.filter(item => item.id !== row.id)}));
  }}}/>;
  const parentName = (id?: string | null) => id ? label(configuration!.directories.find(item => item.id === id)!.name) : t('app.navigation.root', '应用根目录');
  return <ResourceDrawer open title={t('app.navigation.title', '应用目录与名称')} placement="right" defaultWidth={900} locked={locked} onClose={close}>
    {query.error && <Alert type="error" showIcon title={t('app.navigation.loadFailed', '目录配置加载失败')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('app.retry', '重试')}]}
        onButtonEvents={{retry: () => void query.refetch()}}/>}/>}
    {error && <Alert type="error" showIcon title={error}/>}
    {baseline?.pending && <Alert type="warning" showIcon title={t('app.navigation.pending', '有待同步的目录配置。可重试同步，或取消尚未生效的操作。')}/>}
    {configuration && <>
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'save', sort: 0, icon: 'SaveOutlined', type: 'primary', text: baseline?.pending ? t('app.retry', '重试') : t('app.navigation.save', '保存'), disabled: locked || !operation.selected || !!query.error},
        ...(baseline?.pending ? [{key: 'cancel', sort: 1, icon: 'CloseOutlined', text: t('app.navigation.cancelPending', '取消待同步操作'), disabled: locked}] : []),
      ]} onButtonEvents={{save: () => baseline?.pending ? void write() : basicForm.current?.submit(), cancel: () => void write(true)}}/>
      <Tabs items={[
        {key: 'general', label: t('app.navigation.general', '基础设置'), icon: createIcon('SettingOutlined'), forceRender: true, children: <>
          <SForm ref={basicForm} hideSubmit disabled={disabled} schema={{type: 'object', required: ['name'], properties: {
            name: localizedTextSchema(t('app.application.name', '应用名称'), 100),
          }}} formData={{name: configuration.name}} onChange={({formData}, changed) => {
            if (!disabled && changed && formData) setConfiguration(current => current && ({...current, name: formData.name}));
          }} onSubmit={() => void write()}/>
          <label htmlFor="application-parent">{t('app.navigation.mount', '挂载到租户目录')}</label>
          <EntitySelect id="application-parent" endpoint={endpoint + '/directories'} value={configuration.tenantParentCode} disabled={disabled} allowClear
            source={{key: `navigation:${applicationId}:${locale}`, minSearchLength: 0, pageSize: 50,
              searchParams: (keyword, page, size) => ({keyword, page, size}),
              option: row => ({value: String(row.code), label: resolveLocalizedText(row.nameLocalization as LocalizedText, locale, undefined, 'TENANT', String(row.name)).text}),
              resolve: async (ids, signal) => (await Promise.all(ids.map(async code => {
                const result = await get<{content: {code: string; name: string; nameLocalization?: LocalizedText}[]}>(endpoint + '/directories', {keyword: code, page: 0, size: 1}, {signal});
                const row = result.content.find(item => item.code === code);
                return row ? {value: row.code, label: resolveLocalizedText(row.nameLocalization, locale, undefined, 'TENANT', row.name).text} : undefined;
              }))).filter((item): item is {value: string; label: string} => !!item),
            }} onChange={value => {if (!disabled) setConfiguration(current => current && ({...current, tenantParentCode: value ?? null}));}}/>
        </>},
        {key: 'directories', label: t('app.navigation.directories', '应用内目录'), icon: createIcon('FolderOutlined'), children:
          <div style={{height: 440}}><DataTable<Directory> rowKey="id" dataSource={configuration.directories} pagination={{defaultPageSize: 50}}
            toolbarExtra={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'add', sort: 0, icon: 'PlusOutlined', text: t('app.navigation.add', '新增目录'), disabled: disabled || configuration.directories.length >= 99}]}
              onButtonEvents={{add: () => {if (!disabled) setEditing({kind: 'directory', creating: true, value: {id: crypto.randomUUID(), name: {defaultValue: '', defaultLocale: locale, translations: {}}, icon: 'FolderOutlined'}});}}}/>}
            columns={[
              {key: 'name', title: t('app.navigation.name', '名称'), render: (_, row) => label(row.name)},
              {key: 'parent', title: t('app.navigation.parent', '上级目录'), render: (_, row) => parentName(row.parentId)},
              {key: 'actions', title: t('app.application.actions', '操作'), render: (_, row) => rowActions('directory', row)},
            ]}/></div>},
        {key: 'pages', label: t('app.page.list', '页面'), icon: createIcon('FileOutlined'), children:
          <div style={{height: 440}}><DataTable<Page> rowKey="id" dataSource={configuration.pages} pagination={{defaultPageSize: 50}} columns={[
            {key: 'name', title: t('app.page.name', '页面名称'), render: (_, row) => label(row.name)},
            {key: 'parent', title: t('app.navigation.parent', '上级目录'), render: (_, row) => parentName(row.directoryId)},
            {key: 'actions', title: t('app.application.actions', '操作'), render: (_, row) => rowActions('page', row)},
          ]}/></div>},
      ]}/>
    </>}
    {editing && <ResourceDrawer open title={t('app.navigation.edit', '编辑')} placement="right" defaultWidth={560} onClose={() => setEditing(undefined)}>
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'apply', sort: 0, icon: 'CheckOutlined', text: t('app.navigation.apply', '应用'), type: 'primary'}]}
        onButtonEvents={{apply: () => rowForm.current?.submit()}}/>
      <SForm ref={rowForm} hideSubmit schema={{type: 'object', required: ['name'], properties: {
        name: localizedTextSchema(t('app.navigation.name', '名称'), 100),
        parent: {type: 'string', title: t('app.navigation.parent', '上级目录'), oneOf: choices},
        ...(editing.kind === 'directory' ? {icon: {type: 'string' as const, title: t('app.navigation.icon', '图标')}} : {}),
      }}} formData={{...editing.value, parent: (editing.kind === 'directory' ? (editing.value as Directory).parentId : (editing.value as Page).directoryId) ?? ''}}
        onSubmit={({formData}) => {
          if (!formData || !operation.isSelected() || operation.isBusy()) return;
          const value = {...editing.value, name: formData.name, ...(editing.kind === 'directory' ? {parentId: formData.parent || null, icon: formData.icon} : {directoryId: formData.parent || null})};
          setConfiguration(current => current && (editing.kind === 'directory' ? {...current, directories: editing.creating ? [value as Directory, ...current.directories]
            : current.directories.map(item => item.id === value.id ? value as Directory : item)} : {...current, pages: current.pages.map(item => item.id === value.id ? value as Page : item)}));
          setEditing(undefined);
        }}/>
    </ResourceDrawer>}
    <Modal open={discard && operation.selected} title={t('app.draft.discardTitle', '放弃未保存的修改？')} onCancel={() => setDiscard(false)} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'continue', sort: 0, text: t('app.draft.continue', '继续编辑')},
      {key: 'discard', sort: 1, text: t('app.draft.discard', '放弃修改'), danger: true},
    ]} onButtonEvents={{continue: () => setDiscard(false), discard: onClose}}/>}/>
  </ResourceDrawer>;
}
