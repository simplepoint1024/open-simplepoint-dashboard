import {Button, Space} from 'antd';
import type {Key} from 'react';
import {createIcon} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useEffect} from 'react';
import {resolveResourceLabel} from '@simplepoint/shared/i18n/resourceLabel';
import type {TableButtonProps, TableProps} from './index';

type Props<T> = Pick<TableProps<T>, 'buttons' | 'onButtonEvents' | 'isButtonDisabled'> & {
  selectedKeys: Key[];
  selectedRows: T[];
};

/** One platform renderer for permission-filtered toolbar and record-detail actions. */
export default function TableActions<T>({buttons, selectedKeys, selectedRows, onButtonEvents, isButtonDisabled}: Props<T>) {
  const {t, ensure} = useI18n();
  useEffect(() => {void ensure(['table']);}, [ensure]);
  const label = (value: unknown, fallback: string) => {
    const text = typeof value === 'string' ? value : fallback;
    return resolveResourceLabel(text, fallback, t);
  };
  return <Space className="sp-table-button-group" size={[8, 8]} wrap>
    {buttons?.map(button => {
      const {argumentMinSize: min, argumentMaxSize: max, sort: _sort, color, variant, text, icon, title,
        authority: _authority, path: _path, ...rest} = button as TableButtonProps & {authority?: string; path?: string};
      const size = selectedKeys.length;
      const disabled = button.disabled || selectedRows.length !== size
        || (typeof min === 'number' && min !== -1 && size < min)
        || (typeof max === 'number' && max !== -1 && size > max)
        || !!isButtonDisabled?.(button, selectedKeys, selectedRows);
      return <Button {...rest} key={button.key}
        className={['sp-table-button', rest.className].filter(Boolean).join(' ')}
        danger={rest.danger || color === 'danger'} ghost={variant === 'outlined' || rest.ghost}
        title={title ? t(`table.button.${button.key}.title`, label(title, button.key)) : undefined}
        icon={typeof icon === 'string' ? createIcon(icon) : icon} disabled={disabled}
        onClick={button.onClick ?? (() => onButtonEvents?.[button.key]?.(selectedKeys, selectedRows, button))}>
        {typeof (text ?? title) === 'string' && String(text ?? title).startsWith('i18n:')
          ? label(text ?? title, button.key) : t(`table.button.${button.key}`, label(text ?? title, button.key))}
      </Button>;
    })}
  </Space>;
}
