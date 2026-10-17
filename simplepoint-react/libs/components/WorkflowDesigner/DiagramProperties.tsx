import {Alert, Form, Input, Switch} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {diagramLabel, modelingCapabilities, type DiagramBusinessObject} from './modelingCapabilities';

/** Specialized BPMN model editing uses native controls, not resource form submission/validation. */
export default function DiagramProperties({business, disabled, onLabelChange, onCollectionChange, onPlainText, onClearMultiplicity}: {
  business: DiagramBusinessObject; disabled: boolean; onLabelChange: (text: string) => void;
  onCollectionChange: (value: boolean) => void; onPlainText: () => void;
  onClearMultiplicity: () => void;
}) {
  const {t} = useI18n();
  const annotation = business.$type === 'bpmn:TextAnnotation', group = business.$type === 'bpmn:Group';
  const hints: Record<string, string> = {
    'bpmn:Participant': t('workflow.designer.poolHint', '泳池组织当前这一个可执行流程，不创建外部流程，也不改变审批权限。'),
    'bpmn:Lane': t('workflow.designer.laneHint', '泳道用于划分职责。实际办理人仍由任务的办理范围决定，不会自动授权。'),
    'bpmn:DataObjectReference': t('workflow.designer.dataHint', '描述流程使用或产生的数据；不会自动绑定变量、读取或写入数据。实际数据使用流程表单与变量配置。'),
    'bpmn:DataStoreReference': t('workflow.designer.storeHint', '描述持久化数据的位置，不是数据库连接器，不会自动读取或写入外部系统。'),
    'bpmn:Group': t('workflow.designer.groupHint', '分组仅用于视觉归类，不改变执行顺序或审批范围。共享同一分类的分组会同步显示分类名称。'),
    'bpmn:TextAnnotation': t('workflow.designer.annotationHint', '纯文本说明，可通过关联线连接到节点；不会作为表达式或 HTML 执行。'),
  };
  const label = annotation ? t('workflow.designer.annotationText', '注释内容')
    : group ? t('workflow.designer.categoryLabel', '分类名称') : t('workflow.designer.name', '名称');
  return <section className="workflow-diagram-properties">
    <Alert type="info" showIcon message={hints[business.$type ?? '']}/>
    <Form.Item label={label}>
      {annotation ? <Input.TextArea aria-label={label} value={diagramLabel(business)} disabled={disabled}
        autoSize={{minRows: 3, maxRows: 10}} onChange={event => onLabelChange(event.target.value)}/>
        : <Input aria-label={label} value={diagramLabel(business)} disabled={disabled} onChange={event => onLabelChange(event.target.value)}/>}
    </Form.Item>
    {business.$type === 'bpmn:DataObjectReference' && business.dataObjectRef && <Form.Item label={t('workflow.designer.dataCollection', '数据集合')}>
      <Switch aria-label={t('workflow.designer.dataCollection', '数据集合')} disabled={disabled}
        checked={business.dataObjectRef.isCollection === true} onChange={onCollectionChange}/>
    </Form.Item>}
    {annotation && business.textFormat !== undefined && business.textFormat !== modelingCapabilities.annotationFormat && <Form.Item label={t('workflow.designer.annotationPlain', '使用纯文本格式')}>
      <Switch aria-label={t('workflow.designer.annotationPlain', '使用纯文本格式')} disabled={disabled} checked={false} onChange={onPlainText}/>
    </Form.Item>}
    {business.$type === 'bpmn:Participant' && Boolean(business.participantMultiplicity) && <Form.Item
      label={t('workflow.designer.unsupportedMultiplicity', '参与者多实例（不支持，请关闭）')}>
      <Switch aria-label={t('workflow.designer.unsupportedMultiplicity', '参与者多实例（不支持，请关闭）')}
        disabled={disabled} checked onChange={onClearMultiplicity}/>
    </Form.Item>}
  </section>;
}
