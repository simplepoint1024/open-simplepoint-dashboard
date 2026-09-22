import {useState} from 'react';
import {Alert, App, Button, Input, Modal, Select, Space} from 'antd';
import {ArrowDownOutlined, ArrowUpOutlined, DeleteOutlined, PlusOutlined} from '@ant-design/icons';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@/layouts/i18n/useI18n';
import {MAX_APPS, MAX_GROUPS, parsePreferences, type HomeGroup, type HomePreferences} from './preferences';

export function HomeEditor({initial, options, saving, onSave, onClose}: {
  initial: HomePreferences; options: {label: string; value: string}[]; saving: boolean;
  onSave: (value: HomePreferences) => Promise<boolean>; onClose: () => void;
}) {
  const {t} = useI18n();
  const {modal} = App.useApp();
  const active = usePageSelected();
  const [draft, setDraft] = useState<HomePreferences>(() => structuredClone(initial));
  const [error, setError] = useState('');
  const dirty = JSON.stringify(initial) !== JSON.stringify(draft);
  usePageRetention(!dirty && !saving, dirty || saving);
  const updateGroup = (id: string, patch: Partial<HomeGroup>) => setDraft(old => ({...old, groups: old.groups.map(g => g.id === id ? {...g, ...patch} : g)}));
  const move = (index: number, direction: number) => setDraft(old => {
    const groups = [...old.groups];
    [groups[index], groups[index + direction]] = [groups[index + direction], groups[index]];
    return {...old, groups};
  });
  const close = () => {
    if (saving) return;
    if (!dirty) onClose();
    else modal.confirm({title: t('home.discard', '放弃尚未保存的首页设置？'), onOk: onClose});
  };
  const zones = [...new Set([Intl.DateTimeFormat().resolvedOptions().timeZone,
    'Asia/Shanghai', 'Asia/Hong_Kong', 'Asia/Tokyo', 'Asia/Singapore', 'Asia/Kolkata', 'Europe/London', 'Europe/Paris',
    'America/New_York', 'America/Chicago', 'America/Los_Angeles', 'Australia/Sydney', 'UTC', draft.timeZone].filter(Boolean))];
  return <Modal open={active} title={t('home.customize', '自定义首页')} width={760} onCancel={close}
    maskClosable={!dirty && !saving} closable={!saving} keyboard={!saving}
    footer={<Space><Button disabled={saving} onClick={close}>{t('action.cancel', '取消')}</Button>
      <Button type="primary" loading={saving} onClick={async () => {
        setError('');
        try {
          let parsed: HomePreferences;
          try { parsed = parsePreferences(draft); } catch { setError(t('home.invalid', '请填写分组名称，并检查地区和时区设置。')); return; }
          if (await onSave(parsed)) onClose();
        } catch (cause) { setError(resolveApiErrorMessage(cause, t('home.saveError', '保存失败，设置已保留，请重试。'))); }
      }}>{t('action.save', '保存')}</Button></Space>}>
    <div className="sp-home-editor">
      {error && <Alert type="error" showIcon title={error} />}
      <p className="sp-home-muted">{t('home.editorHint', '设置保存在当前账号和工作空间中。仅显示当前角色有权限的应用。')}</p>
      <fieldset disabled={saving} className="sp-home-fieldset">
        {draft.groups.map((group, index) => <section className="sp-home-edit-group" key={group.id}>
          <div className="sp-home-group-tools">
            <Input aria-label={t('home.groupName', '分组名称')} value={group.name} maxLength={40}
              placeholder={t('home.groupName', '分组名称')} onChange={e => updateGroup(group.id, {name: e.target.value})} disabled={saving} />
            <Button icon={<ArrowUpOutlined />} aria-label={t('home.moveUp', '上移分组')} disabled={saving || index === 0} onClick={() => move(index, -1)} />
            <Button icon={<ArrowDownOutlined />} aria-label={t('home.moveDown', '下移分组')} disabled={saving || index === draft.groups.length - 1} onClick={() => move(index, 1)} />
            <Button danger icon={<DeleteOutlined />} aria-label={t('home.deleteGroup', '删除分组')} disabled={saving} onClick={() => {
              modal.confirm({title: t('home.deleteConfirm', '删除此分组及其快捷入口？'), onOk: () => setDraft(old => ({...old, groups: old.groups.filter(g => g.id !== group.id)}))});
            }} />
          </div>
          <Select mode="multiple" showSearch={{optionFilterProp: 'label'}} aria-label={t('home.selectApps', '选择常用应用')}
            placeholder={t('home.selectApps', '选择常用应用')} options={options} disabled={saving}
            maxCount={Math.max(0, MAX_APPS - group.paths.filter(path => !options.some(o => o.value === path)).length)}
            value={group.paths.filter(path => options.some(o => o.value === path))}
            onChange={paths => updateGroup(group.id, {paths: [...group.paths.filter(path => !options.some(o => o.value === path)), ...paths]})} />
        </section>)}
        <Button block type="dashed" icon={<PlusOutlined />} disabled={saving || draft.groups.length >= MAX_GROUPS}
          onClick={() => setDraft(old => ({...old, groups: [...old.groups, {id: crypto.randomUUID(), name: t('home.newGroup', '新分组'), paths: []}]}))}>
          {t('home.addGroup', '添加分组')}</Button>
        <div className="sp-home-location-fields">
          <label>{t('home.region', '地区')}<Input value={draft.region} maxLength={60} disabled={saving}
            placeholder={t('home.regionAuto', '留空时使用浏览器地区')} onChange={e => setDraft(old => ({...old, region: e.target.value}))} /></label>
          <label>{t('home.timeZone', '时区')}<Select showSearch allowClear disabled={saving} value={draft.timeZone || undefined}
            placeholder={t('home.timeZoneAuto', '跟随设备时区')} options={zones.map(value => ({label: value, value}))}
            onChange={value => setDraft(old => ({...old, timeZone: value || ''}))} /></label>
        </div>
      </fieldset>
    </div>
  </Modal>;
}
