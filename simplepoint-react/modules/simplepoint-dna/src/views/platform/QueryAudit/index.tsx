import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {Tag} from 'antd';
import {useMemo} from 'react';
import {formatDateTime} from '../shared';

const baseConfig = api['platform.dna-federation-query-audits'];

const statusColorMap: Record<string, string> = {
  SUCCESS: 'green',
  FAILED: 'red',
  REJECTED: 'gold',
};

const App = () => {
  const columnOverrides = useMemo(() => ({
    status: {
      width: 120,
      render: (value: string) => <Tag color={statusColorMap[value] || 'default'}>{value || '-'}</Tag>,
    },
    executedAt: {
      width: 180,
      render: formatDateTime,
    },
    queryText: {
      width: 320,
      ellipsis: true,
    },
    pushdownSummary: {
      width: 260,
      ellipsis: true,
    },
    errorMessage: {
      width: 240,
      ellipsis: true,
    },
  }), []);

  return <SimpleTable {...baseConfig} columnOverrides={columnOverrides}/>;
};

export default App;
