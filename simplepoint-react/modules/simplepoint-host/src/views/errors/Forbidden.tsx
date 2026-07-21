import React from 'react';
import {Button, Result} from 'antd';
import {useNavigate} from 'react-router';
import {useI18n} from '@/layouts/i18n/useI18n';

export const Forbidden: React.FC = () => {
  const navigate = useNavigate();
  const {t} = useI18n();
  return (
    <div style={{display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', minHeight: 320}}>
      <Result
        status="403"
        title="403"
        subTitle={t('error.403', '抱歉，您没有访问该页面的权限')}
        extra={
          <Button type="primary" onClick={() => navigate('/dashboard')}>
            {t('error.backHome', '返回首页')}
          </Button>
        }
      />
    </div>
  );
};
