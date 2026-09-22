import {useQuery} from '@tanstack/react-query';
import {get} from '@simplepoint/shared/api/methods';
import {scopedQueryOptions} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {normalizePage} from '@simplepoint/shared/types/request';
import type {RawPage} from '@simplepoint/shared/types/request';

export type ProfileResourceAccess = {
  code: string;
  label?: string;
  type: string;
  path?: string;
  icon?: string;
  effective: boolean;
  available: boolean;
  directory?: string[];
};

export type ProfilePageAccessDetail = {
  page: ProfileResourceAccess;
  operations: ProfileResourceAccess[];
  dataPermissionSupported: boolean;
  fieldPermissionSupported: boolean;
};

export type ProfileOrganization = {
  id?: string | null;
  name?: string | null;
  path: string[];
};

export type ProfileResourceFilters = {
  roleId?: string;
  keyword: string;
  type: string;
  page: number;
  size: number;
};

export async function fetchProfileResources(filters: ProfileResourceFilters, signal?: AbortSignal) {
  const page = await get<RawPage<ProfileResourceAccess>>('/common/users/me/access/resources', filters, {signal});
  return normalizePage(page, filters.size);
}

export function useProfileResources(filters: ProfileResourceFilters, enabled: boolean) {
  const scope = useQueryScope();
  return useQuery({
    ...scopedQueryOptions(scope, ['profile-resources', filters.roleId ?? 'effective', filters.keyword,
      filters.type, filters.page, filters.size], ({signal}) => fetchProfileResources(filters, signal)),
    enabled,
    staleTime: 30 * 1000,
    refetchOnWindowFocus: false,
  });
}

export function useProfilePageDetail(code: string | undefined, roleId: string | undefined, enabled: boolean) {
  const scope = useQueryScope();
  return useQuery({
    ...scopedQueryOptions(scope, ['profile-page-detail', roleId ?? 'effective', code], ({signal}) =>
      get<ProfilePageAccessDetail>('/common/users/me/access/page-detail', {code, roleId}, {signal})),
    enabled: enabled && !!code,
    staleTime: 30 * 1000,
    refetchOnWindowFocus: false,
  });
}

export function useProfileOrganization(enabled: boolean) {
  const scope = useQueryScope();
  return useQuery({
    ...scopedQueryOptions(scope, ['profile-organization'], ({signal}) =>
      get<ProfileOrganization>('/common/users/me/organization', undefined, {signal})),
    enabled,
    staleTime: 60 * 1000,
    refetchOnWindowFocus: false,
  });
}
