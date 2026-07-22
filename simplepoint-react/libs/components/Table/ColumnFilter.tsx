import React, {ChangeEvent, useEffect, useMemo, useRef, useState} from 'react';
import {Button, Divider, Input, Select, Tag} from 'antd';
import {FilterOutlined} from '@ant-design/icons';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

export type ColumnFilterType = 'string' | 'number' | 'integer' | 'boolean' | 'date' | 'unknown';

export interface ColumnFilterProps {
  initialOp?: string;
  initialText?: string;
  onChange?: (op: string, text: string) => void;
  /** Dictionary options. When present, filtering is fixed to a searchable multi-select. */
  options?: Array<{ value: string; label: string }>;
  columnType?: ColumnFilterType;
  columnLabel?: string;
}

/** 操作符是否不需要输入值 */
const NO_INPUT_OPS = new Set(['is:null', 'is:not:null']);
/** 操作符是否需要逗号分隔多值 */
const MULTI_VALUE_OPS = new Set(['in', 'not:in', 'between', 'not:between']);

const ColumnFilter: React.FC<ColumnFilterProps> = ({
  initialOp = 'like',
  initialText = '',
  onChange,
  options,
  columnType = 'unknown',
  columnLabel,
}) => {
  const {t, locale} = useI18n();
  const inputRef = useRef<any>(null);
  const isDictionary = Array.isArray(options);

  // ── 操作符分组 ────────────────────────────────────────────────────────────
  const opGroups = useMemo(() => {
    const isNumeric = columnType === 'number' || columnType === 'integer';
    const isDate    = columnType === 'date';

    const matchGroup = {
      label: t('table.filter.group.match', '匹配'),
      options: [
        {value: 'like',      label: t('table.filter.like',     '包含')},
        {value: 'not:like',  label: t('table.filter.notLike',  '不包含')},
        {value: 'equals',    label: t('table.filter.equals',   '等于')},
        {value: 'not:equals',label: t('table.filter.notEquals','不等于')},
      ],
    };

    const setGroup = {
      label: t('table.filter.group.set', '集合'),
      options: [
        {value: 'in',     label: t('table.filter.in',    '包含于（逗号分隔）')},
        {value: 'not:in', label: t('table.filter.notIn', '排除于（逗号分隔）')},
      ],
    };

    const compareGroup = {
      label: t('table.filter.group.compare', '比较'),
      options: [
        {value: 'than:greater',       label: t('table.filter.greater',       '大于')},
        {value: 'than:less',          label: t('table.filter.less',          '小于')},
        {value: 'than:equal:greater', label: t('table.filter.greaterOrEqual','大于等于')},
        {value: 'than:equal:less',    label: t('table.filter.lessOrEqual',   '小于等于')},
        {value: 'between',            label: t('table.filter.between',       '区间（逗号分隔）')},
        {value: 'not:between',        label: t('table.filter.notBetween',    '区间取反（逗号分隔）')},
      ],
    };

    const nullGroup = {
      label: t('table.filter.group.null', '空值'),
      options: [
        {value: 'is:null',     label: t('table.filter.null',    '为空')},
        {value: 'is:not:null', label: t('table.filter.notNull', '非空')},
      ],
    };

    if (isNumeric || isDate) return [compareGroup, setGroup, nullGroup];
    return [matchGroup, setGroup, compareGroup, nullGroup];
  }, [columnType, t, locale]);

  const selectOptions = opGroups.map(g => ({label: g.label, options: g.options}));

  const [inputValue, setInputValue] = useState(initialText);
  const [selectValue, setSelectValue] = useState(initialOp);
  const [dictionaryValues, setDictionaryValues] = useState<string[]>(() =>
    initialText.split(',').map(value => value.trim()).filter(Boolean)
  );
  const noInput = NO_INPUT_OPS.has(selectValue);
  const isMulti  = MULTI_VALUE_OPS.has(selectValue);

  useEffect(() => {
    setSelectValue(initialOp);
    setInputValue(initialText);
    setDictionaryValues(initialText.split(',').map(value => value.trim()).filter(Boolean));
  }, [initialOp, initialText]);

  // 选了不需要输入的操作符时清空
  useEffect(() => {
    if (NO_INPUT_OPS.has(selectValue)) setInputValue('');
  }, [selectValue]);

  // Dropdown 出现时自动聚焦输入框
  useEffect(() => {
    if (!noInput && !isDictionary) {
      const timer = setTimeout(() => inputRef.current?.focus(), 60);
      return () => clearTimeout(timer);
    }
  }, [isDictionary, noInput]);

  const apply = () => isDictionary
    ? onChange?.('in', dictionaryValues.join(','))
    : onChange?.(selectValue, noInput ? '' : inputValue);
  const reset = () => {
    setSelectValue(isDictionary ? 'in' : 'like');
    setInputValue('');
    setDictionaryValues([]);
    onChange?.(isDictionary ? 'in' : 'like', '');
  };

  const placeholder = isMulti
    ? t('table.filter.placeholder.multi', '多值请用逗号分隔')
    : t('table.filter.placeholder.single', '请输入筛选值');

  return (
    <div style={{width: 300, padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 10}}>
      {/* 标题 */}
      <div style={{display: 'flex', alignItems: 'center', gap: 6}}>
        <FilterOutlined style={{fontSize: 13, opacity: 0.55}}/>
        <span style={{fontSize: 12, fontWeight: 600, opacity: 0.75, letterSpacing: '0.02em'}}>
          {columnLabel
            ? t('table.filter.title.named', '筛选：{col}', {col: columnLabel})
            : t('table.filter.title', '列筛选')}
        </span>
        {(isDictionary ? dictionaryValues.length > 0 : inputValue || NO_INPUT_OPS.has(selectValue)) && (
          <Tag color="blue" style={{marginLeft: 'auto', fontSize: 11, padding: '0 6px'}}>
            {t('table.filter.active', '已激活')}
          </Tag>
        )}
      </div>

      <Divider style={{margin: 0}}/>

      {isDictionary ? (
        <div>
          <div style={{fontSize: 11, opacity: 0.5, marginBottom: 5, fontWeight: 500, letterSpacing: '0.03em'}}>
            {t('table.filter.value', '筛选值')}
          </div>
          <Select
            mode="multiple"
            allowClear
            showSearch
            autoFocus
            optionFilterProp="label"
            filterOption={(input, option) =>
              String(option?.label ?? '').toLocaleLowerCase().includes(input.toLocaleLowerCase())
              || String(option?.value ?? '').toLocaleLowerCase().includes(input.toLocaleLowerCase())
            }
            maxTagCount="responsive"
            style={{width: '100%'}}
            value={dictionaryValues}
            options={options}
            onChange={values => setDictionaryValues(values)}
            placeholder={t('table.filter.dictionary.placeholder', '搜索并选择一个或多个选项')}
            size="small"
          />
        </div>
      ) : (
        <div>
          <div style={{fontSize: 11, opacity: 0.5, marginBottom: 5, fontWeight: 500, letterSpacing: '0.03em'}}>
            {t('table.filter.operator', '操作符')}
          </div>
          <Select
            style={{width: '100%'}}
            value={selectValue}
            options={selectOptions}
            onChange={v => setSelectValue(v)}
            size="small"
            popupMatchSelectWidth={false}
          />
        </div>
      )}

      {/* 输入框 */}
      {isDictionary ? null : noInput ? (
        <div style={{
          padding: '6px 10px',
          borderRadius: 4,
          background: 'rgba(22,119,255,0.06)',
          fontSize: 12,
          color: 'rgba(22,119,255,0.9)',
        }}>
          {selectValue === 'is:null'
            ? t('table.filter.nullHint', '将筛选值为空的行')
            : t('table.filter.notNullHint', '将筛选值不为空的行')}
        </div>
      ) : (
        <div>
          <div style={{fontSize: 11, opacity: 0.5, marginBottom: 5, fontWeight: 500, letterSpacing: '0.03em'}}>
            {t('table.filter.value', '筛选值')}
          </div>
          <Input
            ref={inputRef}
            value={inputValue}
            onChange={(e: ChangeEvent<HTMLInputElement>) => setInputValue(e.target.value)}
            onPressEnter={apply}
            placeholder={placeholder}
            allowClear
            size="small"
          />
        </div>
      )}

      {/* 操作按钮 */}
      <div style={{display: 'flex', justifyContent: 'flex-end', gap: 8, paddingTop: 2}}>
        <Button size="small" onClick={reset}>
          {t('table.filter.reset', '重置')}
        </Button>
        <Button size="small" type="primary" onClick={apply} icon={<FilterOutlined/>}>
          {t('table.filter.apply', '应用')}
        </Button>
      </div>
    </div>
  );
};

export default ColumnFilter;
