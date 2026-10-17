import {Avatar, Tag} from 'antd';
import {ApartmentOutlined, GlobalOutlined, HomeOutlined} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {EntityOption} from '../EntitySelect/query';
import {tenantLogo, tenantType, type TenantType} from './model';
import './style.css';

/** Shared by selectors and the active workspace. Images are decorative; adjacent text names it. */
export function TenantMark({type, logo, size = 20, className = ''}: {
  type?: TenantType; logo?: unknown; size?: number; className?: string;
}) {
  return <Avatar className={`sp-tenant-mark ${className}`} shape="square" size={size} aria-hidden="true"
    src={tenantLogo(logo)} alt="" icon={type === 'PLATFORM' ? <GlobalOutlined/>
      : type === 'PERSONAL' ? <HomeOutlined/> : <ApartmentOutlined/>}/>;
}

export function TenantTypeTag({type}: {type?: TenantType}) {
  const {t} = useI18n();
  const label = type === 'PLATFORM' ? t('tenant.type.platform', '平台')
    : type === 'PERSONAL' ? t('tenant.type.personal', '个人')
      : type === 'ORGANIZATION' ? t('tenant.type.organization', '组织') : t('tenant.type.unknown', '租户');
  return <Tag className="sp-tenant-type" color={type === 'PLATFORM' ? 'purple'
    : type === 'PERSONAL' ? 'blue' : type === 'ORGANIZATION' ? 'green' : undefined}>{label}</Tag>;
}

export function TenantIdentity({option}: {option: EntityOption}) {
  const type = tenantType(option.metadata?.type);
  return <span className="sp-tenant-identity" data-tenant-type={type ?? 'UNKNOWN'}>
    <TenantMark type={type} logo={option.metadata?.logo}/>
    <span className="sp-tenant-name" title={option.label}>{option.label}</span>
    <TenantTypeTag type={type}/>
  </span>;
}
