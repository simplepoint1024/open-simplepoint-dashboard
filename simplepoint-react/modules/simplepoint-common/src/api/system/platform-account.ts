import {get, post, put} from '@simplepoint/shared/api/methods';
import {contextPath} from '@/services';

const base = `${contextPath}/platform/accounts`;
export type PlatformRole = 'PLATFORM_ADMIN' | 'ACCOUNT_ADMIN' | 'AUDITOR';
export interface PlatformAccount {
  id: string;
  name?: string;
  email?: string;
  enabled: boolean;
  superAdmin: boolean;
  roles: PlatformRole[];
  revision: number;
}
export interface PlatformAccountCommand {
  name?: string;
  email?: string;
  initialPassword?: string;
  enabled?: boolean;
  superAdmin?: boolean;
  roles?: PlatformRole[];
  revision?: number;
  reason: string;
  confirmationPassword: string;
  confirmationCode?: string;
}
export interface PlatformAudit {
  id: string;
  actorId: string;
  targetId: string;
  action: string;
  beforeState?: string;
  afterState?: string;
  reason: string;
  occurredAt: string;
}
export const fetchCapabilities = () => get<{permissions: string[]; twoFactorEnabled: boolean}>(`${base}/capabilities`);
export const createAccount = (command: PlatformAccountCommand) => post<PlatformAccount>(base, command);
export const updateAccount = (id: string, command: PlatformAccountCommand) => put<PlatformAccount>(`${base}/${encodeURIComponent(id)}`, command);
export const assignPlatformIdentity = (id: string, command: PlatformAccountCommand) => put<PlatformAccount>(`${base}/${encodeURIComponent(id)}/identity`, command);
