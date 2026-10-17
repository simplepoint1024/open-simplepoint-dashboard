import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import SForm from '../SForm';

const fields = [
  ['tenantId', '所属租户'], ['createOrgDeptId', '创建部门'], ['createdBy', '创建人'],
  ['createdAt', '创建时间'], ['updatedBy', '更新人'], ['updatedAt', '更新时间'], ['deletedAt', '删除时间'],
] as const;

/** Only server-projected metadata is displayed; it never joins the editable form's values. */
export default function RecordMetadata({values}: {values?: Record<string, unknown>}) {
  const {t} = useI18n();
  const visible = fields.filter(([field]) => Object.prototype.hasOwnProperty.call(values ?? {}, field));
  if (!visible.length) return null;
  return <SForm hideSubmit disabled formData={values} schema={{type: 'object', additionalProperties: false,
    properties: Object.fromEntries(visible.map(([field, label]) => [field, {
      type: ['string', 'null'], title: t(`app.runtime.record.${field}`, label), readOnly: true, 'x-display-only': true,
      ...(['createdAt', 'updatedAt', 'deletedAt'].includes(field) ? {format: 'date-time'} : {}),
    }])),
  }} formContext={{labelCol: {flex: '140px'}, wrapperCol: {flex: '1 1 0'}, descriptionLocation: 'tooltip'}}/>;
}
