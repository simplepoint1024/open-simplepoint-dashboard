import {useContext, useState} from 'react';
import {Alert} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import TableActions from '../../Table/Actions';
import type {DesignerPropertyEditorProps} from '../../FormDesigner/extensions';
import {DesignerRelationContext} from '../../FormDesigner/relations/Context';
import RelationEditor from '../../FormDesigner/relations/Editor';
import {fieldRelation} from '../../FormDesigner/relations/model';

export default function ReferenceProperties({node, document, disabled, onDocumentChange}: DesignerPropertyEditorProps) {
  const {t} = useI18n(), directory = useContext(DesignerRelationContext);
  const [editing, setEditing] = useState(false);
  if (!node.fieldId) return null;
  if (fieldRelation(document, {fieldId: node.fieldId})?.retiredByCollectionId) {
    return <Alert type="info" showIcon title={t('app.relation.collection.retiredSource', '此引用字段已配置为停用。')}/>;
  }
  return <>
    <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'configure', icon: 'LinkOutlined', sort: 0, text: t('app.relation.configure', '配置关联'), disabled: disabled || !directory},
    ]} onButtonEvents={{configure: () => {if (!disabled && directory) setEditing(true);}}}/>
    {editing && <RelationEditor document={document} source={{fieldId: node.fieldId}} disabled={disabled}
      onChange={onDocumentChange} onClose={() => setEditing(false)}/>}
  </>;
}
