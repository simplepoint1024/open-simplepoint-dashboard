import type {ReactNode} from 'react';
import {Tooltip} from 'antd';
import {QuestionCircleOutlined} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

/** Focusable contextual help; descriptions remain available to mouse and keyboard users. */
export default function HelpTip({children}: {children: ReactNode}) {
  const {t} = useI18n();
  return <Tooltip title={children} trigger={['hover', 'focus', 'click']}>
    <span tabIndex={0} role="img" aria-label={t('table.help', '帮助')}
      style={{display: 'inline-flex', color: 'var(--ant-color-text-tertiary)', cursor: 'help'}}><QuestionCircleOutlined/></span>
  </Tooltip>;
}
