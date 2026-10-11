import {useCallback} from 'react';
import {useQueryClient} from '@tanstack/react-query';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';

/** Invalidate both tabs and personal matters after a decision; never replace another open form. */
export function useWorkflowRefresh() {
  const client = useQueryClient(), scope = useQueryScope();
  return useCallback(async () => {
    await Promise.all([
      ...['/workflow/inbox/pending', '/workflow/inbox/completed', '/workflow/matters/started',
        '/workflow/matters/copied', '/workflow/matters/all'].map(endpoint =>
        client.invalidateQueries({queryKey: scopedQueryKey(scope, [endpoint])})),
      client.invalidateQueries({queryKey: scopedQueryKey(scope, ['workflow', 'diagram'])}),
      client.invalidateQueries({queryKey: scopedQueryKey(scope, ['workflow', 'progress'])}),
      client.invalidateQueries({queryKey: scopedQueryKey(scope, ['workflow', 'node-history'])}),
      client.invalidateQueries({queryKey: scopedQueryKey(scope, ['workflow', 'actions'])}),
      client.invalidateQueries({queryKey: scopedQueryKey(scope, ['workflow', 'overview'])}),
    ]);
  }, [client, scope]);
}
