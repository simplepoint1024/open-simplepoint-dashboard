import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';
import {tenantDirectoryLookupBatches, tenantRoleAuthoritySelectable} from './query.ts';

test('role selection uses the shared native CSV/form-contract cases without trimming actual authorities', () => {
  const cases = JSON.parse(readFileSync(new URL('./roleAuthorities.fixture.json', import.meta.url), 'utf8'));
  for (const item of cases) assert.equal(tenantRoleAuthoritySelectable(item.value), item.selectable, JSON.stringify(item.value));
});

test('selected authorities retain Unicode and order without oversized request lines', () => {
  const ids = Array.from({length: 50}, (_, index) => `${index}-` + '审核'.repeat(40));
  const batches = tenantDirectoryLookupBatches([...ids, ids[0]]);
  assert.deepEqual(batches.flat(), ids);
  assert.ok(batches.every(batch => batch.length <= 50
    && new URLSearchParams({values: batch.join(',')}).toString().length <= 2500));
  assert.deepEqual(tenantDirectoryLookupBatches([]), []);
});
