import {useCallback, useEffect} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {fetchAuthorized, fetchAuthorize, fetchUnauthorized} from '@/api/platform/application';
import ResourceTreeTransfer from '@/views/system/Resource/components/ResourceTreeTransfer';

export interface ApplicationResourceConfigProps {
  applicationCode: string;
}

const App = ({applicationCode}: ApplicationResourceConfigProps) => {
  const {ensure, locale} = useI18n();

  useEffect(() => {
    void ensure(['resources', 'applications', 'table', 'common', 'access-center']);
  }, [ensure, locale]);

  const fetchAssignedCodes = useCallback(
    () => fetchAuthorized({applicationCode}),
    [applicationCode],
  );

  const handleAssign = useCallback(
    (resourceCodes: string[]) => fetchAuthorize({applicationCode, resourceCodes}),
    [applicationCode],
  );

  const handleUnassign = useCallback(
    (resourceCodes: string[]) => fetchUnauthorized({applicationCode, resourceCodes}),
    [applicationCode],
  );

  return (
    <ResourceTreeTransfer
      key={applicationCode}
      enabled={!!applicationCode}
      reloadKey={applicationCode}
      fetchAssignedCodes={fetchAssignedCodes}
      onAssign={handleAssign}
      onUnassign={handleUnassign}
    />
  );
};

export default App;
