import SimpleTable from "@simplepoint/components/SimpleTable";
import api from '@/api/index';
import {useEffect} from "react";
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

const baseConfig = api['rbac-permissions'];
const App = () => {
    // 国际化
    const {ensure, locale} = useI18n();
    // 确保本页所需命名空间加载（roles），语言切换后也会自动增量加载
    useEffect(() => {
        void ensure(baseConfig.i18nNamespaces);
    }, [ensure, locale]);
    return (
        <div>
            <SimpleTable
                {...baseConfig}
                columnOverrides={{
                    authority: { width: 260, ellipsis: true, order: 1 },
                    resource: { width: 260, ellipsis: true, order: 2 },
                    description: { ellipsis: true, order: 3 },
                }}
                submitRefreshTargets={{page: true, schema: false}}
                deleteRefreshTargets={{page: true, schema: false}}
            />
        </div>
    );
};

export default App;