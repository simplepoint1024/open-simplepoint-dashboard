import type {ReactNode} from 'react';
import {Splitter, Tooltip} from 'antd';
import {createIcon} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import './style.css';

type Props = {children: ReactNode; sidebar: ReactNode; defaultWidth?: number};

/** Shared editor layout. Splitter owns drag, touch, collapse and restoration; hidden content stays mounted. */
export default function ResizableSidebar({children, sidebar, defaultWidth = 300}: Props) {
  const {t} = useI18n();
  return <Splitter className="sp-resizable-sidebar" destroyOnHidden={false}
    collapsible={{icon: {
      start: <Tooltip title={t('table.expandConfiguration', '展开面板')}>{createIcon('LeftOutlined')}</Tooltip>,
      end: <Tooltip title={t('table.collapseConfiguration', '收起面板')}>{createIcon('RightOutlined')}</Tooltip>,
    }}}>
    <Splitter.Panel min="30%" className="sp-resizable-sidebar-main">{children}</Splitter.Panel>
    <Splitter.Panel defaultSize={defaultWidth} min={240} max="70%" collapsible={{start: true, showCollapsibleIcon: true}}
      className="sp-resizable-sidebar-panel">{sidebar}</Splitter.Panel>
  </Splitter>;
}
