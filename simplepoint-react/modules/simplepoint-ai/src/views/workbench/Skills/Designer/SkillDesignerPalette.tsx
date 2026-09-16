import {BranchesOutlined, DatabaseOutlined, ForkOutlined, FormOutlined, MessageOutlined, ToolOutlined} from '@ant-design/icons';
import {Alert, Card, Space, Typography} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {DragEvent, KeyboardEvent, ReactNode} from 'react';
import {DESIGNER_DRAG_TYPE} from './types';

const {Text} = Typography;

type PaletteItemProps = {
  icon: ReactNode;
  title: string;
  description: string;
  enabled?: boolean;
  nodeType?: 'TOOL' | 'PROMPT' | 'RESOURCE' | 'CONDITION' | 'PARALLEL';
  onActivate?: (nodeType: NonNullable<PaletteItemProps['nodeType']>) => void;
};

const PaletteItem = ({icon, title, description, enabled = false, nodeType, onActivate}: PaletteItemProps) => {
  const onDragStart = (event: DragEvent<HTMLDivElement>) => {
    if (!enabled) return;
    if (!nodeType) return;
    event.dataTransfer.setData(DESIGNER_DRAG_TYPE, nodeType);
    event.dataTransfer.effectAllowed = 'move';
  };
  const activate = () => {
    if (enabled && nodeType) onActivate?.(nodeType);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    activate();
  };
  return (
    <Card
      size="small"
      role="button"
      tabIndex={enabled ? 0 : -1}
      aria-disabled={!enabled}
      aria-label={`${title}: ${description}`}
      draggable={enabled}
      onDragStart={onDragStart}
      onClick={activate}
      onKeyDown={onKeyDown}
      style={{cursor: enabled ? 'grab' : 'not-allowed', opacity: enabled ? 1 : .48}}
      styles={{body: {padding: 10}}}
    >
      <Space align="start">
        <span style={{fontSize: 18, color: enabled ? '#722ed1' : undefined}}>{icon}</span>
        <span>
          <Text strong>{title}</Text>
          <Text type="secondary" style={{display: 'block', fontSize: 12}}>{description}</Text>
        </span>
      </Space>
    </Card>
  );
};

type Props = {
  onAdd: (nodeType: NonNullable<PaletteItemProps['nodeType']>) => void;
};

const SkillDesignerPalette = ({onAdd}: Props) => {
  const {t} = useI18n();
  return <div style={{padding: 12}} aria-label={t('ai.skills.designer.palette.title', '节点面板')}>
    <Alert
      type="info"
      closable
      showIcon
      message={t('ai.skills.designer.palette.hint', '拖入或点击节点；也可用 Tab 选择后按 Enter 添加')}
      style={{marginBottom: 12}}
    />
    <Space direction="vertical" size={10} style={{width: '100%'}}>
      <PaletteItem enabled onActivate={onAdd} nodeType="TOOL" icon={<ToolOutlined />} title={t('ai.skills.designer.node.tool', 'MCP Tool')} description={t('ai.skills.designer.palette.tool', '调用固定能力快照')} />
      <PaletteItem enabled onActivate={onAdd} nodeType="PROMPT" icon={<MessageOutlined />} title={t('ai.skills.designer.node.prompt', 'MCP Prompt')} description={t('ai.skills.designer.palette.prompt', '获取固定 Prompt')} />
      <PaletteItem enabled onActivate={onAdd} nodeType="RESOURCE" icon={<DatabaseOutlined />} title={t('ai.skills.designer.node.resource', 'MCP Resource')} description={t('ai.skills.designer.palette.resource', '读取固定 URI 或模板')} />
      <PaletteItem enabled onActivate={onAdd} nodeType="CONDITION" icon={<BranchesOutlined />} title={t('ai.skills.designer.node.condition', '条件')} description={t('ai.skills.designer.palette.condition', 'True / False 分支')} />
      <PaletteItem enabled onActivate={onAdd} nodeType="PARALLEL" icon={<ForkOutlined />} title={t('ai.skills.designer.node.parallel', '并行')} description={t('ai.skills.designer.palette.parallel', '并行执行并汇合')} />
      <PaletteItem icon={<FormOutlined />} title={t('ai.skills.designer.node.inputOutput', '输入 / 输出')} description={t('ai.skills.designer.palette.fixedIo', '每个 Skill 固定各一个')} />
    </Space>
  </div>
};

export default SkillDesignerPalette;
