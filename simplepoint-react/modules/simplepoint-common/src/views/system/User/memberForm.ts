import {identifiableMember, type OrganizationMember} from '../../platform/Organization/memberForms.ts';

export type Member = OrganizationMember;
export type MemberCommand = {email?: string; orgId?: string | null; enabled?: boolean; revision?: number; userType?: string};
export {identifiableMember};

export function memberDraft(member?: Member): MemberCommand {
  return member ? {orgId: member.orgId ?? undefined, enabled: member.enabled, userType: member.userType} : {userType: 'INTERNAL'};
}

/** Compare editable values, not RJSF's insertion order or absent optional organization value. */
export function memberDraftChanged(initial: MemberCommand, next: MemberCommand): boolean {
  return initial.email !== next.email || (initial.orgId ?? null) !== (next.orgId ?? null)
    || initial.enabled !== next.enabled || initial.userType !== next.userType;
}

/** The update API replaces orgId even when it is omitted: retain captured read-only values. */
export function memberCommand(mode: 'add' | 'edit', row: Member | undefined, draft: MemberCommand,
  writable: (field: string) => boolean): MemberCommand {
  const result: MemberCommand = {};
  if (mode === 'add') {
    if (!writable('email') || typeof draft.email !== 'string' || !draft.email.trim()) throw Error('Member email unavailable');
    result.email = draft.email.trim();
  } else {
    if (!identifiableMember(row) || !Object.prototype.hasOwnProperty.call(row, 'orgId') || row?.orgId === '***')
      throw Error('Member identity, organization or revision unavailable');
    result.revision = row!.revision;
    result.orgId = writable('orgId') ? draft.orgId ?? null : row!.orgId ?? null;
  }
  if (writable('orgId')) result.orgId = draft.orgId ?? null;
  if (result.orgId != null && (typeof result.orgId !== 'string' || result.orgId === '***')) throw Error('Organization unavailable');
  if (writable('userType') && draft.userType !== undefined) {
    if (!['INTERNAL', 'EXTERNAL', 'REFERENCE'].includes(draft.userType)) throw Error('User type unavailable');
    result.userType = draft.userType;
  }
  if (mode === 'edit' && writable('enabled') && draft.enabled !== undefined) {
    if (typeof draft.enabled !== 'boolean') throw Error('Member enabled state unavailable');
    result.enabled = draft.enabled;
  }
  return result;
}
