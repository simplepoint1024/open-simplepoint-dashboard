import {useEffect, useRef, useState} from 'react';
import {Alert, App, Button, Divider, Drawer, Input, Select, Space, Tag, Tooltip, Typography} from 'antd';
import {FilterOutlined} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {get, put} from '@simplepoint/shared/api/methods';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import ConditionEditor from './ConditionEditor';
import {encodeDocument, operatorLabels, readDocument, filterNode, summarizeNode, validateNode, validateView,
  type QueryContract, type QueryNode, type SavedView} from './query';

type Props = {contract: QueryContract; properties: Record<string, any>; filters: Record<string, string>;
  sort?: string | string[]; storageKey?: string; scopeHint?: string;
  onApply: (filters: Record<string, string>, sort?: string | string[]) => void};

/** On-demand preference loading: no extra request in the menu's critical loading path. */
export default function QueryTools({contract, properties, filters, sort, storageKey, scopeHint, onApply}: Props) {
  const {t} = useI18n();
  const {message} = App.useApp();
  const selected = usePageSelected();
  const scope = useQueryScope();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<QueryNode>({op: 'and', children: []});
  const [sorts, setSorts] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [views, setViews] = useState<SavedView[]>([]);
  const [viewName, setViewName] = useState('');
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [retry, setRetry] = useState(0);
  const epoch = useRef(0);
  const key = `sp.table.views.${storageKey ?? ''}.${JSON.stringify(scope)}`;
  const [url, setUrl] = useState('');
  useEffect(() => {epoch.current++; setOpen(false); setLoaded(false); setViews([]);}, [key]);
  useEffect(() => {
    if (!open || !storageKey) return;
    const abort = new AbortController();
    setLoaded(false); setError('');
    const load = async () => {
      // SHA-256 keeps arbitrarily long resource/context names below the DB's 200-character key limit.
      const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(key));
      const hash = Array.from(new Uint8Array(digest), n => n.toString(16).padStart(2, '0')).join('');
      const target = `/common/users/preferences?key=sp.table.views.v1.${hash}`;
      if (abort.signal.aborted) return;
      setUrl(target);
      const raw = await get<string | undefined>(target, undefined, {signal: abort.signal});
      if (abort.signal.aborted) return;
      const document = raw ? JSON.parse(raw) : {version: 1, views: []};
      if (document.version !== 1 || !Array.isArray(document.views) || document.views.length > 10
        || document.views.some((v: any) => !v || typeof v.name !== 'string' || v.name.length > 40 || !v.filters)) throw Error('invalid');
      setViews(document.views); setLoaded(true);
    };
    void load().catch(() => {if (!abort.signal.aborted) setError(t('table.query.loadViewsFailed', '视图加载失败，请重试；为避免覆盖已有设置，暂时不能保存。'));});
    return () => abort.abort();
  }, [open, key, retry, storageKey, t]);
  const applyDraft = () => {
    try {
      const next = {...filters};
      if (draft.children?.length === 0) delete next.$query;
      else {validateNode(draft, contract); next.$query = encodeDocument(draft);}
      validateView({name: 'draft', filters: next, sort: sorts}, contract);
      onApply(next, sorts.length ? sorts : undefined); setOpen(false);
    } catch (e) {setError((e as Error).message);}
  };
  const save = async (next: SavedView[]) => {
    if (!loaded || busy) return;
    const current = epoch.current;
    setBusy(true);
    try {
      const value = JSON.stringify({version: 1, views: next});
      if (value.length > 16000) throw Error(t('table.query.viewsTooLarge', '保存的视图内容过多'));
      await put(url, {value});
      if (current === epoch.current) {setViews(next); setError(''); message.success(t('table.query.saved', '视图已保存'));}
    } catch (e) {if (current === epoch.current) setError(t('table.query.saveFailed', '视图保存失败，请重试'));}
    finally {setBusy(false);}
  };
  return <>
    <Tooltip title={t('table.query.title', '筛选与视图')}>
    <Button type="text" className="sp-table-icon-button" aria-label={t('table.query.title', '筛选与视图')}
      icon={<FilterOutlined />} onClick={() => {
      setDraft(readDocument(filters.$query) ?? {op: 'and', children: []});
      setSorts(sort ? Array.isArray(sort) ? sort : [sort] : []); setOpen(true);
    }} />
    </Tooltip>
    {Object.entries(filters).map(([field, value]) => {
      const node = filterNode(field, value);
      const summary = node ? summarizeNode(node, properties, op => t(`table.query.operator.${op}`, operatorLabels[op])) : value;
      return <Tag key={field} closable style={{maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis'}}
        title={summary} onClose={() => {const next = {...filters}; delete next[field]; onApply(next, sort);}}>{summary}</Tag>;
    })}
    <Drawer rootClassName="sp-query-drawer" title={t('table.query.title', '筛选与视图')} width={720} open={open && selected} onClose={() => setOpen(false)} destroyOnHidden
      footer={<Space><Button onClick={() => setDraft({op: 'and', children: []})}>{t('table.query.clearAdvanced', '清空高级条件')}</Button>
        <Button type="primary" onClick={applyDraft}>{t('table.filter.apply', '应用')}</Button></Space>}>
      <Typography.Paragraph type="secondary">{scopeHint ?? t('table.query.scopeHint', '高级条件与表头筛选同时生效；权限和归属限制始终由服务端执行。日期按浏览器时区输入。')}</Typography.Paragraph>
      {error && <Alert type="error" title={error} action={!loaded ? <Button onClick={() => setRetry(v => v + 1)}>{t('table.retry', '重试')}</Button> : undefined} />}
      <ConditionEditor node={draft} onChange={setDraft} contract={contract} properties={properties} />
      <Divider>{t('table.query.sort', '排序优先级（从上到下）')}</Divider>
      {sorts.map((sort, index) => {
        const [field, direction] = sort.split(',');
        return <Space key={index} style={{display: 'flex', marginBottom: 8}}>
          <Select style={{width: 240}} value={field} options={Object.entries(contract.fields)
            .filter(([key, cap]) => cap.sortable && properties[key] && (key === field || !sorts.some(s => s.split(',')[0] === key)))
            .map(([value]) => ({value, label: properties[value]?.title ?? value}))}
            onChange={value => setSorts(sorts.map((s, i) => i === index ? `${value},${direction}` : s))} />
          <Select value={direction} options={[{value: 'asc', label: t('table.query.asc', '升序')}, {value: 'desc', label: t('table.query.desc', '降序')}]}
            onChange={value => setSorts(sorts.map((s, i) => i === index ? `${field},${value}` : s))} />
          <Button onClick={() => setSorts(sorts.filter((_, i) => i !== index))}>×</Button>
        </Space>;
      })}
      <Button disabled={sorts.length >= contract.maxSort} onClick={() => {
        const field = Object.keys(contract.fields).find(f => contract.fields[f].sortable && properties[f] && !sorts.some(s => s.split(',')[0] === f));
        if (field) setSorts([...sorts, `${field},asc`]);
      }}>{t('table.query.addSort', '添加排序')}</Button>
      {storageKey && <><Divider>{t('table.query.views', '我的视图')}</Divider>
        <Space.Compact style={{width: '100%'}}><Input value={viewName} maxLength={40} onChange={e => setViewName(e.target.value)}
          placeholder={t('table.query.viewName', '视图名称（保存当前已应用的筛选和排序）')} />
          <Button disabled={!loaded || busy || !viewName.trim() || views.length >= 10 || views.some(v => v.name === viewName.trim())}
            onClick={() => {try {const view = {name: viewName.trim(), filters, sort}; validateView(view, contract); void save([...views, view]);}
              catch (e) {setError((e as Error).message);}}}>{t('table.query.save', '保存当前视图')}</Button></Space.Compact>
        {views.map((view, index) => <Space key={index} style={{display: 'flex', marginTop: 8}}>
          <Button onClick={() => {try {validateView(view, contract); onApply(view.filters, view.sort); setOpen(false);}
            catch (e) {setError((e as Error).message);}}}>{view.name}</Button>
          <Button disabled={busy} onClick={() => void save(views.filter((_, i) => i !== index))}>{t('table.query.deleteView', '删除视图')}</Button>
        </Space>)}
      </>}
    </Drawer>
  </>;
}
