import {pageRoutePath, pageViewLocation, pageViewSelection} from '../../shared/page/viewIdentity.ts';
import {referenceIdentity} from '../FormComponents/Reference/display.ts';

export type ReferenceLocation = {applicationId: string; pageId: string; formId: string; formVersion: number; relationId: string; recordId: string};
const identity = (value: string) => /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(value);

/** The link only names a source relation; target routing and record access are resolved on every read. */
export function formReferenceLocation(source: Omit<ReferenceLocation, 'relationId' | 'recordId'>, relationId: string, recordId: string): string {
  if (![source.applicationId, source.pageId, source.formId, relationId].every(identity)
    || !Number.isSafeInteger(source.formVersion) || source.formVersion < 1 || source.formVersion > 2147483647
    || !referenceIdentity(recordId)) throw new Error('Invalid form reference location');
  return pageViewLocation(`/app/runtime/${source.applicationId}/${source.pageId}`, {version: 1, kind: 'form-reference',
    identity: [source.formId, String(source.formVersion), relationId, referenceIdentity(recordId)!]});
}

export function readFormReferenceLocation(location: string): {state: 'none'} | {state: 'invalid'} | {state: 'reference'; reference: ReferenceLocation} {
  const selection = pageViewSelection(location);
  if (selection.state !== 'view') return selection;
  const path = pageRoutePath(location);
  const route = path.slice(1).split('/');
  const parts = selection.view.identity;
  if (selection.view.kind !== 'form-reference' || parts.length !== 4 || route.length !== 4
    || !path.startsWith('/') || route[0] !== 'app' || route[1] !== 'runtime' || ![route[2], route[3], parts[0], parts[2]].every(identity)
    || !/^[1-9][0-9]{0,9}$/.test(parts[1]) || Number(parts[1]) > 2147483647
    || referenceIdentity(parts[3]) !== parts[3]) return {state: 'invalid'};
  return {state: 'reference', reference: {applicationId: route[2], pageId: route[3], formId: parts[0],
    formVersion: Number(parts[1]), relationId: parts[2], recordId: parts[3]}};
}
