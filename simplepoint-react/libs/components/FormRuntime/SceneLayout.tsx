import {createIcon} from '@simplepoint/shared/types/icon';
import StandardDisplay from '../FormComponents/Standard/Display';
import type {ObjectFieldTemplateProps} from '@rjsf/utils';
import {Tabs, Typography} from 'antd';
import {useEffect, useState, type ReactNode} from 'react';
import PageSection from '@simplepoint/shared/page/Section';
import type {RuntimeLayoutItem, RuntimeFormAction} from './model.ts';

export type RuntimeLayoutContext = {
  layout: RuntimeLayoutItem[];
  renderAction: (action: RuntimeFormAction) => ReactNode;
  errorFields: readonly string[];
};

/** Stable SForm template: field inputs retain their identity while rules and values change. */
export default function SceneLayout({properties, registry}: ObjectFieldTemplateProps) {
  const context = registry.formContext.runtimeLayout as RuntimeLayoutContext;
  const fields = new Map(properties.map(property => [property.name, property.content]));
  return <div className="sp-form-runtime-grid">{context.layout.map(item =>
    <LayoutNode key={item.key} item={item} fields={fields} context={context}/>)}</div>;
}

type NodeProps = {item: RuntimeLayoutItem; fields: Map<string, ReactNode>; context: RuntimeLayoutContext};
function LayoutNode({item, fields, context}: NodeProps) {
  if (item.hidden) return null;
  const content = item.kind === 'field' ? fields.get(item.fieldId)
    : item.kind === 'action' ? context.renderAction(item.action)
    : item.kind === 'display' ? <StandardDisplay node={item.component}/>
    : item.layout === 'tabs' ? <LayoutTabs item={item} fields={fields} context={context}/>
    : <>
      {item.title && <Typography.Title level={5}>{item.title}</Typography.Title>}
      <div className={item.layout === 'matrix' ? 'sp-form-runtime-matrix' : 'sp-form-runtime-grid'} style={item.layout === 'matrix' ? {gridTemplateColumns: `repeat(${item.columns}, minmax(0, 1fr))`} : undefined}>{item.children.map(child =>
        <LayoutNode key={child.key} item={child} fields={fields} context={context}/>)}</div>
    </>;
  return <section className={`sp-form-runtime-${item.kind}`} data-form-component={item.key}
    style={{gridColumn: item.kind === 'container' && item.layout === 'cell' ? undefined : `span ${item.columnSpan}`}}>{content}</section>;
}

function LayoutTabs({item, fields, context}: NodeProps & {item: Extract<RuntimeLayoutItem, {kind: 'container'}>}) {
  const children = item.children.filter(child => !child.hidden);
  const [active, setActive] = useState(children[0]?.key);
  useEffect(() => {
    const target = children.find(child => containsError(child, context.errorFields));
    if (target) setActive(target.key);
  }, [context.errorFields]);
  const activeKey = children.some(child => child.key === active) ? active : children[0]?.key;
  return <Tabs activeKey={activeKey}
    onChange={setActive} items={children.map((child, index) => ({key: child.key,
      icon: createIcon('FolderOutlined'), label: 'title' in child && child.title ? child.title : String(index + 1), forceRender: true,
      children: <PageSection selected={child.key === activeKey}>
        <div className="sp-form-runtime-grid"><LayoutNode item={child} fields={fields} context={context}/></div>
      </PageSection>,
    }))}/>;
}

function containsError(item: RuntimeLayoutItem, fields: readonly string[]): boolean {
  return item.kind === 'field' ? fields.includes(item.fieldId)
    : item.kind === 'container' && item.children.some(child => containsError(child, fields));
}
