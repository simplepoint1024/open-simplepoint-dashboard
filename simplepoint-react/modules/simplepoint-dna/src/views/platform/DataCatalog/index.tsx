import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';

const baseConfig = api['platform.dna-federation-catalogs'];

const App = () => {
  return <SimpleTable {...baseConfig}/>;
};

export default App;
