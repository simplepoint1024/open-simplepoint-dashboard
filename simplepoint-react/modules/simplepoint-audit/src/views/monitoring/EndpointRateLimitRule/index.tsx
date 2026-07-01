import SimpleTable from "@simplepoint/components/SimpleTable";
import {useEffect} from "react";
import {useI18n} from "@simplepoint/shared/hooks/useI18n";
import api from "@/api";

const baseConfig = api["monitoring-endpoint-rate-limit-rules"];

const App = () => {
    const {ensure, locale} = useI18n();

    useEffect(() => {
        void ensure(baseConfig.i18nNamespaces);
    }, [ensure, locale]);

    return (
        <div>
            <SimpleTable
                {...baseConfig}
                beforeSubmit={({formData}) => {
                    const next = {...formData};
                    ["name", "serviceId", "pathPattern", "httpMethod", "keyStrategy", "description"].forEach((key) => {
                        if (typeof next[key] === "string") {
                            next[key] = next[key].trim();
                        }
                    });
                    return next;
                }}
                submitRefreshTargets={{page: true, schema: false}}
                deleteRefreshTargets={{page: true, schema: false}}
            />
        </div>
    );
};

export default App;
