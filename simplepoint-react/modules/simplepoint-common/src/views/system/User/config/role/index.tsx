import AssignmentTable from '@simplepoint/components/AssignmentTable';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import api from '@/api';
import type {RoleRelevantVo} from '@/api/system/role';
import {fetchAuthorize, fetchUnauthorized} from '@/api/system/user';

export interface RoleSelectProps {
  userId: string | null;
  disabled?: boolean;
  onInteractionLockChange?: (locked: boolean) => void;
}

export default function RoleSelect({userId, disabled, onInteractionLockChange}: RoleSelectProps) {
  const {t} = useI18n();
  if (!userId) return null;
  return <AssignmentTable<RoleRelevantVo>
    endpoint={`${api['rbac-users'].baseUrl}/role-candidates`} parent={{userId}} itemKey="id"
    storageKey="system.users.roles.assignment"
    disabled={disabled} onInteractionLockChange={onInteractionLockChange}
    columns={[
      {key: 'name', dataIndex: 'name', title: t('roles.title.roleName', '角色名称')},
      {key: 'authority', dataIndex: 'authority', title: t('roles.title.authority', '角色权限')},
      {key: 'description', dataIndex: 'description', title: t('roles.title.description', '描述')},
      {key: 'id', dataIndex: 'id', title: t('roles.title.id', '角色标识')},
    ]}
    onMove={(selected, roleIds, options) => selected ? fetchUnauthorized({userId, roleIds}, options) : fetchAuthorize({userId, roleIds}, options)}
  />;
}
