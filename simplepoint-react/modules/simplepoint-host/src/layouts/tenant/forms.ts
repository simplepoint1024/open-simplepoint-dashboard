import type {RJSFSchema} from '@rjsf/utils';
import type {CurrentTenantProfile} from '@/fetches/tenants';

export type TenantProfileDraft = Pick<CurrentTenantProfile, 'name' | 'description' | 'logo' | 'backgroundImage'>;
export const profileDraft = (profile: CurrentTenantProfile): TenantProfileDraft => ({name: profile.name,
  description: profile.description ?? '', logo: profile.logo ?? '', backgroundImage: profile.backgroundImage ?? ''});

export function profileSchema(t: (key: string, fallback?: string) => string, appearance: boolean): RJSFSchema {
  return appearance ? {type: 'object', additionalProperties: false, properties: {
    logo: {type: ['string', 'null'], title: t('tenant.profile.logo', '空间 Logo'), maxLength: 1024},
    backgroundImage: {type: ['string', 'null'], title: t('tenant.profile.background', '空间背景图片'), maxLength: 1024},
  }} : {type: 'object', additionalProperties: false, required: ['name'], properties: {
    name: {type: 'string', title: t('tenant.profile.name', '空间名称'), minLength: 1, maxLength: 128, pattern: '\\S'},
    description: {type: ['string', 'null'], title: t('tenant.profile.description', '空间简介'), maxLength: 512},
  }};
}
