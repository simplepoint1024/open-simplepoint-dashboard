import SimpleTable from "@simplepoint/components/SimpleTable";
import {useEffect} from "react";
import {useI18n} from "@simplepoint/shared/hooks/useI18n";
import {Tag} from "antd";
import api from "@/api";

const baseConfig = api["monitoring-resource-grant-logs"];

const actionColor: Record<string, string> = {
    assign: 'success',
    revoke: 'error',
    update: 'warning',
};

const changeTypeColor: Record<string, string> = {
    role: 'blue',
    resource: 'purple',
    application: 'cyan',
    package: 'geekblue',
};

const App = () => {
    const {ensure, locale, t} = useI18n();

    useEffect(() => {
        void ensure(baseConfig.i18nNamespaces);
    }, [ensure, locale]);

    return (
        <div>
            <SimpleTable
                {...baseConfig}
                columnOverrides={{
                    action: {
                        render: (val: string) => (
                            <Tag color={actionColor[val] ?? 'default'}>
                                {val === 'assign'
                                    ? t('monitoring.resourceGrantLog.action.assign', '授权')
                                    : val === 'revoke'
                                        ? t('monitoring.resourceGrantLog.action.revoke', '撤销')
                                        : val === 'update'
                                            ? t('monitoring.resourceGrantLog.action.update', '更新')
                                            : (val ?? '-')}
                            </Tag>
                        ),
                    },
                    changeType: {
                        render: (val: string) => (
                            <Tag color={changeTypeColor[val] ?? 'default'}>{val ?? '-'}</Tag>
                        ),
                    },
                    description: {
                        ellipsis: true,
                        width: 220,
                    },
                }}
                submitRefreshTargets={{page: true, schema: false}}
                deleteRefreshTargets={{page: true, schema: false}}
            />
        </div>
    );
};

export default App;
