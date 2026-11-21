import DataSchemaEditor from '../../FormDesigner/DataSchemaEditor';
import type {DesignerPropertyEditorProps} from '../../FormDesigner/extensions';
import {detailIdentity} from './model';
import {detailDesignDocument} from '../../FormDesigner/relations/model';

/** Detail schema tabs reuse the same field/index commands as the main model editor. */
function DetailProperties({node, document, components, writePolicies, disabled, onDocumentChange, section}:
  DesignerPropertyEditorProps & {section: 'fields' | 'indexes'}) {
  const detail = document.details?.find(item => item.detailId === detailIdentity(node));
  if (!detail) return null;
  return <DataSchemaEditor calculationScope={detail.detailId} modelScope="detail" section={section} compact
    relationOwner={{document, detailId: detail.detailId, onChange: onDocumentChange}}
    document={detailDesignDocument(document, detail)}
    components={components} writePolicies={writePolicies} disabled={disabled} maxFields={48} maxIndexes={8}
    onChange={next => onDocumentChange({...document, details: document.details?.map(item => item.detailId === detail.detailId
      ? {...item, fields: next.fields, indexes: next.indexes} : item)})}/>;
}
export function DetailFieldsProperties(props: DesignerPropertyEditorProps) {return <DetailProperties {...props} section="fields"/>;}
export function DetailIndexesProperties(props: DesignerPropertyEditorProps) {return <DetailProperties {...props} section="indexes"/>;}
