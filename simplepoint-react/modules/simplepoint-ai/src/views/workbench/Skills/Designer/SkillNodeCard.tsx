import {DeleteOutlined, ToolOutlined} from '@ant-design/icons';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Button, Tag, Typography} from 'antd';
import {Handle, Position, type NodeProps} from '@xyflow/react';
import {memo} from 'react';
import {skillDebugNodeStatusLabel, skillNodeTypeLabel} from './labels';
import type {DesignerNodeType, SkillDebugNodeStatus} from './types';

const {Text} = Typography;

export type SkillNodeData = {
  label: string;
  nodeType: DesignerNodeType;
  sequenceLabel?: string;
  removable: boolean;
  invalid: boolean;
  debugStatus?: SkillDebugNodeStatus;
  outputHandles: Array<{id: string; label: string}>;
  onRemove: (nodeId: string) => void;
};

const colors: Record<DesignerNodeType, string> = {
  INPUT: '#1677ff',
  OUTPUT: '#52c41a',
  TOOL: '#722ed1',
  PROMPT: '#13c2c2',
  RESOURCE: '#fa8c16',
  CONDITION: '#eb2f96',
  PARALLEL: '#2f54eb',
};

const debugColors: Record<SkillDebugNodeStatus, string> = {
  PENDING: '#8c8c8c',
  RUNNING: '#1677ff',
  SUCCEEDED: '#52c41a',
  FAILED: '#ff4d4f',
  SKIPPED: '#bfbfbf',
  WAITING_APPROVAL: '#fa8c16',
  PAUSED: '#722ed1',
};

const SkillNodeCard = ({id, data, selected}: NodeProps) => {
  const {t} = useI18n();
  const nodeData = data as unknown as SkillNodeData;
  const isInput = nodeData.nodeType === 'INPUT';
  const isOutput = nodeData.nodeType === 'OUTPUT';
  const debugColor = nodeData.debugStatus ? debugColors[nodeData.debugStatus] : undefined;
  const nodeTypeLabel = skillNodeTypeLabel(t, nodeData.nodeType);
  return (
    <div
      role="group"
      aria-label={t(
        'ai.skills.designer.canvas.nodeLabel',
        '{type} 节点：{label}',
        {type: nodeTypeLabel, label: nodeData.label},
      )}
      aria-invalid={nodeData.invalid}
      className={nodeData.debugStatus === 'RUNNING' ? 'skill-node-running' : undefined}
      style={{
        width: 196,
        border: `${nodeData.debugStatus === 'SKIPPED' ? '2px dashed' : '2px solid'} ${
          debugColor ?? (selected ? colors[nodeData.nodeType] : '#d9d9d9')
        }`,
        borderRadius: 10,
        background: '#fff',
        boxShadow: debugColor
          ? `0 0 0 3px ${debugColor}22`
          : selected
            ? `0 0 0 2px ${colors[nodeData.nodeType]}22`
            : '0 4px 16px rgba(0,0,0,.08)',
        overflow: 'hidden',
      }}
    >
      {!isInput && <Handle id="in" type="target" position={Position.Left} />}
      <div style={{height: 5, background: colors[nodeData.nodeType]}} />
      <div style={{display: 'flex', alignItems: 'center', gap: 8, padding: '12px 12px 10px'}}>
        <ToolOutlined style={{color: colors[nodeData.nodeType]}} />
        <div style={{minWidth: 0, flex: 1}}>
          <Text strong ellipsis style={{display: 'block'}}>{nodeData.label}</Text>
          <div style={{display: 'flex', flexWrap: 'wrap', gap: 4, marginTop: 6}}>
            <Tag color={nodeData.invalid ? 'error' : colors[nodeData.nodeType]} style={{margin: 0}}>
              {nodeData.invalid
                ? t('ai.skills.designer.canvas.needsConfiguration', '需配置')
                : nodeTypeLabel}
            </Tag>
            {nodeData.sequenceLabel && <Tag style={{margin: 0}}>{nodeData.sequenceLabel}</Tag>}
            {nodeData.debugStatus && (
              <Tag color={debugColor} style={{margin: 0}}>
                {skillDebugNodeStatusLabel(t, nodeData.debugStatus)}
              </Tag>
            )}
          </div>
        </div>
        {nodeData.removable && (
          <Button
            aria-label={t('ai.skills.designer.canvas.deleteNode', '删除节点 {label}', {label: nodeData.label})}
            danger
            size="small"
            type="text"
            icon={<DeleteOutlined />}
            onClick={(event) => {
              event.stopPropagation();
              nodeData.onRemove(id);
            }}
          />
        )}
      </div>
      {!isOutput && nodeData.outputHandles.map((handle, index) => (
        <Handle
          key={handle.id}
          id={handle.id}
          type="source"
          position={Position.Right}
          title={handle.label}
          style={{top: `${((index + 1) / (nodeData.outputHandles.length + 1)) * 100}%`}}
        />
      ))}
    </div>
  );
};

export default memo(SkillNodeCard);
