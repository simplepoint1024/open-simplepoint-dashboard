import SimpleTable from "@simplepoint/components/SimpleTable";
import {useEffect} from "react";
import {useI18n} from "@simplepoint/shared/hooks/useI18n";
import {Tag} from "antd";
import api from "@/api";

const baseConfig = api["monitoring-login-logs"];

const statusColor: Record<string, string> = {
    success: 'success',
    failure: 'error',
};

const loginTypeColor: Record<string, string> = {
    password: 'blue',
    oidc: 'purple',
    phone: 'cyan',
};

const App = () => {
    const {t, ensure, locale} = useI18n();

    useEffect(() => {
        void ensure(['common', ...baseConfig.i18nNamespaces]);
    }, [ensure, locale]);

    return (
        <div>
            <SimpleTable
                {...baseConfig}
                columnOverrides={{
                    status: {
                        render: (val: string) => (
                            <Tag color={statusColor[val] ?? 'default'}>
                                {val === 'success'
                                    ? t('status.success', '成功')
                                    : val === 'failure'
                                        ? t('status.failure', '失败')
                                        : val ?? '-'}
                            </Tag>
                        ),
                    },
                    loginType: {
                        render: (val: string) => val ? (
                            <Tag color={loginTypeColor[val] ?? 'default'}>{val}</Tag>
                        ) : '-',
                    },
                }}
                submitRefreshTargets={{page: true, schema: false}}
                deleteRefreshTargets={{page: true, schema: false}}
            />
        </div>
    );
};

export default App;
