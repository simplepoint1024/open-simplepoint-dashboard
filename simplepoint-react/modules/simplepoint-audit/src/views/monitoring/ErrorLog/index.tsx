import SimpleTable from "@simplepoint/components/SimpleTable";
import {useEffect} from "react";
import {useI18n} from "@simplepoint/shared/hooks/useI18n";
import {Tag} from "antd";
import api from "@/api";

const baseConfig = api["monitoring-error-logs"];

const levelColor: Record<string, string> = {
    ERROR: 'error',
    WARN: 'warning',
    INFO: 'processing',
    DEBUG: 'default',
};

const App = () => {
    const {ensure, locale} = useI18n();

    useEffect(() => {
        void ensure(baseConfig.i18nNamespaces);
    }, [ensure, locale]);

    return (
        <div>
            <SimpleTable
                {...baseConfig}
                columnOverrides={{
                    level: {
                        render: (val: string) => (
                            <Tag color={levelColor[val] ?? 'default'}>{val ?? '-'}</Tag>
                        ),
                    },
                    message: {
                        ellipsis: true,
                        width: 260,
                    },
                    loggerName: {
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
