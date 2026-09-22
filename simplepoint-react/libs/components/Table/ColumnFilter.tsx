import {useEffect, useState} from 'react';
import {Alert, Button, Select, Space} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import QueryValue from './QueryValue';
import {arrayOps, nullOps, operatorLabels, type Scalar} from './query';
import {defaultColumnOperator} from './filters';

export type ColumnFilterType = 'string' | 'number' | 'integer' | 'boolean' | 'date' | 'unknown';
export interface ColumnFilterProps {
  initialOp?: string;
  initialText?: string;
  initialValue?: Scalar | Scalar[];
  onChange?: (op: string, text: string, value?: Scalar | Scalar[]) => void;
  options?: Array<{value: string; label: string}>;
  columnType?: ColumnFilterType;
  columnLabel?: string;
  operators?: string[];
  javaType?: string;
  format?: string;
}

export default function ColumnFilter({initialOp, initialText = '', initialValue, onChange, options,
  columnType = 'unknown', columnLabel, operators, javaType, format}: ColumnFilterProps) {
  const {t} = useI18n();
  const allowed = operators ?? Object.keys(operatorLabels).filter(op =>
    (columnType === 'string' || columnType === 'unknown' || !op.includes('like'))
    && (columnType !== 'boolean' || (!op.includes('between') && !op.startsWith('than:'))));
  const fallback = options ? 'in' : defaultColumnOperator(columnType);
  const defaultOp = allowed.includes(fallback) ? fallback : allowed[0];
  const activeOp = initialOp && allowed.includes(initialOp) ? initialOp : defaultOp;
  const initial = initialValue ?? (initialText ? arrayOps.includes(activeOp) ? initialText.split(',')
    : columnType === 'boolean' ? initialText === 'true' : initialText : undefined);
  const [op, setOp] = useState(activeOp);
  const [value, setValue] = useState<Scalar | Scalar[] | undefined>(initial);
  const [error, setError] = useState('');
  useEffect(() => {setOp(activeOp); setValue(initial); setError('');}, [activeOp, initialText, initialValue]);
  const apply = () => {
    if (!nullOps.includes(op)) {
      const values = Array.isArray(value) ? value : [value];
      if (!values.length || values.some(v => v === undefined || v === '') || (op.includes('between') && values.length !== 2)) {
        setError(t('table.query.valueRequired', '请完整填写筛选值')); return;
      }
    }
    onChange?.(op, nullOps.includes(op) ? '' : Array.isArray(value) ? value.join(',') : String(value ?? ''), value);
  };
  return <div style={{width: 320, padding: 14, display: 'flex', flexDirection: 'column', gap: 10}}>
    <span>{t('table.filter.title', '列筛选')}{columnLabel ? `：${columnLabel}` : ''}</span>
    <Select value={op} options={allowed.map(key => ({value: key, label: t(`table.query.operator.${key}`, operatorLabels[key])}))}
      onChange={next => {setOp(next); setValue(undefined); setError('');}} />
    <QueryValue op={op} value={value} onChange={setValue} type={columnType} javaType={javaType} format={format} options={options} />
    {columnType === 'date' && <small>{t('table.query.timezone', '日期按当前浏览器时区输入')}：{Intl.DateTimeFormat().resolvedOptions().timeZone}</small>}
    {error && <Alert type="error" title={error} />}
    <Space style={{justifyContent: 'flex-end'}}>
      <Button size="small" onClick={() => onChange?.(defaultOp, '')}>{t('table.filter.reset', '重置')}</Button>
      <Button size="small" type="primary" onClick={apply}>{t('table.filter.apply', '应用')}</Button>
    </Space>
  </div>;
}
