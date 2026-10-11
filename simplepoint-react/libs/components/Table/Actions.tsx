import {Button, Space, Tooltip} from 'antd';
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
      const {argumentMinSize: min, argumentMaxSize: max, sort: _sort, color, variant, text, icon, title, iconOnly, children,
        authority: _authority, path: _path, ...rest} = button as TableButtonProps & {authority?: string; path?: string};
      const size = selectedKeys.length;
      const disabled = button.disabled || selectedRows.length !== size
        || (typeof min === 'number' && min !== -1 && size < min)
        || (typeof max === 'number' && max !== -1 && size > max)
        || !!isButtonDisabled?.(button, selectedKeys, selectedRows);
      const unavailable = !button.onClick && !onButtonEvents?.[button.key];
      const reason = unavailable ? t('table.actionUnavailable', '此操作暂不可用，请刷新页面后重试')
        : selectedRows.length !== size ? t('table.selectionExpired', '所选记录已不可用，请重新选择')
        : typeof min === 'number' && min !== -1 && size < min
          ? t('table.selectAtLeast', '请至少选择 {count} 条记录', {count: min})
          : typeof max === 'number' && max !== -1 && size > max
            ? t('table.selectAtMost', '最多选择 {count} 条记录', {count: max}) : undefined;
      const actionLabel = text == null
        ? t(`table.button.${button.key}`, label(title, button.key))
        : label(text, button.key);
      return <Tooltip key={button.key} title={reason || (iconOnly ? actionLabel : undefined)}><span style={{display: 'inline-flex'}}><Button {...rest}
        className={['sp-table-button', rest.className].filter(Boolean).join(' ')}
        danger={rest.danger || color === 'danger'} ghost={variant === 'outlined' || rest.ghost}
        title={title ? t(`table.button.${button.key}.title`, label(title, button.key)) : undefined}
        aria-label={iconOnly || children != null ? rest['aria-label'] ?? actionLabel : rest['aria-label']}
        icon={typeof icon === 'string' ? createIcon(icon) : icon} disabled={disabled || unavailable}
        onClick={button.onClick ?? (() => onButtonEvents?.[button.key]?.(selectedKeys, selectedRows, button))}>
        {/* Rich record actions still use the same availability/selection/label contract. */}
        {iconOnly ? null : children ?? actionLabel}
      </Button></span></Tooltip>;
    })}
  </Space>;
}
