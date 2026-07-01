import api from '@/api/index';
import SimpleTable from "@simplepoint/components/SimpleTable";
import {useEffect} from "react";
import {useI18n} from '@simplepoint/shared/hooks/useI18n';


const baseConfig = api['oidc-clients'];
const App = () => {
    // 国际化
    const {ensure, locale} = useI18n();
    // 确保本页所需命名空间加载（roles），语言切换后也会自动增量加载
    useEffect(() => {
        void ensure(baseConfig.i18nNamespaces);
    }, [ensure, locale]);
  return (
    <div>
      <SimpleTable{...baseConfig}/>
    </div>
  );
};

export default App;