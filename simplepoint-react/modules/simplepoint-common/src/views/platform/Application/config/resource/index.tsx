import {useCallback, useEffect} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {fetchAuthorized, fetchAuthorize, fetchUnauthorized} from '@/api/platform/application';
import ResourceTreeTransfer from '@/views/system/Resource/components/ResourceTreeTransfer';
import type {RequestOptions} from '@simplepoint/shared/api/client';

export interface ApplicationResourceConfigProps {
  applicationCode: string;
  onInteractionLockChange?: (locked: boolean) => void;
}

const App = ({applicationCode, onInteractionLockChange}: ApplicationResourceConfigProps) => {
  const {ensure, locale} = useI18n();

  useEffect(() => {
    void ensure(['resources', 'applications', 'table', 'common']);
  }, [ensure, locale]);

  const fetchAssignedCodes = useCallback(
    (options: RequestOptions) => fetchAuthorized({applicationCode}, options),
    [applicationCode],
  );

  const handleAssign = useCallback(
    (resourceCodes: string[], options: RequestOptions) => fetchAuthorize({applicationCode, resourceCodes}, options),
    [applicationCode],
  );

  const handleUnassign = useCallback(
    (resourceCodes: string[], options: RequestOptions) => fetchUnauthorized({applicationCode, resourceCodes}, options),
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
      onInteractionLockChange={onInteractionLockChange}
    />
  );
};

export default App;
