export type GenerationResult = {output?: Array<{type: string; text?: string}>};
export type GenerationEvent = {type: string; textDelta?: string; result?: GenerationResult; errorCode?: string};
export class ModelDebugOperationError extends Error {
  readonly code: string;
  constructor(code: string) {super('Model debug operation failed'); this.name = 'ModelDebugOperationError'; this.code = code;}
}
export const finalText = (result?: GenerationResult) => result?.output
  ?.filter(block => block.type === 'TEXT' || block.type === 'REFUSAL').map(block => block.text).filter(Boolean).join('\n\n') ?? '';

/** SSE line endings and UTF-8 can span chunks. Always release the actual response reader. */
export async function readSse(response: Response, onEvent: (event: GenerationEvent) => void, signal: AbortSignal) {
  if (!response.body) throw new ModelDebugOperationError('AI_MODEL_DEBUG_STREAM_UNAVAILABLE');
  const reader = response.body.getReader(), decoder = new TextDecoder();
  let buffer = '', data: string[] = [], done = false;
  const consume = (line: string) => {
    if (!line) {
      const payload = data.join('\n').trim(); data = [];
      if (!payload || payload === '[DONE]') return;
      const event = JSON.parse(payload) as GenerationEvent;
      if (!event || typeof event !== 'object' || Array.isArray(event) || typeof event.type !== 'string')
        throw new ModelDebugOperationError('AI_MODEL_DEBUG_STREAM_UNAVAILABLE');
      onEvent(event); return;
    }
    if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
  };
  const cancel = () => {void reader.cancel().catch(() => undefined);};
  signal.addEventListener('abort', cancel, {once: true});
  try {
    signal.throwIfAborted();
    while (!done) {
      const chunk = await reader.read(); done = chunk.done;
      signal.throwIfAborted();
      buffer += decoder.decode(chunk.value, {stream: !done});
      let start = 0;
      for (let index = 0; index < buffer.length; index++) {
        const char = buffer[index]; if (char !== '\r' && char !== '\n') continue;
        if (char === '\r' && index === buffer.length - 1 && !done) break;
        consume(buffer.slice(start, index));
        if (char === '\r' && buffer[index + 1] === '\n') index++;
        start = index + 1;
      }
      buffer = buffer.slice(start);
    }
    // The SSE contract dispatches only events terminated by a blank line; a truncated last frame is not success.
  } finally {
    signal.removeEventListener('abort', cancel);
    if (!done) await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}
