import React, {useMemo} from 'react';
import {Select} from 'antd';
import type {WidgetProps} from '@rjsf/utils';
import * as AntIcons from '@ant-design/icons';
import {createIcon} from '@simplepoint/shared/types/icon';

const allIconNames = Object.keys(AntIcons).filter((k) => /Outlined$/.test(k));

const IconPicker: React.FC<WidgetProps> = ({id, value, disabled, readonly, autofocus, onChange, placeholder, rawErrors, options}) => {
  const opts = useMemo(() => {
    const list = (options?.enumOptions as any[]) || allIconNames.map((name) => ({ value: name, label: name }));
    return list.map((o) => ({
      value: o.value,
      label: (
        <span style={{display: 'inline-flex', alignItems: 'center', gap: 8}}>
          <span style={{width: 16, height: 16, display: 'inline-flex', alignItems: 'center', justifyContent: 'center'}}>
            {typeof o.value === 'string' ? createIcon(o.value) : null}
          </span>
          <span>{o.label}</span>
        </span>
      )
    }));
  }, [options]);

  const status = rawErrors && rawErrors.length > 0 ? 'error' : undefined;

  return (
    <Select
      id={id}
      showSearch
      allowClear
      placeholder={placeholder || '选择图标'}
      value={value as any}
      disabled={disabled || readonly}
      autoFocus={autofocus}
      onChange={(v) => onChange(v)}
      optionFilterProp="children"
      options={opts}
      status={status as any}
      style={{ width: '100%' }}
    />
  );
};

export default IconPicker;

