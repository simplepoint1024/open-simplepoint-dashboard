import assert from 'node:assert/strict';

/** Transport fixture only: tagged outputs deliberately distinguish preview from committed values. */
export function createCalculationRuntimeFixture() {
  let source, detailId, detailSource, held, rowSequence = 0;
  const reads = [], target = 'runtime-calculated-value', detailTarget = 'runtime-calculated-detail';
  const output = (values, prefix) => `${prefix}:${values[source] ?? ''}`;
  return {reads, target, detailTarget, enable(fieldId) {source = fieldId;},
    holdNext() {let release; const promise = new Promise(resolve => {release = resolve;}); held = promise; return release;},
    document(document) {
      if (!source) return document;
      detailId = document.details?.[0]?.detailId; detailSource = document.details?.[0]?.fields[0]?.fieldId;
      return {...document, fields: [...document.fields, {fieldId: target, label: '计算摘要', type: 'STRING', calculated: true, choices: []}],
        details: document.details?.map(detail => detail.detailId !== detailId ? detail : {...detail,
          fields: [...detail.fields, {fieldId: detailTarget, label: '明细计算', type: 'STRING', calculated: true, choices: []}]}),
        scenes: document.scenes.map(scene => scene.type !== 'MAIN' ? scene : {...scene, root: {...scene.root, children: [...scene.root.children,
          {componentId: target, componentType: 'field.text', componentVersion: '1', fieldId: target, columnSpan: 24, readOnly: true, properties: {}, children: []},
        ]}})};
    },
    async preview(data) {
      assert.ok(source); assert.equal(Object.hasOwn(data.values, target), false, 'computed output is never a preview input');
      assert.equal(data.idempotencyKey, undefined, 'preview creates no write operation');
      for (const row of data.values[detailId] ?? []) assert.equal(Object.hasOwn(row.values, detailTarget), false);
      reads.push(structuredClone(data));
      const wait = held; held = undefined; if (wait) await wait;
      return {runtime: data.runtime, values: {[target]: output(data.values, 'preview')}, details: !detailId ? {} : {
        [detailId]: (data.values[detailId] ?? []).map(row => ({[detailTarget]: `preview-row:${row.values[detailSource] ?? ''}`})),
      }};
    },
    committed(record, data) {
      if (!source) return;
      assert.equal(Object.hasOwn(data.values, target), false, 'computed output is never user-written');
      record.values[target] = output(record.values, 'committed');
      for (const row of data.values[detailId] ?? []) assert.equal(Object.hasOwn(row.values, detailTarget), false);
      for (const row of record.values[detailId] ?? []) {
        row.id ??= `calculated-row-${++rowSequence}`;
        row.version = (row.version ?? -1) + 1;
        row.values[detailTarget] = `committed-row:${row.values[detailSource] ?? ''}`;
      }
    },
  };
}
