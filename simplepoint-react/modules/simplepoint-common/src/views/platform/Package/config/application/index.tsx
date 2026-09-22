import {useEffect, useMemo} from 'react';
import type {TableColumnsType} from 'antd';
import AssignmentTable from '@simplepoint/components/AssignmentTable';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import api from '@/api';
import {fetchAuthorize, fetchUnauthorized} from '@/api/platform/package';
import type {ApplicationRelevantVo} from '@/api/platform/application';

export interface PackageApplicationConfigProps {
  packageCode: string;
}

const App = ({packageCode}: PackageApplicationConfigProps) => {
  const {t, messages, ensure, locale} = useI18n();
  useEffect(() => {void ensure(['applications', 'table', 'common']);}, [ensure, locale]);
  const columns = useMemo<TableColumnsType<ApplicationRelevantVo>>(() => [
    {key: 'name', dataIndex: 'name', title: t('applications.title.name')},
    {key: 'code', dataIndex: 'code', title: t('applications.title.code')},
    {key: 'description', dataIndex: 'description', title: t('applications.title.description')},
  ], [messages, t]);
  if (!packageCode) return <div/>;
  return <AssignmentTable<ApplicationRelevantVo> endpoint={`${api['platform.applications'].baseUrl}/items`}
    parent={{packageCode}} itemKey="code" columns={columns} storageKey="platform.applications.assignment"
    onMove={(selected, keys) => selected
      ? fetchUnauthorized({packageCode, applicationCodes: keys})
      : fetchAuthorize({packageCode, applicationCodes: keys})}/>;
};

export default App;
