import {resolveLocalizedText, type LocalizedText} from '../../shared/i18n/localizedText.ts';
import type {ApplicationFormDocument, FormComponentDefinition, FormFieldDefinition} from './contracts.ts';

/** Display-only projection: identities, formulas, values and canonical snapshots are untouched. */
export function localizeFormDocument(document: ApplicationFormDocument, locale: string): ApplicationFormDocument {
  const label = (item: {label?: string | null; labelLocalization?: LocalizedText | null}) =>
    item.labelLocalization ? resolveLocalizedText(item.labelLocalization, locale, undefined, 'TENANT', item.label ?? '').text : item.label;
  const field = (item: FormFieldDefinition): FormFieldDefinition => ({...item, label: label(item)!,
    choices: item.choices.map(option => ({...option, label: label(option)!}))});
  const fields = new Map(document.fields.map(item => [item.fieldId, item]));
  const details = new Map((document.details ?? []).map(item => [item.detailId, item]));
  const component = (item: FormComponentDefinition): FormComponentDefinition => {
    const owner = fields.get(item.fieldId ?? '') ?? details.get(item.properties.detailId?.value ?? '');
    // Legacy controls copied the field's default title. An explicit authored override wins.
    const title = !item.labelLocalization && owner && (!item.label || item.label === owner.label) ? label(owner) : label(item);
    return {...item, label: title, children: item.children.map(component)};
  };
  return {...document, fields: document.fields.map(field),
    ...(document.details ? {details: document.details.map(item => ({...item, label: label(item)!, fields: item.fields.map(field)}))} : {}),
    scenes: document.scenes.map(scene => ({...scene, root: component(scene.root)}))};
}
