import React from 'react';
import {Result} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import {useNavigate} from 'react-router';
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
          <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'home', sort: 0, type: 'primary',
            text: t('error.backHome', '返回首页'), onClick: () => navigate('/dashboard')}]}/>
        }
      />
    </div>
  );
};
