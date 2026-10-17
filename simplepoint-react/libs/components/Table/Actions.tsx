import {Button, Dropdown, Space, Tooltip} from 'antd';
import type {Key, ReactElement} from 'react';
import {createIcon} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {createContext, useContext, useEffect, useState} from 'react';
import {resolveResourceLabel} from '@simplepoint/shared/i18n/resourceLabel';
import type {TableButtonProps, TableProps} from './index';

export const RowActionContext = createContext(false);
const rowActionIcons: Readonly<Record<string, string>> = {
  view: 'EyeOutlined', detail: 'EyeOutlined', edit: 'EditOutlined', update: 'EditOutlined',
  delete: 'DeleteOutlined', remove: 'DeleteOutlined', add: 'PlusOutlined', create: 'PlusOutlined',
  copy: 'CopyOutlined', retry: 'ReloadOutlined', refresh: 'ReloadOutlined',
  download: 'DownloadOutlined', history: 'HistoryOutlined', design: 'FormOutlined',
};

type Props<T> = Pick<TableProps<T>, 'buttons' | 'onButtonEvents' | 'isButtonDisabled'> & {
  appearance?: 'toolbar' | 'row';
  selectedKeys: Key[];
  selectedRows: T[];
  /** Context and overflow menus share the platform action permission/availability contract. */
  menu?: {trigger: 'click' | 'contextMenu' | 'hover' | ('click' | 'contextMenu' | 'hover')[];
    target?: ReactElement; label?: string; showLabel?: boolean; disabled?: boolean};
};

/** One platform renderer for permission-filtered toolbar and record-detail actions. */
export default function TableActions<T>({buttons, selectedKeys, selectedRows, onButtonEvents, isButtonDisabled, menu, appearance}: Props<T>) {
  const rowContext = useContext(RowActionContext);
  const rowAppearance = appearance ? appearance === 'row' : rowContext;
  const {t, ensure} = useI18n();
  const [menuOpen, setMenuOpen] = useState(false);
  useEffect(() => {if (menu?.disabled) setMenuOpen(false);}, [menu?.disabled]);
  useEffect(() => {void ensure(['table']);}, [ensure]);
  const label = (value: unknown, fallback: string) => {
    const text = typeof value === 'string' ? value : fallback;
    return resolveResourceLabel(text, fallback, t);
  };
  const actions = buttons?.map(button => {
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
      return {button, rest, color, variant, title, iconOnly, children, actionLabel, reason,
        icon: (typeof icon === 'string' ? createIcon(icon) : icon)
          ?? (rowAppearance ? createIcon(rowActionIcons[String(button.key)] ?? 'RightOutlined') : undefined),
        disabled: disabled || unavailable,
        invoke: () => {if (!disabled && !unavailable) onButtonEvents?.[button.key]?.(selectedKeys, selectedRows, button);}};
    }) ?? [];
  if (menu) return <Dropdown trigger={Array.isArray(menu.trigger) ? menu.trigger : [menu.trigger]}
    disabled={menu.disabled} open={menuOpen && !menu.disabled} onOpenChange={setMenuOpen}
    menu={{items: actions.map(action => ({
    key: action.button.key, label: action.actionLabel, icon: action.icon, disabled: action.disabled,
    danger: action.rest.danger || action.color === 'danger',
    onClick: ({domEvent}) => {
      domEvent.stopPropagation();
      setMenuOpen(false);
      if (!menu.disabled && !action.disabled) {
        if (action.button.onClick) action.button.onClick(domEvent as any);
        else action.invoke();
      }
    },
  }))}}>
    {menu.target ?? <Tooltip title={menu.showLabel ? undefined : menu.label ?? t('table.more', '更多操作')}><Button type={menu.showLabel ? 'default' : 'text'} disabled={menu.disabled}
      icon={createIcon(menu.showLabel ? 'DownOutlined' : 'MoreOutlined')} iconPlacement={menu.showLabel ? 'end' : 'start'}
      aria-label={menu.label ?? t('table.more', '更多操作')} aria-haspopup="menu" aria-expanded={menuOpen && !menu.disabled}>
      {menu.showLabel ? menu.label ?? t('table.more', '更多操作') : null}
    </Button></Tooltip>}
  </Dropdown>;
  return <Space className="sp-table-button-group" size={[8, 8]} wrap>
    {actions.map(({button, rest, color, variant, title, iconOnly, children, actionLabel, reason, icon, disabled, invoke}) =>
      <Tooltip key={button.key} title={reason || (iconOnly ? actionLabel : undefined)}><span style={{display: 'inline-flex'}}><Button {...rest}
        className={['sp-table-button', rowAppearance && 'sp-table-row-action', rest.className].filter(Boolean).join(' ')}
        type={rowAppearance ? 'text' : rest.type}
        variant={rowAppearance ? 'text' : variant}
        danger={rest.danger || color === 'danger'} ghost={!rowAppearance && (variant === 'outlined' || rest.ghost)}
        title={title ? t(`table.button.${button.key}.title`, label(title, button.key)) : undefined}
        aria-label={iconOnly || children != null ? rest['aria-label'] ?? actionLabel : rest['aria-label']}
        icon={icon} disabled={disabled}
        onClick={button.onClick ?? invoke}>
        {iconOnly && !rowAppearance ? null : children ?? actionLabel}
      </Button></span></Tooltip>)}
  </Space>;
}
