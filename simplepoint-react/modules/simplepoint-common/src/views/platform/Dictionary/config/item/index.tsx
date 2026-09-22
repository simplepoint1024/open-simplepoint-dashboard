import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTablePageRequest} from '@simplepoint/components/SimpleTable/types';
import {get} from '@simplepoint/shared/api/methods';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {useCallback, useMemo} from 'react';

const baseConfig = api['platform.dictionary-items'];

export interface DictionaryItemConfigProps {
  dictionaryCode: string;
}

const App = ({dictionaryCode}: DictionaryItemConfigProps) => {
  const loadPage = useCallback(async ({page, size, filters, sort, signal}: SimpleTablePageRequest) => (
    normalizePage(await get<RawPage<any>>(baseConfig.baseUrl, {
      ...filters, parentDictionaryCode: dictionaryCode, page, size, ...(sort ? {sort} : {}),
    }, {signal}))
  ), [dictionaryCode]);
  const formUiSchema = useMemo(
    () => ({
      dictionaryCode: {
        'ui:widget': 'hidden',
      },
    }),
    []
  );

  if (!dictionaryCode) {
    return <div style={{flex: 1, minHeight: 0}}/>;
  }

  return (
    <SimpleTable
      {...baseConfig}
      key={dictionaryCode}
      queryContext={dictionaryCode}
      loadPage={loadPage}
      initialValues={{dictionaryCode}}
      formUiSchema={formUiSchema}
    />
  );
};

export default App;
