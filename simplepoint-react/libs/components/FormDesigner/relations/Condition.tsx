import {useMemo} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {resolveLocalizedText} from '@simplepoint/shared/i18n/localizedText';
import ConditionEditor from '../../Table/ConditionEditor';
import QueryValue from '../../Table/QueryValue';
import type {QueryNode} from '../../Table/query';
import type {RelationTargetMetadata} from './catalog';
import {relationConditionDocument} from './edit';
import {ruleQueryContract} from '../ruleModel';

/** Candidate browsing and write eligibility deliberately use separate instances of this editor. */
export default function RelationCondition({metadata, value, disabled, onChange}: {
  metadata: RelationTargetMetadata; value: QueryNode; disabled: boolean; onChange: (value: QueryNode) => void;
}) {
  const {locale} = useI18n();
  const contract = useMemo(() => ruleQueryContract(relationConditionDocument(metadata)), [metadata]);
  const properties = useMemo(() => Object.fromEntries(metadata.fields.map(field => [field.fieldId, {
    title: resolveLocalizedText(field.labelLocalization, locale, undefined, 'TENANT', field.label).text,
    type: field.type === 'BOOLEAN' ? 'boolean' : 'string',
  }])), [metadata, locale]);
  return <fieldset disabled={disabled} style={{border: 0, padding: 0, margin: 0, pointerEvents: disabled ? 'none' : undefined}}>
    <ConditionEditor contract={contract} properties={properties} preserveGroups multiline
      node={value.children ? value : {op: 'and', children: [value]}}
      renderValue={(node, change) => <QueryValue op={node.op} value={node.value} multiline maxLength={2000}
        type={properties[node.field ?? '']?.type} javaType={contract.fields[node.field ?? '']?.javaType}
        onChange={value => {if (!disabled) change({...node, value});}}/>}
      onChange={next => {if (!disabled) onChange(next);}}/>
  </fieldset>;
}
