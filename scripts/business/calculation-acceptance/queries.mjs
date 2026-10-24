import assert from 'node:assert/strict';

/** Server filtering, sorting, counts and pagination use the stored authoritative calculation. */
export async function verifyCalculationQueries({call, spec, state, save}) {
  const query = body => call(spec.runtime + '/records/query', 'POST', body);
  const filter = {operator: 'GREATER_THAN_OR_EQUAL', fieldId: 'total_amount', values: ['5000'], children: []};
  const sorts = [{fieldId: 'total_amount', direction: 'DESC'}];
  const all = await query({page: 0, size: 100, filter, sorts});
  assert(all.total >= 2 && all.total < 100, 'Bounded known acceptance dataset');
  assert.equal(all.records.length, all.total);
  for (let i = 0; i < all.records.length; i++) {
    const row = all.records[i]; assert(Number(row.values.total_amount) >= 5000);
    if (i) assert(Number(all.records[i - 1].values.total_amount) >= Number(row.values.total_amount));
    const detail = await call(spec.runtime + '/records/' + row.id); assert.equal(detail.values.total_amount, row.values.total_amount);
  }
  const ids = [], pages = [];
  for (let page = 0; page < Math.ceil(all.total / 2); page++) {
    const result = await query({page, size: 2, filter, sorts}); assert.equal(result.total, all.total);
    ids.push(...result.records.map(r => r.id)); pages.push({page, ids: result.records.map(r => r.id), total: result.total});
  }
  assert.deepEqual(ids, all.records.map(r => r.id)); assert.equal(new Set(ids).size, ids.length);
  const empty = await query({page: Math.ceil(all.total / 2), size: 2, filter, sorts});
  assert.equal(empty.total, all.total); assert.deepEqual(empty.records, []);
  const defaultPage = await query({page: 0, size: 100});
  for (let i = 1; i < defaultPage.records.length; i++) {
    const previous = Date.parse(defaultPage.records[i - 1].systemValues.createdAt);
    const next = Date.parse(defaultPage.records[i].systemValues.createdAt);
    assert(Number.isFinite(previous) && Number.isFinite(next) && previous >= next, 'Default creation order precedes pagination');
  }
  assert.deepEqual((await query({page: 0, size: 100})).records.map(r => r.id), defaultPage.records.map(r => r.id));
  const subject = defaultPage.records[0].values.subject;
  const exact = await query({page: 0, size: 100, filter: {operator: 'EQUALS', fieldId: 'subject', values: [subject], children: []}});
  assert(exact.total > 0); assert(exact.records.every(row => row.values.subject === subject));
  state.passed = true; state.completedAt = new Date().toISOString(); state.filter = filter; state.pages = pages;
  state.rows = all.records.map(r => ({id: r.id, amount: r.values.total_amount}));
  state.defaultOrder = defaultPage.records.map(r => ({id: r.id, createdAt: r.systemValues.createdAt}));
  state.exactUnicodeSubject = {value: subject, total: exact.total}; await save();
  console.log('PASS calculated result filters, server sorting/pagination/counts and stable newest-first order');
}
