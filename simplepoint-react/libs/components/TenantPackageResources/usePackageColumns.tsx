import {useMemo} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {TenantPackage} from '@simplepoint/shared/types/tenantPackage';
import type {SimpleTableColumnOverride} from '../SimpleTable/types';
import StatusTag from '../StatusTag';

/** Owner and platform administration show the same entitlement provenance and status semantics. */
export function usePackageColumns(): Record<string, SimpleTableColumnOverride<TenantPackage>> {
  const {t} = useI18n();
  return useMemo(() => {
    const acquisition: Record<string, string> = {
      ASSIGNED: t('tenant.packages.acquisition.ASSIGNED'),
      LEGACY_PACKAGE: t('tenant.packages.acquisition.LEGACY_PACKAGE'),
      LEGACY_PUBLIC: t('tenant.packages.acquisition.LEGACY_PUBLIC'),
      LEGACY_ROLE: t('tenant.packages.acquisition.LEGACY_ROLE'),
      LEGACY_HOST: t('tenant.packages.acquisition.LEGACY_HOST'),
    };
    const states: Record<string, string> = {PENDING: t('tenant.packages.state.PENDING'),
      ACTIVE: t('tenant.packages.state.ACTIVE'), REVOKED: t('tenant.packages.state.REVOKED')};
    return {
      packageCode: {render: value => value || '—'},
      packageName: {render: (value, row) => value || acquisition[row.acquisition] || '—'},
      state: {render: value => <StatusTag label={states[value] ?? value ?? '—'}
        tone={value === 'ACTIVE' ? 'success' : value === 'PENDING' ? 'warning' : 'neutral'}/>},
      durationDays: {render: value => value == null ? t('tenant.packages.entitlement.UNLIMITED') : value},
    };
  }, [t]);
}
