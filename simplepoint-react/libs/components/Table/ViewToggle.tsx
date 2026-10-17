import {Button, Select, Space, Tooltip} from 'antd';
import {AppstoreOutlined, TableOutlined} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

/** Platform presentation controls; business actions continue to use TableActions. */
export default function ViewToggle({mode, rows, onMode, onRows, pagination = true}: {
  mode: 'table' | 'cards'; rows: 2 | 3 | 4; onMode: (mode: 'table' | 'cards') => void;
  onRows: (rows: 2 | 3 | 4) => void; pagination?: boolean;
}) {
  const {t} = useI18n();
  const label = mode === 'cards' ? t('table.view.table', '切换为列表') : t('table.view.cards', '切换为卡片');
  return <Space size={4}>
    {mode === 'cards' && pagination && <Select size="small" aria-label={t('table.view.rows', '每页卡片行数')}
      value={rows} onChange={onRows} options={[2, 3, 4].map(value => ({value,
        label: t('table.view.rowCount', '{count} 行/页', {count: value})}))} />}
    <Tooltip title={label}><Button type="text" className="sp-table-icon-button" aria-label={label}
      aria-pressed={mode === 'cards'} icon={mode === 'cards' ? <TableOutlined/> : <AppstoreOutlined/>}
      onClick={() => onMode(mode === 'cards' ? 'table' : 'cards')} /></Tooltip>
  </Space>;
}
