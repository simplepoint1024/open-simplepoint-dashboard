import assert from 'node:assert/strict';

/** Unsupported executable/collaboration elements are rejected without changing a published flow. */
export async function verifyWorkflowRejections({call, request, definitionId, state, save}) {
  const base = '/workflow/definitions/' + definitionId;
  const original = await call('admin', base);
  const revision = original.definition.draftRevision;
  const version = original.definition.latestVersion;
  const published = await call('admin', base + '/versions/' + version);
  const invalid = [];
  for (const type of ['scriptTask', 'serviceTask', 'businessRuleTask', 'sendTask', 'receiveTask', 'callActivity', 'manualTask']) {
    invalid.push([type, original.bpmnXml.replace(/<bpmn:userTask\b[^>]*>[\s\S]*?<\/bpmn:userTask>/,
      `<bpmn:${type} id="review" name="Forbidden execution"/>`)]);
  }
  invalid.push(['participantMultiplicity', original.bpmnXml.replace(/(<bpmn:participant\b[^>]*)(\/>)/,
    '$1><bpmn:participantMultiplicity minimum="1" maximum="2"/></bpmn:participant>')]);
  invalid.push(['messageFlow', original.bpmnXml.replace('</bpmn:collaboration>',
    '<bpmn:messageFlow id="unsupported_message" sourceRef="pool" targetRef="review"/></bpmn:collaboration>')]);
  state.checks ??= {};
  for (const [name, bpmnXml] of invalid) {
    if (state.checks[name]?.passed) continue;
    assert.notEqual(bpmnXml, original.bpmnXml, 'Mutation must introduce ' + name);
    const response = await request('admin', base + '/draft', 'PUT', {expectedRevision: revision,
      name: original.definition.name, description: original.definition.description, category: original.definition.category,
      bpmnXml, formContract: original.formContract});
    assert.equal(response.status, 400, 'Unsupported ' + name + ': ' + response.body);
    const error = JSON.parse(response.body); assert(error.messageKey?.startsWith('workflow.'));
    const after = await call('admin', base);
    assert.equal(after.definition.draftRevision, revision); assert.equal(after.definition.latestVersion, version);
    assert.equal(after.bpmnXml, original.bpmnXml);
    // The rejected input never becomes a publishable draft. Replaying publish retains the old version.
    const replay = await call('admin', base + '/publish', 'POST', {expectedRevision: revision, comment: '拒绝后发布重放核对'});
    assert.equal(replay.version, version);
    assert.deepEqual(await call('admin', base + '/versions/' + version), published);
    state.checks[name] = {passed: true, status: response.status, code: error.code, messageKey: error.messageKey,
      definitionId, preservedVersion: version}; await save(); console.log('PASS workflow rejected ' + name);
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
}

/** A well-formed XML document can still violate BPMN's element ordering at deployment. */
export async function verifyWorkflowSchemaError({call, request, definitionId, state, save}) {
  if (state.passed) return;
  const base = '/workflow/definitions/' + definitionId;
  state.original ??= await call('admin', base); await save();
  const original = state.original, version = original.definition.latestVersion;
  const published = await call('admin', base + '/versions/' + version);
  const lane = original.bpmnXml.match(/<bpmn:laneSet\b[\s\S]*?<\/bpmn:laneSet>/)?.[0]; assert(lane);
  const invalid = original.bpmnXml.replace(lane, '').replace('<bpmn:startEvent id="start"/>', '<bpmn:startEvent id="start"/>' + lane);
  assert.notEqual(invalid, original.bpmnXml);
  const command = (current, xml) => ({expectedRevision: current.definition.draftRevision, name: original.definition.name,
    description: original.definition.description, category: original.definition.category, bpmnXml: xml, formContract: original.formContract});
  try {
    const current = await call('admin', base);
    const bad = await call('admin', base + '/draft', 'PUT', command(current, invalid));
    const result = await request('admin', base + '/publish', 'POST', {expectedRevision: bad.definition.draftRevision});
    assert.equal(result.status, 400, result.body); const error = JSON.parse(result.body);
    assert.equal(error.code, 'WORKFLOW_VALIDATION'); assert.equal(error.messageKey, 'workflow.error.VALIDATION');
    assert(!error.message.includes('<bpmn:'), 'Diagnostic XML must not enter the public error');
    assert.deepEqual(await call('admin', base + '/versions/' + version), published);
    assert.equal((await call('admin', base)).definition.latestVersion, version);
    state.error = {status: result.status, code: error.code, messageKey: error.messageKey}; await save();
  } finally {
    const current = await call('admin', base);
    assert([invalid, original.bpmnXml].includes(current.bpmnXml), 'Do not overwrite another editor');
    if (current.bpmnXml === invalid) await call('admin', base + '/draft', 'PUT', command(current, original.bpmnXml));
    assert.equal((await call('admin', base)).bpmnXml, original.bpmnXml); state.restored = true; await save();
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS malformed BPMN schema returns stable 400, preserves published version and restores the draft');
}
