import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {useMemo} from 'react';

const baseConfig = api['platform.dictionary-items'];

export interface DictionaryItemConfigProps {
  dictionaryCode: string;
}

const App = ({dictionaryCode}: DictionaryItemConfigProps) => {
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
      initialFilters={{dictionaryCode: `equals:${dictionaryCode}`}}
      initialValues={{dictionaryCode}}
      formUiSchema={formUiSchema}
    />
  );
};

export default App;
