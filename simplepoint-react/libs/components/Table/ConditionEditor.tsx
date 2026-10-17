import type {ReactNode} from 'react';
import {Button, Select, Space} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import QueryValue from './QueryValue';
import {operatorLabels, type QueryContract, type QueryNode} from './query';

export default function ConditionEditor({node, onChange, contract, properties, depth = 0, renderValue, multiline, preserveGroups = false}: {
  node: QueryNode; onChange: (node: QueryNode) => void; contract: QueryContract; properties: Record<string, any>; depth?: number;
  multiline?: boolean; renderValue?: (node: QueryNode, onChange: (node: QueryNode) => void, input: ReactNode) => ReactNode;
  /** Wrap compound conditions when negating instead of discarding their siblings. */
  preserveGroups?: boolean;
}) {
  const {t} = useI18n();
  const fields = Object.keys(contract.fields).filter(field => properties[field]);
  const fresh = (): QueryNode => ({field: fields[0], op: contract.fields[fields[0]]?.operators[0] ?? 'equals'});
  if (node.children) return <div data-condition-group={node.op} style={{padding: 12, border: '1px solid var(--ant-color-border, #ddd)', borderRadius: 6}}>
    <Space wrap>
      <Select style={{width: 180}} value={node.op} options={[
        {value: 'and', label: t('table.query.and', '满足全部')},
        {value: 'or', label: t('table.query.or', '满足任一')},
        {value: 'not', label: t('table.query.not', '不满足')},
      ]} onChange={op => onChange({...node, op, children: op === 'not'
        ? node.children?.length ? preserveGroups && node.children.length > 1 ? [node] : node.children.slice(0, 1) : [fresh()] : node.children})} />
      {node.op !== 'not' && depth < contract.maxDepth && <>
        <Button onClick={() => onChange({...node, children: [...node.children!, fresh()]})}>{t('table.query.add', '添加条件')}</Button>
        {depth < contract.maxDepth - 1 && <Button onClick={() => onChange({...node, children: [...node.children!, {op: 'or', children: [fresh()]}]})}>{t('table.query.addGroup', '添加条件组')}</Button>}
      </>}
    </Space>
    {node.children.map((child, index) => <div key={index} style={{display: 'flex', gap: 8, marginTop: 12}}>
      <div style={{flex: 1, minWidth: 0}}><ConditionEditor node={child} contract={contract} properties={properties} depth={depth + 1} renderValue={renderValue} multiline={multiline} preserveGroups={preserveGroups}
        onChange={next => onChange({...node, children: node.children!.map((n, i) => i === index ? next : n)})} /></div>
      <Button disabled={node.op === 'not'} aria-label={t('table.query.remove', '移除条件')}
        onClick={() => onChange({...node, children: node.children!.filter((_, i) => i !== index)})}>×</Button>
    </div>)}
  </div>;
  const field = node.field ?? fields[0];
  const capability = contract.fields[field];
  const schema = properties[field] ?? {};
  const type = Array.isArray(schema.type) ? schema.type.find((v: string) => v !== 'null') : schema.type;
  const options = schema.oneOf?.filter((n: any) => n.const != null).map((n: any) => ({value: String(n.const), label: String(n.title ?? n.const)}))
    ?? schema.enum?.map((v: any) => ({value: String(v), label: String(v)}));
  const input = <QueryValue op={node.op} value={node.value} onChange={value => onChange({...node, value})}
    type={type} format={schema.format} javaType={capability?.javaType} options={options} multiline={multiline} />;
  return <div data-condition-field={field} style={{display: 'grid', gap: 8}}>
    <Space.Compact style={{width: '100%'}}>
      <Select style={{width: '50%'}} showSearch optionFilterProp="label" value={field}
        options={fields.map(value => ({value, label: properties[value]?.title ?? value}))}
        onChange={value => onChange({field: value, op: contract.fields[value].operators[0]})} />
      <Select style={{width: '50%'}} value={node.op} options={capability?.operators.map(value => ({value, label: t(`table.query.operator.${value}`, operatorLabels[value])}))}
        onChange={op => onChange({field, op})} />
    </Space.Compact>
    {renderValue ? renderValue(node, onChange, input) : input}
  </div>;
}

