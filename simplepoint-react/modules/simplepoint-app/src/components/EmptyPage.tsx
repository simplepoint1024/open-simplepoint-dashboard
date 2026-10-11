import {Card, Empty, Typography} from 'antd';
import {useEffect} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

type EmptyPageProps = {
  title: string;
};

export default function EmptyPage({title}: EmptyPageProps) {
  const {t, ensure, locale} = useI18n();

  useEffect(() => {
    void ensure(['app-workbench']);
  }, [ensure, locale]);

  return (
    <section style={{height: '100%', minHeight: 0, padding: 24, boxSizing: 'border-box'}}>
      <Card style={{height: '100%'}}>
        <Typography.Title level={4}>{title}</Typography.Title>
        <Empty style={{marginTop: 80}} description={t('app.page.empty', '暂无内容')}/>
      </Card>
    </section>
  );
}
