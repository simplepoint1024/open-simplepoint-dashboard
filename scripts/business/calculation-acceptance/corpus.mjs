import assert from 'node:assert/strict';

/** The shared TSV uses Java escapes, including literal tab characters inside formula strings. */
export function parseFunctionCases(text) {
  const escapes = {'b': '\b', 't': '\t', 'n': '\n', 'f': '\f', 'r': '\r', 's': ' ', '"': '"', "'": "'", '\\': '\\'};
  const decode = value => value.replace(/\\(.)/g, (_match, key) => {
    assert(Object.hasOwn(escapes, key), 'Unsupported corpus escape'); return escapes[key];
  });
  const names = new Set();
  return text.split('\n').filter(line => line.trim() && !line.startsWith('#')).map(line => {
    const columns = line.split('\t').map(decode); assert.equal(columns.length, 4);
    const [name, expression, type, expected] = columns;
    assert(!names.has(name) && expression.startsWith(name + '(')); names.add(name);
    return {name, expression, type, expected};
  });
}
