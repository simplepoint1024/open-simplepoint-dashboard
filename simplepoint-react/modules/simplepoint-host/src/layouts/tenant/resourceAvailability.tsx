import StatusTag from '@simplepoint/components/StatusTag';
import type {SimpleTableColumnOverride} from '@simplepoint/components/SimpleTable/types';
import type {TenantResource} from './resourceForms';
import {resolveLocalizedText} from '@simplepoint/shared/i18n/localizedText';

type Translate = (key: string, fallback?: string) => string;
/** Resource availability is tenant-wide; it never claims that an ordinary user holds a role grant. */
export function resourceAvailabilityColumns(t: Translate, locale = 'zh-CN'): Record<string, SimpleTableColumnOverride<TenantResource>> {
  const states: Record<string, string> = {
    AVAILABLE: t('tenant.resources.availability.AVAILABLE'), OFFLINE: t('tenant.resources.availability.OFFLINE'),
    DISABLED: t('tenant.resources.availability.DISABLED'), SOURCE_UNAVAILABLE: t('tenant.resources.availability.SOURCE_UNAVAILABLE'),
    NO_ENTITLEMENT: t('tenant.resources.availability.NO_ENTITLEMENT'), NOT_STARTED: t('tenant.resources.availability.NOT_STARTED'),
    EXPIRED: t('tenant.resources.availability.EXPIRED'), DEPENDENCY_UNAVAILABLE: t('tenant.resources.availability.DEPENDENCY_UNAVAILABLE'),
  };
  const kinds: Record<string, string> = {
    BASELINE: t('tenant.resources.entitlementKind.BASELINE'), TIMED: t('tenant.resources.entitlementKind.TIMED'),
    UNLIMITED: t('tenant.resources.entitlementKind.UNLIMITED'), NOT_REQUIRED: t('tenant.resources.entitlementKind.NOT_REQUIRED'),
    NONE: t('tenant.resources.entitlementKind.NONE'),
  };
  return {
    name: {render: (value, row) => resolveLocalizedText(row.nameLocalization, locale,
      row.title ? t(row.title.replace(/^i18n:/, ''), '') : undefined, 'TENANT', String(value ?? '')).text},
    availability: {render: value => <StatusTag label={states[value] ?? value ?? '—'}
      tone={value === 'AVAILABLE' ? 'success' : ['NO_ENTITLEMENT', 'EXPIRED', 'DEPENDENCY_UNAVAILABLE', 'SOURCE_UNAVAILABLE'].includes(value) ? 'warning' : 'neutral'}/>},
    entitlementKind: {render: value => kinds[value] ?? value ?? '—'},
  };
}
