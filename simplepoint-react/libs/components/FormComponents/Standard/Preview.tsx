import {Rate, Switch} from 'antd';
import {createIcon} from '@simplepoint/shared/types/icon';
import type {FormComponentDefinition} from '../../FormRuntime/contracts';
import StandardDisplay from './Display';
/** Noninteractive canvas previews use the same display renderer as runtime. */
export default function StandardPreview({node}: {node: FormComponentDefinition}) {
  if (node.componentType.startsWith('display.')) return <StandardDisplay node={node}/>;
  if (node.componentType === 'field.rating') return <Rate disabled count={Number(node.properties.count?.value ?? 5)} value={3}/>;
  if (node.componentType === 'field.switch') return <Switch disabled/>;
  return <div className="sp-form-field-preview">{createIcon(node.componentType === 'field.transfer' ? 'SwapOutlined' : 'BranchesOutlined')}{node.placeholder || node.label}</div>;
}
