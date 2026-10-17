import {Button, Tooltip} from 'antd';
import {ReloadOutlined} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

/** Local edits remain available while account synchronization can be retried explicitly. */
export default function PreferenceFeedback({preferences}: {preferences: {failed: boolean; retry: () => void}[]}) {
  const {t} = useI18n();
  if (!preferences.some(item => item.failed)) return null;
  const label = t('table.preferences.retry', '表格设置暂未同步，点击重试');
  return <Tooltip title={label}><Button type="text" icon={<ReloadOutlined/>} aria-label={label}
    onClick={() => preferences.filter(item => item.failed).forEach(item => item.retry())}/></Tooltip>;
}
