import DataTable from '../../DataTable';
import type {FormComponentDefinition} from '../../FormRuntime/contracts';
import type {DesignDocument} from '../../FormDesigner/contracts';
import {detailIdentity} from './model';

export default function DetailPreview({node, document}: {node: FormComponentDefinition; document: DesignDocument}) {
  const detail = document.details?.find(item => item.detailId === detailIdentity(node));
  return <div className="sp-form-detail-preview" aria-hidden>
    <DataTable rowKey="key" dataSource={[{key: 'preview'}]} pagination={false} settings={false} cards={false}
      columns={detail?.fields.map(field => ({key: field.fieldId, title: field.label, render: () => '—'})) ?? []}/>
  </div>;
}
