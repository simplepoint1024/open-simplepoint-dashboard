import React, {type Key, type ReactNode} from 'react';
import {Checkbox, Pagination, Spin, type PaginationProps} from 'antd';
import type {ColumnType, ColumnsType} from 'antd/es/table';
import type {TableRowSelection} from 'antd/es/table/interface';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {isRowSelectionClick} from './rowClickSelection';
import './cards.css';

export type CardViewConfig = {
  minWidth?: number;
  titleKey?: string;
  subtitleKey?: string;
  statusKey?: string;
  fieldKeys?: string[];
};

const columnKey = <T,>(column: ColumnType<T>) => String(column.key ?? column.dataIndex);
const cellValue = <T,>(column: ColumnType<T>, record: T, index: number): ReactNode => {
  const path = Array.isArray(column.dataIndex) ? column.dataIndex : [column.dataIndex];
  const value = path.reduce<unknown>((current, key) => key == null ? undefined
    : (current as Record<string, unknown> | undefined)?.[String(key)], record);
  if (column.render) {
    const rendered = column.render(value, record, index);
    return rendered && typeof rendered === 'object' && !React.isValidElement(rendered) && 'children' in rendered
      ? rendered.children : rendered as ReactNode;
  }
  return value == null ? '—' : typeof value === 'boolean' ? String(value)
    : typeof value === 'object' ? JSON.stringify(value) : String(value);
};

/** Renders only authorized, visible columns and reuses their existing formatters and actions. */
export default function CardView<T extends object>({data, columns, columnsCount, rowKey, selection,
  onRow, pagination, loading, emptyText, config = {}, actions}: {
  data: readonly T[]; columns: ColumnsType<T>; columnsCount: number; rowKey: (record: T) => Key;
  selection?: TableRowSelection<T>; onRow?: (record: T, index: number) => React.HTMLAttributes<HTMLElement>;
  pagination: PaginationProps | false; loading?: boolean; emptyText?: ReactNode;
  config?: CardViewConfig; actions?: (record: T) => ReactNode;
}) {
  const {t} = useI18n();
  const leaves = columns.filter((column): column is ColumnType<T> => !('children' in column));
  const fields = leaves.filter(column => column.dataIndex != null);
  const title = fields.find(column => columnKey(column) === config.titleKey) ?? fields[0];
  const subtitle = fields.find(column => columnKey(column) === config.subtitleKey);
  const status = fields.find(column => columnKey(column) === config.statusKey);
  const details = fields.filter(column => column !== title && column !== subtitle && column !== status
    && (!config.fieldKeys || config.fieldKeys.includes(columnKey(column))));
  const controls = leaves.filter(column => column.dataIndex == null && column.render);
  const selected = selection?.selectedRowKeys ?? [];
  const select = (record: T, checked: boolean) => {
    const key = rowKey(record);
    const keys = checked ? selection?.type === 'radio' ? [key] : [...selected.filter(item => item !== key), key]
      : selected.filter(item => item !== key);
    selection?.onChange?.(keys, data.filter(item => keys.includes(rowKey(item))), {type: 'single'});
  };
  return <div className="sp-card-view">
    <div className="sp-card-scroll"><Spin spinning={!!loading} delay={150}>
      {data.length === 0 ? emptyText : <div className="sp-card-grid" style={{gridTemplateColumns: `repeat(${columnsCount}, minmax(0, 1fr))`}}>
        {data.map((record, index) => {
          const key = rowKey(record);
          const disabled = selection?.getCheckboxProps?.(record).disabled;
          const handlers = onRow?.(record, index);
          return <article key={key} className={`sp-record-card${selected.includes(key) ? ' sp-record-card-selected' : ''}`}
            {...handlers} onClick={event => {
              handlers?.onClick?.(event);
              if (!handlers?.onClick && selection && !disabled && isRowSelectionClick(event)) select(record, !selected.includes(key));
            }}>
            <header className="sp-record-card-header">
              {selection && <Checkbox checked={selected.includes(key)} disabled={disabled}
                aria-label={t('table.view.selectRecord', '选择记录')} onChange={event => select(record, event.target.checked)} />}
              <div className="sp-record-card-heading">
                <div className="sp-record-card-title">{title ? cellValue(title, record, index) : String(key)}</div>
                {subtitle && <div className="sp-record-card-subtitle">{cellValue(subtitle, record, index)}</div>}
              </div>
              {status && <div>{cellValue(status, record, index)}</div>}
            </header>
            <dl className="sp-record-card-fields">{details.map(column => <div key={columnKey(column)}>
              <dt>{typeof column.title === 'function' ? columnKey(column) : column.title}</dt>
              <dd>{cellValue(column, record, index)}</dd>
            </div>)}</dl>
            {(actions || controls.length > 0) && <footer className="sp-record-card-actions">
              {actions?.(record)}{controls.map(column => <React.Fragment key={columnKey(column)}>{cellValue(column, record, index)}</React.Fragment>)}
            </footer>}
          </article>;
        })}
      </div>}
    </Spin></div>
    {pagination && <Pagination {...pagination} className="sp-card-pagination" showSizeChanger={false} />}
  </div>;
}
