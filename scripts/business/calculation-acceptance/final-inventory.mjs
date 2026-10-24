import assert from 'node:assert/strict';

/** Read every acceptance submission page; terminal negative evidence remains identifiable. */
export async function verifyFinalBusinessInventory({call, forms, failedSubmissionId, state, save}) {
  state.forms = [];
  for (const [name, spec] of Object.entries(forms)) {
    const records = await call(spec.runtime + '/records/query', 'POST', {page: 0, size: 1});
    assert(records.total > 0);
    const submissions = [];
    for (let page = 0; ; page++) {
      const result = await call(spec.runtime + '/submissions?page=' + page + '&size=100');
      submissions.push(...result.content);
      if (page + 1 >= result.page.totalPages) break;
    }
    assert(submissions.length > 0, name + ' retains actual approval evidence');
    for (const submission of submissions) {
      if (submission.status === 'FAILED') {
        assert.equal(submission.id, failedSubmissionId, 'Only the deliberate immutable constraint rejection remains');
        assert(!submission.processInstanceId);
      } else {
        assert.equal(submission.status, 'SUCCEEDED', name + ' has no unfinished submission');
        assert(['COMPLETED', 'REJECTED', 'WITHDRAWN', 'CANCELED'].includes(submission.processStatus), name + ' has no abandoned running flow');
      }
    }
    state.forms.push({name, pageId: spec.pageId, recordCount: records.total,
      submissions: submissions.map(({id, status, processInstanceId, processStatus}) => ({id, status, processInstanceId, processStatus}))});
    await save();
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS complete submission pagination: retained acceptance records, no abandoned submission or running flow');
}
