import {test} from 'node:test';
import assert from 'node:assert/strict';
import {parseFormula, printFormula, formulaReferences, node, replaceFormulaNode, validateFormula} from '../.test-dist/index.js';
const error = code => failure => failure.code === code;
test('ANTLR parses Unicode references, escaped strings and fields with lossless decimal literals', () => {
  const ast = parseFormula('=IF([日本語]>0.123456789012345678901234567890,"a""b",[费用]]项目])');
  assert.deepEqual(formulaReferences(ast), new Set(['费用]项目', '日本語']));
  assert.deepEqual(parseFormula(printFormula(ast)), ast);
  assert.equal(ast.children[0].children[1].text, '0.123456789012345678901234567890');
  assert.equal(ast.children[1].text, 'a"b');
});
test('precedence and associativity match the Java grammar', () => {
  assert.equal(printFormula(parseFormula('=-3^2')), '=(-(3)^2)');
  assert.equal(printFormula(parseFormula('=2^3^2')), '=((2^3)^2)');
  assert.equal(printFormula(parseFormula('=1+2*3&"x"')), '=((1+(2*3))&"x")');
});
test('visual replacement remains immutable and invalid AST cannot inject operators', () => {
  const before = parseFormula('=SUM([a],1)');
  const after = replaceFormulaNode(before, [1], node('STRING', '汉😀'));
  assert.equal(printFormula(before), '=SUM([a],1)');
  assert.equal(printFormula(after), '=SUM([a],"汉😀")');
  assert.deepEqual(parseFormula(printFormula(after)), after);
  assert.throws(() => printFormula(node('NUMBER', '1+2')), error('INVALID_AST'));
  assert.throws(() => replaceFormulaNode(before, [99], node('NULL', 'NULL')), error('INVALID_PATH'));
});
test('syntax errors, excessive depth, unknown references and arity fail explicitly', () => {
  assert.throws(() => parseFormula('SUM(1,'), error('SYNTAX'));
  assert.throws(() => parseFormula('('.repeat(65) + '1' + ')'.repeat(65)), error('EXPRESSION_DEPTH'));
  const fn = [{name:'SUM',category:'AGGREGATE',minArguments:1,maxArguments:256,resultType:'NUMBER',parameters:['value']}];
  assert.throws(() => validateFormula(parseFormula('[hidden]'), [], fn), error('UNKNOWN_FIELD'));
  assert.throws(() => validateFormula(parseFormula('SUM()'), [], fn), error('ARGUMENT_COUNT'));
  assert.throws(() => validateFormula(parseFormula('EXEC(1)'), [], fn), error('UNKNOWN_FUNCTION'));
});

test('Java and browser share canonical expression fixtures', async () => {
  const {readFile} = await import('node:fs/promises');
  const fixtures = await readFile(new URL('../../../../simplepoint-calculation/src/test/resources/formula-syntax.tsv', import.meta.url), 'utf8');
  for (const line of fixtures.trimEnd().split('\n')) {
    const [expression, expected] = line.split('\t');
    const tree = parseFormula(expression);
    assert.equal(printFormula(tree), expected, expression);
    assert.deepEqual(parseFormula(expected), tree, expression);
  }
});
