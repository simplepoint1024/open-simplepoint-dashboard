import React from 'react';
import {Button, Result} from 'antd';
import {useNavigate} from 'react-router';
import {useI18n} from '@/layouts/i18n/useI18n';

export const OrgTenantRequired: React.FC = () => {
  const navigate = useNavigate();
  const {t} = useI18n();
  return (
    <div style={{display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', minHeight: 320}}>
      <Result
        status="403"
        title={t('error.orgTenantRequired.title', '需要组织租户')}
        subTitle={t('error.orgTenantRequired.subtitle', '此功能需要在组织租户下使用，个人工作区暂不支持访问该功能。')}
        extra={
          <Button type="primary" onClick={() => navigate('/dashboard')}>
            {t('error.backHome', '返回首页')}
          </Button>
        }
      />
    </div>
  );
};
