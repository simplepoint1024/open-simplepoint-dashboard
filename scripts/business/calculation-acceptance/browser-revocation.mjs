import assert from 'node:assert/strict';

/** A real retained drawer must not reappear after the same session loses its resource grant. */
export async function verifyActualRetainedRevocation({evaluate, poll, click, screenshot, failures, call, revoke,
  appId, spec, otherPageId, state, save}) {
  const root = '.nb-tab-panel-active', drawer = '.app-record-detail.ant-drawer-open';
  const page = await call(spec.runtime + '/records/query', 'POST', {page: 0, size: 20}); assert(page.records.length);
  const record = page.records[0], route = '#/app/runtime/' + appId + '/' + spec.pageId;
  await evaluate(`location.hash=${JSON.stringify(route)}`);
  const row = root + ` [data-row-key="${record.id}"]`;
  await poll(() => evaluate(`!!document.querySelector('${row} input[type="checkbox"]')`), 'temporarily granted record');
  await evaluate(`document.querySelector('${row} input[type="checkbox"]').click()`); await click(root + ' button', '详情');
  await poll(() => evaluate(`!!document.querySelector('${drawer} #form_main_subject')`), 'owned detail before revocation');
  assert((await evaluate(`document.querySelector('${drawer}').textContent`)).includes(record.values.subject));
  await evaluate(`location.hash=${JSON.stringify('#/app/runtime/' + appId + '/' + otherPageId)}`);
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'retained drawer hidden on other page');
  await revoke(); state.revoked = true; await save();
  await evaluate(`location.hash=${JSON.stringify(route)}`);
  await poll(() => failures.some(item => item.path.includes(spec.pageId) && [403, 404].includes(item.status)), 'server denies retained resource');
  await poll(() => evaluate(`!document.querySelector('${drawer}')&&!document.querySelector('${row}')`), 'revoked cached values removed');
  await screenshot('calculation-retained-revocation');
  const expected = failures.filter(item => item.path.includes(spec.pageId) && [403, 404].includes(item.status));
  for (const item of expected) failures.splice(failures.indexOf(item), 1);
  assert(expected.length); state.denials = expected; state.recordId = record.id;
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS actual resource revocation fences a retained detail in the original login session');
}
