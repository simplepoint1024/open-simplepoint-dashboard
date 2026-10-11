import {Alert, DatePicker, InputNumber, Segmented, Typography} from 'antd';
import dayjs from 'dayjs';
import {useState} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {dueDuration, parseDueDuration, ordinaryDueDate, type DueDuration} from './dueDate';
import TableActions from '../Table/Actions';

/** Calendar date or duration from task creation; switching views never silently changes the XML. */
export default function DueDateValue({value, onChange, disabled}: {
  value?: string; onChange: (value?: string) => void; disabled?: boolean;
}) {
  const {t} = useI18n();
  const duration = value ? parseDueDuration(value) : undefined;
  const [mode, setMode] = useState(duration || value?.startsWith('P') ? 'duration' : 'date');
  const date = value && ordinaryDueDate(value) && dayjs(value).isValid() ? dayjs(value) : null;
  const visible = !value || (mode === 'duration' ? Boolean(duration) : Boolean(date));
  const units: Array<[keyof DueDuration, string]> = [
    ['days', t('workflow.designer.durationDays', '天')], ['hours', t('workflow.designer.durationHours', '小时')],
    ['minutes', t('workflow.designer.durationMinutes', '分钟')], ['seconds', t('workflow.designer.durationSeconds', '秒')],
  ];
  return <div style={{display: 'grid', gap: 8}}>
    <Segmented block disabled={disabled} value={mode} options={[
      {value: 'date', label: t('workflow.designer.dueAbsolute', '指定时间')},
      {value: 'duration', label: t('workflow.designer.dueRelative', '任务到达后')},
    ]} onChange={setMode}/>
    {!visible && <Alert type="info" showIcon title={t('workflow.editor.fixedPending', '原配置已保留，选择固定值后才会替换')}
      description={<Typography.Text code>{value}</Typography.Text>}/>}
    {mode === 'date' ? <DatePicker showTime disabled={disabled} style={{width: '100%'}} value={date}
      onChange={next => onChange(next?.toISOString())}/>
      : <div style={{display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8}}>
        {units.map(([unit, label]) => <label key={unit} style={{display: 'grid', gap: 4}}>
          <Typography.Text>{label}</Typography.Text>
          <InputNumber aria-label={label} disabled={disabled} min={0} max={1_000_000} precision={0}
            value={duration?.[unit]} style={{width: '100%'}}
            onChange={next => onChange(dueDuration({
              ...(duration ?? {days: 0, hours: 0, minutes: 0, seconds: 0}), [unit]: Number(next ?? 0),
            }))}/>
        </label>)}
      </div>}
    {value && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'clearDue', sort: 10,
      text: t('workflow.designer.clearDue', '清除到期设置'), type: 'link', size: 'small', disabled,
      onClick: () => onChange(undefined)}]}/>}
  </div>;
}
