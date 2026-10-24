import SForm from '@simplepoint/components/SForm/Lazy';
import type {SimpleTableFormContext} from '@simplepoint/components/SimpleTable/types';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import TenantResourceSelect from './TenantResourceSelect';
import {resourceDraft, resourceFormSchema, type ResourceDraft, type TenantResource} from './resourceForms';
import type {RJSFSchema} from '@rjsf/utils';

const widgets = {TenantResource: TenantResourceSelect};

/** Contextual navigation fields reuse the standard table's sole draft and write lifecycle. */
export default function ResourceEditor({context, tenantId, localizationSchema}: {context: SimpleTableFormContext; tenantId: string; localizationSchema?: RJSFSchema}) {
  const {t, locale} = useI18n();
  const row = context.editingRecord as TenantResource | null;
  const draft = resourceDraft(context.formData as TenantResource) as ResourceDraft;
  if (localizationSchema) {
    draft.nameLocalization ??= {defaultValue: draft.name ?? '', defaultLocale: locale, translations: {}};
    delete draft.name;
  }
  const localPage = draft.type === 'PAGE' && !row?.platformResourceId;
  return <SForm key={row?.id ?? 'new'} schema={resourceFormSchema(t, row, draft.type, localizationSchema)} widgets={widgets} formData={draft}
    formContext={{localizedFields: {nameLocalization: {owner: `${tenantId}:${row?.id ?? 'new'}`, scope: 'TENANT',
      inheritedKey: row?.title || (row?.alias?.startsWith('i18n:') ? row.alias : undefined), namespace: 'common,menu'}}}}
    disabled={context.disabled} readonly={context.readOnly} submitLoading={context.submitting}
    uiSchema={{parentId: {'ui:widget': 'TenantResource', 'ui:options': {tenantId, kind: 'PARENT', childType: draft.type, excludedId: row?.id}},
      targetResourceId: localPage ? {'ui:widget': 'TenantResource', 'ui:options': {tenantId, kind: 'PAGE_TARGET', childType: draft.type}} : {'ui:widget': 'hidden'},
      path: localPage ? {} : {'ui:widget': 'hidden'}, description: {'ui:widget': 'textarea'}}}
    onChange={event => context.onChange(event.formData ?? {})} onSubmit={event => void context.onSubmit(event.formData ?? {})}/>;
}
