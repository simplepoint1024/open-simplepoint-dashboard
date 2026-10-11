import {DatePicker, Input, InputNumber, Select, Space} from 'antd';
import dayjs from 'dayjs';
import {arrayOps, nullOps, type Scalar} from './query';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {isNumericQueryType, queryScalar} from './queryValue';

export interface QueryValueProps {
  op: string;
  value?: Scalar | Scalar[];
  onChange: (value?: Scalar | Scalar[]) => void;
  type?: string;
  javaType?: string;
  format?: string;
  multiline?: boolean;
  options?: Array<{value: string; label: string}>;
}

/** Typed input shared by header filters and the condition-tree editor. Dates use browser timezone. */
export default function QueryValue({op, value, onChange, type, javaType, format, options, multiline}: QueryValueProps) {
  const {t} = useI18n();
  if (nullOps.includes(op)) return null;
  const numeric = isNumericQueryType(type, javaType);
  const boolean = type === 'boolean';
  const date = type === 'date' || format === 'date' || format === 'date-time';
  const range = op.includes('between');
  const multiple = arrayOps.includes(op);
  const enumOptions = boolean ? [{value: 'true', label: t('common.yes', '是')}, {value: 'false', label: t('common.no', '否')}] : options;
  const normalize = (v: string): Scalar => queryScalar(v, type, javaType);
  if (enumOptions && !range) return <Select style={{width: '100%'}} allowClear showSearch optionFilterProp="label"
    mode={multiple ? 'multiple' : undefined} options={enumOptions}
    value={Array.isArray(value) ? value.map(String) : value === undefined ? undefined : String(value)}
    onChange={v => onChange(Array.isArray(v) ? v.map(normalize) : v === undefined ? undefined : normalize(v))} />;
  if (multiple && !range) return <Select mode="tags" style={{width: '100%'}} allowClear
    placeholder={t('table.query.tags', '逐个输入后按回车；逗号保留在值中')}
    value={Array.isArray(value) ? value.map(String) : []} onChange={v => onChange(v.map(normalize))} />;
  const input = (v: Scalar | undefined, change: (v?: Scalar) => void) => date
    ? <DatePicker style={{width: '100%'}} showTime={format !== 'date' && javaType !== 'LocalDate'}
        value={v && dayjs(String(v)).isValid() ? dayjs(String(v)) : null}
        onChange={d => change(!d ? undefined : format === 'date' || javaType === 'LocalDate' ? d.format('YYYY-MM-DD')
          : javaType === 'LocalDateTime' ? d.format('YYYY-MM-DDTHH:mm:ss') : d.toISOString())} />
    : numeric ? <InputNumber style={{width: '100%'}} stringMode precision={type === 'integer' ? 0 : undefined}
        value={v === undefined ? null : String(v)} onChange={n => change(n == null ? undefined : normalize(String(n)))} />
    : multiline ? <Input.TextArea value={v === undefined ? '' : String(v)} maxLength={2000} allowClear
        autoSize={{minRows: 1, maxRows: 4}} onChange={e => change(e.target.value)}/>
      : <Input value={v === undefined ? '' : String(v)} maxLength={2000} allowClear onChange={e => change(e.target.value)} />;
  if (range) {
    const values = Array.isArray(value) ? value : [];
    return <Space.Compact style={{width: '100%'}}>
      {input(values[0], v => onChange([v ?? '', values[1] ?? '']))}
      {input(values[1], v => onChange([values[0] ?? '', v ?? '']))}
    </Space.Compact>;
  }
  return input(Array.isArray(value) ? undefined : value, onChange);
}
