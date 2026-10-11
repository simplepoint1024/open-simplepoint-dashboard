/** BPMN is XML 1.0; JUEL accepts controls that XML cannot carry, so validate both contracts. */
export function assertXmlCharacters(value: string): void {
  for (const character of value) {
    const code = character.codePointAt(0)!;
    if (code === 9 || code === 10 || code === 13 || code >= 32 && code <= 0xD7FF
      || code >= 0xE000 && code <= 0xFFFD || code >= 0x10000 && code <= 0x10FFFF) continue;
    throw Error('字符串包含 BPMN XML 不支持的字符');
  }
}

/** Flowable/JUEL only escapes quotes and backslashes. Whitespace is literal, not JSON escape syntax. */
export function quoteExpressionString(value: string): string {
  assertXmlCharacters(value);
  return '"' + value.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
}

export function readExpressionString(source: string, offset = 0): {value: string; end: number} {
  const quote = source[offset];
  if (quote !== '"' && quote !== "'") throw Error('字符串缺少引号');
  let value = '';
  for (let index = offset + 1; index < source.length; index++) {
    const character = source[index];
    if (character === quote) {assertXmlCharacters(value); return {value, end: index + 1};}
    if (character !== '\\') {value += character; continue;}
    const escaped = source[++index];
    if (!['\\', '"', "'"].includes(escaped)) throw Error('字符串转义必须使用 Flowable 规则');
    value += escaped;
  }
  throw Error('字符串缺少结束引号');
}

export function parseExpressionString(source: string): string {
  const result = readExpressionString(source);
  if (result.end !== source.length) throw Error('字符串后包含其他表达式');
  return result.value;
}

/** Validate and mask literal text before checking the remaining executable expression surface. */
export function maskExpressionStrings(source: string): string {
  assertXmlCharacters(source);
  let result = '';
  for (let index = 0; index < source.length;) {
    if (source[index] !== '"' && source[index] !== "'") {result += source[index++]; continue;}
    const literal = readExpressionString(source, index);
    let before = index - 1, after = literal.end;
    while (before >= 0 && /\s/.test(source[before])) before--;
    while (after < source.length && /\s/.test(source[after])) after++;
    // An indexed reflective key is executable surface; the same text inside a larger literal is not.
    const reflective = ['class', 'getclass', 'forname'].includes(literal.value.toLowerCase())
      && source[before] === '[' && source[after] === ']';
    result += reflective ? source.slice(index, literal.end) : ' '.repeat(literal.end - index);
    index = literal.end;
  }
  return result;
}
