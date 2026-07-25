import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Tag} from 'antd';
import {useMemo} from 'react';

type InvocationConfigKey = 'platform.ai-invocations' | 'tenant.ai-invocations';

type InvocationProps = {
  configKey?: InvocationConfigKey;
};

type InvocationRow = {
  billingCurrency?: string;
};

export const InvocationView = ({configKey = 'platform.ai-invocations'}: InvocationProps) => {
  const {t} = useI18n();
  const columnOverrides = useMemo(() => ({
    status: {
      width: 120,
      render: (value: string) => (
        <Tag color={value === 'SUCCEEDED' ? 'green' : value === 'FAILED' ? 'red' : 'blue'}>
          {t(`ai.invocations.status.${value}`, value || '-')}
        </Tag>
      ),
    },
    operation: {
      width: 130,
      render: (value: string) => t(`ai.invocations.operation.${value}`, value || '-'),
    },
    modelId: {width: 260, ellipsis: true},
    durationMillis: {width: 130},
    inputTokens: {width: 120},
    outputTokens: {width: 120},
    totalTokens: {width: 120},
    billingStatus: {
      width: 130,
      render: (value: string) => {
        const color = value === 'CALCULATED' ? 'gold'
          : value === 'UNPRICED' ? 'orange'
            : value === 'NOT_CHARGED' ? 'default'
              : 'blue';
        return (
          <Tag color={color}>
            {t(`ai.invocations.billingStatus.${value}`, value || '-')}
          </Tag>
        );
      },
    },
    billingCurrency: {width: 100},
    totalCost: {
      width: 150,
      render: (value: number | string | undefined, record: InvocationRow) => (
        value == null ? '-' : `${record.billingCurrency || ''} ${Number(value).toFixed(8)}`
      ),
    },
    startedAt: {width: 190},
  }), [t]);

  return <SimpleTable {...api[configKey]} columnOverrides={columnOverrides} />;
};

export default InvocationView;
