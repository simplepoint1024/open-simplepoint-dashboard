import type {RJSFSchema} from '@rjsf/utils';
import type {TranslateFn} from '@simplepoint/shared/i18n/translator';
export type OrganizationMember = {userId: string; name: string; email?: string; orgId?: string | null; orgName?: string;
  enabled: boolean; userType?: string; revision?: number};
export type UserTypeDraft = {userType?: string};
export type UserTypeOption = {value: string; label: string};
export function identifiableMember(row?: OrganizationMember): boolean {
  return !!row && typeof row.userId === 'string' && !!row.userId.trim() && row.userId !== '***'
    && typeof row.revision === 'number' && Number.isSafeInteger(row.revision) && row.revision >= 0;
}
export function memberTypeSchema(t: TranslateFn, options: UserTypeOption[]): RJSFSchema {
  return {type: 'object', additionalProperties: false, required: ['userType'], properties: {
    userType: {type: 'string', title: t('tenant-members.userType', '用户类型'),
      ...(options.length ? {oneOf: options.map(option => ({const: option.value, title: option.label}))} : {not: {}})},
  }};
}
export function membershipPayload(row: OrganizationMember, orgId: string | null, draft: UserTypeDraft, writableType: boolean) {
  if (!identifiableMember(row) || orgId === '***') throw Error('Member identity or revision unavailable');
  return {orgId, revision: row.revision, ...(writableType ? {userType: draft.userType} : {})};
}
