import api from '@/api/index';
import SimpleTable from '@simplepoint/components/SimpleTable';

const baseConfig = api['platform.account-audit'];

export default function PlatformAccountAudit() {
  return <SimpleTable {...baseConfig}/>;
}
