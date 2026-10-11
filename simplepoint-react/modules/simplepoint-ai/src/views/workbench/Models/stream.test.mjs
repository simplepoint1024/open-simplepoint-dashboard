import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readSse, finalText, ModelDebugOperationError} from './stream.ts';

const responseFor = text => {
  const bytes = new TextEncoder().encode(text); let index = 0;
  return new Response(new ReadableStream({pull(controller) {if (index === bytes.length) controller.close(); else controller.enqueue(bytes.slice(index, ++index));}}));
};
test('real SSE decoding retains split UTF-8, CRLF boundaries, comments and multiline data', async () => {
  const response = responseFor(': comment\r\ndata: {"type":"TEXT_DELTA",\r\ndata: "textDelta":"你好"}\r\n\r\ndata: {"type":"COMPLETED"}\n\ndata: [DONE]\n\n');
  const events = []; await readSse(response, event => events.push(event), new AbortController().signal);
  assert.deepEqual(events, [{type: 'TEXT_DELTA', textDelta: '你好'}, {type: 'COMPLETED'}]); assert.equal(response.body.locked, false);
});
test('unterminated frames do not fabricate successful stream completion', async () => {
  const events = [], response = responseFor('data: {"type":"COMPLETED"}\r\n');
  await readSse(response, event => events.push(event), new AbortController().signal); assert.deepEqual(events, []);
});
test('consumer errors cancel and release the owned reader', async () => {
  let cancelled = false;
  const response = new Response(new ReadableStream({start(controller) {controller.enqueue(new TextEncoder().encode('data: {"type":"ERROR"}\n\n'));}, cancel() {cancelled = true;}}));
  await assert.rejects(readSse(response, () => {throw new ModelDebugOperationError('AI_MODEL_DEBUG_GENERATION_FAILED');}, new AbortController().signal),
    error => error.code === 'AI_MODEL_DEBUG_GENERATION_FAILED');
  assert.equal(cancelled, true); assert.equal(response.body.locked, false);
});
test('explicit cancellation unblocks a pending read without committing an event', async () => {
  const request = new AbortController(), response = new Response(new ReadableStream());
  const reading = readSse(response, () => assert.fail('Cancelled stream committed'), request.signal); request.abort();
  await assert.rejects(reading, {name: 'AbortError'}); assert.equal(response.body.locked, false);
});
test('final outputs include only actual text/refusal blocks', () => {
  assert.equal(finalText({output: [{type: 'TEXT', text: 'actual'}, {type: 'IMAGE', text: 'not prose'}, {type: 'REFUSAL', text: 'refusal'}]}), 'actual\n\nrefusal');
});
