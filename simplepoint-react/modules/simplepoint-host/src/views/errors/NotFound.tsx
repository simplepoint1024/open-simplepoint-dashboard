import React from 'react';
import {Button, Result} from 'antd';
import {useNavigate} from 'react-router-dom';
import {useI18n} from '@/layouts/i18n/useI18n';

export const NotFound: React.FC = () => {
  const navigate = useNavigate();
  const {t} = useI18n();
  return (
    <div style={{display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', minHeight: 320}}>
      <Result
        status="404"
        title="404"
        subTitle={t('error.404', '抱歉，您访问的页面不存在')}
        extra={
          <Button type="primary" onClick={() => navigate('/dashboard')}>
            {t('error.backHome', '返回首页')}
          </Button>
        }
      />
    </div>
  );
};
