import './style.css';
import {Alert, Divider, Image, Progress, Typography} from 'antd';
import {lazy, Suspense} from 'react';
import {createIcon} from '@simplepoint/shared/types/icon';
import type {FormComponentDefinition} from '../../FormRuntime/contracts';
import {displayText, safeDisplayUrl} from './model';
const Markdown = lazy(() => import('./Markdown'));
/** Display primitives have no platform CRUD interaction; all content remains declarative. */
export default function StandardDisplay({node}: {node: FormComponentDefinition}) {
  const text = (key: string, fallback = '') => displayText(node, key, fallback);
  switch (node.componentType) {
    case 'display.divider': return <Divider titlePlacement="start">{node.label}</Divider>;
    case 'display.image': {
      const src = safeDisplayUrl(text('src'));
      return src ? <Image src={src} alt={node.label ?? ''} height={Number(text('height', '180'))}
        style={{maxWidth: '100%', objectFit: 'contain'}}/> : <div className="sp-form-image-placeholder">{createIcon('PictureOutlined')}</div>;
    }
    case 'display.alert': return <Alert showIcon title={node.label} description={text('text')}
      type={text('severity', 'info') as 'info' | 'success' | 'warning' | 'error'}/>;
    case 'display.progress': return <div aria-label={node.label ?? undefined}><Progress percent={Number(text('percent', '0'))}/></div>;
    case 'display.icon': return <span role="img" aria-label={node.label ?? text('icon')} style={{fontSize: Number(text('size', '24'))}}>{createIcon(text('icon', 'InfoCircleOutlined'))}</span>;
    case 'display.markdown': return <Suspense fallback={<Typography.Paragraph>{text('text')}</Typography.Paragraph>}><Markdown text={text('text')}/></Suspense>;
    default: return <>{node.label && <Typography.Title level={5}>{node.label}</Typography.Title>}
      {(node.description || text('text')) && <Typography.Paragraph type="secondary">{node.description || text('text')}</Typography.Paragraph>}</>;
  }
}
