import assert from 'node:assert/strict';
import test from 'node:test';
import {QueryClient, QueryObserver} from '@tanstack/react-query';
import {cancelInactiveEntityRead} from './queryLifecycle.ts';
import {scopedQueryKey} from '../../shared/api/queryScope.ts';

test('unused selector cancellation preserves a shared enabled owner and cancels after its last owner becomes inactive', async () => {
  const client = new QueryClient({defaultOptions: {queries: {retry: false, gcTime: Infinity}}});
  const scope = ['tenant', 'role', 'context'], key = ['entity-select', '/users', 'source-one'];
  let requests = 0, aborts = 0;
  const options = {queryKey: scopedQueryKey(scope, key), queryFn: ({signal}) => {
    requests++;
    return new Promise((resolve, reject) => signal.addEventListener('abort', () => {aborts++; reject(signal.reason);}, {once: true}));
  }};
  const first = new QueryObserver(client, {...options, enabled: true});
  const second = new QueryObserver(client, {...options, enabled: false});
  const unsubscribeFirst = first.subscribe(() => {}), unsubscribeSecond = second.subscribe(() => {});
  try {
    assert.equal(requests, 1);
    await cancelInactiveEntityRead(client, scope, key);
    assert.equal(aborts, 0, 'disabled second widget cannot cancel an enabled shared observer');
    first.setOptions({...options, enabled: false});
    await cancelInactiveEntityRead(client, scope, key);
    assert.equal(aborts, 1);
    first.setOptions({...options, enabled: true});
    assert.equal(requests, 2, 'returning owner starts a fresh usable read');
    await cancelInactiveEntityRead(client, ['tenant', 'role', 'another-context'], key);
    await cancelInactiveEntityRead(client, scope, ['entity-select', '/users', 'another-source']);
    assert.equal(aborts, 1, 'scope/source cancellation remains exact');
  } finally {unsubscribeFirst(); unsubscribeSecond(); client.clear();}
});
