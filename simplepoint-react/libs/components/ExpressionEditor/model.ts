import type {QueryNode, Scalar} from '../Table/query';
import {quoteExpressionString, parseExpressionString, maskExpressionStrings} from './literals.ts';

export type ExpressionVariable = {name: string; label: string; type: 'string' | 'number' | 'boolean' | 'array';
  kind?: string; readOnly?: boolean};
/** Numeric text is a literal, not a quoted string. The shared query inputs already keep it exact. */
export type ConditionNode = QueryNode & {valueType?: 'number' | 'string'};
export const conditionLimits = {maxDepth: 3, maxNodes: 32} as const;
export const comparisonOperators = ['equals', 'not:equals', 'than:greater', 'than:equal:greater',
  'than:less', 'than:equal:less', 'is:null', 'is:not:null'];
const symbols: Record<string, string> = {equals: '==', 'not:equals': '!=', 'than:greater': '>',
  'than:equal:greater': '>=', 'than:less': '<', 'than:equal:less': '<='};
const operatorFor = Object.fromEntries(Object.entries(symbols).map(([key, value]) => [value, key]));

/** Serializes only the visual editor's finite AST, never executable JavaScript or Java. */
export function conditionExpression(node: QueryNode, variables?: ReadonlyMap<string, ExpressionVariable>): string {
  let count = 0;
  const render = (value: ConditionNode, depth = 0): string => {
    if (depth > conditionLimits.maxDepth || ++count > conditionLimits.maxNodes) throw Error('表达式过于复杂');
    if (value.children) {
      if (!value.children.length) throw Error('条件组不能为空');
      if (value.op === 'not') {
        if (value.children.length !== 1) throw Error('否定条件必须包含一个条件');
        return `!(${render(value.children[0], depth + 1)})`;
      }
      if (!['and', 'or'].includes(value.op)) throw Error('条件组类型不正确');
      return `(${value.children.map(child => render(child, depth + 1)).join(value.op === 'and' ? ' && ' : ' || ')})`;
    }
    if (!value.field || !/^[A-Za-z_][A-Za-z0-9_.]*$/.test(value.field)) throw Error('请选择流程变量');
    const variable = variables?.get(value.field);
    if (variables && (!variable || variable.type === 'array')) throw Error('请选择流程变量');
    if (value.op === 'is:null' || value.op === 'is:not:null') return `${value.field} ${value.op === 'is:null' ? '==' : '!='} null`;
    if (!symbols[value.op] || value.value === undefined || !['string', 'number', 'boolean'].includes(typeof value.value)) throw Error('请填写有效的条件值');
    if (variable?.type === 'number' && value.valueType === 'string') throw Error('条件值类型不正确');
    if (variable && variable.type !== 'number' && (!['equals', 'not:equals'].includes(value.op)
      || value.valueType === 'number' || typeof value.value !== variable.type)) throw Error('条件值类型不正确');
    const literal = variable?.type === 'number' || value.valueType === 'number' || typeof value.value === 'number'
      ? numericConditionLiteral(value.value) : typeof value.value === 'string' ? quoteExpressionString(value.value) : JSON.stringify(value.value);
    return `${value.field} ${symbols[value.op]} ${literal}`;
  };
  const result = '${' + render(node) + '}';
  if (result.length > 500) throw Error('表达式不能超过 500 个字符');
  return result;
}

function numericConditionLiteral(value: unknown): string {
  const text = String(value);
  if (!['string', 'number'].includes(typeof value) || !/^-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(text)
    || !Number.isFinite(Number(text))) throw Error('数值不正确');
  return text;
}

/** A bounded index lookup, also accepting incomplete drafts so their controls remain editable. */
export function conditionAvailable(node: ConditionNode, variables: ReadonlyMap<string, ExpressionVariable>): boolean {
  let count = 0;
  const visit = (value: ConditionNode, depth: number): boolean => {
    if (depth > conditionLimits.maxDepth || ++count > conditionLimits.maxNodes) return false;
    if (value.children) return ['and', 'or', 'not'].includes(value.op)
      && (value.op !== 'not' || value.children.length === 1) && value.children.every(child => visit(child, depth + 1));
    const variable = variables.get(value.field ?? '');
    if (!variable || variable.type === 'array' || !comparisonOperators.includes(value.op)
      || (variable.type !== 'number' && !['equals', 'not:equals', 'is:null', 'is:not:null'].includes(value.op))) return false;
    if (value.op.startsWith('is:') || value.value === undefined) return true;
    if (value.valueType === 'number' && variable.type !== 'number') return false;
    if (value.valueType === 'string' && variable.type !== 'string') return false;
    return variable.type === 'number' ? typeof value.value === 'number' || typeof value.value === 'string'
      : typeof value.value === variable.type;
  };
  return visit(node, 0);
}

type Token = {text: string; value?: Scalar | null; valueType?: 'number' | 'string'; kind: 'field' | 'literal' | 'symbol'};

/** Reads the supported visual subset. Unsupported advanced syntax is preserved instead of guessed. */
export function parseConditionExpression(expression: string): ConditionNode {
  const match = /^\$\{([\s\S]*)\}$/.exec(expression.trim());
  if (!match || expression.length > 500) throw Error('条件表达式格式不正确');
  const source = match[1];
  const tokens: Token[] = [];
  let offset = 0;
  while (offset < source.length) {
    if (/\s/.test(source[offset])) {offset++; continue;}
    const rest = source.slice(offset);
    const symbol = /^(?:&&|\|\||==|!=|>=|<=|[()!><])/.exec(rest);
    if (symbol) {tokens.push({kind: 'symbol', text: symbol[0]}); offset += symbol[0].length; continue;}
    const string = /^(?:"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')/.exec(rest);
    if (string) {
      const quoted = string[0];
      const value = parseExpressionString(quoted);
      tokens.push({kind: 'literal', text: quoted, value, valueType: 'string'}); offset += quoted.length; continue;
    }
    const number = /^-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?/.exec(rest);
    if (number) {
      const value = numericConditionLiteral(number[0]);
      tokens.push({kind: 'literal', text: value, value, valueType: 'number'}); offset += number[0].length; continue;
    }
    const field = /^[A-Za-z_][A-Za-z0-9_.]*/.exec(rest);
    if (field) {
      const text = field[0];
      tokens.push(['true', 'false', 'null'].includes(text)
        ? {kind: 'literal', text, value: text === 'null' ? null : text === 'true'} : {kind: 'field', text});
      offset += text.length; continue;
    }
    throw Error('该表达式包含可视化未支持的语法，请保留高级模式');
  }
  let cursor = 0;
  let count = 0;
  const consume = (text: string) => tokens[cursor]?.text === text ? (cursor++, true) : false;
  const atom = (depth: number): ConditionNode => {
    if (depth > 8 || ++count > 64) throw Error('表达式过于复杂');
    if (consume('!')) return {op: 'not', children: [atom(depth + 1)]};
    if (consume('(')) {
      const result = or(depth + 1);
      if (!consume(')')) throw Error('表达式缺少右括号');
      return result;
    }
    const field = tokens[cursor++];
    if (field?.kind !== 'field') throw Error('条件左侧必须是流程变量');
    const operator = tokens[cursor];
    if (!operatorFor[operator?.text]) return {field: field.text, op: 'equals', value: true};
    cursor++;
    const literal = tokens[cursor++];
    if (literal?.kind !== 'literal') throw Error('此条件需要在高级模式编辑');
    if (literal.value === null) {
      if (!['==', '!='].includes(operator.text)) throw Error('空值只支持等于或不等于');
      return {field: field.text, op: operator.text === '==' ? 'is:null' : 'is:not:null'};
    }
    return {field: field.text, op: operatorFor[operator.text], value: literal.value,
      ...(literal.valueType ? {valueType: literal.valueType} : {})};
  };
  const and = (depth: number): QueryNode => {
    const children = [atom(depth)];
    while (consume('&&')) children.push(atom(depth));
    return children.length === 1 ? children[0] : {op: 'and', children};
  };
  const or = (depth: number): QueryNode => {
    const children = [and(depth)];
    while (consume('||')) children.push(and(depth));
    return children.length === 1 ? children[0] : {op: 'or', children};
  };
  const result = or(0);
  if (cursor !== tokens.length) throw Error('此表达式需要在高级模式编辑');
  return result;
}

export function expressionVariable(value?: string): string | undefined {
  const name = /^\$\{([A-Za-z_][A-Za-z0-9_.]*)\}$/.exec(value?.trim() ?? '')?.[1];
  return name && !['true', 'false', 'null'].includes(name) ? name : undefined;
}

/** Inverts only Flowable string and JSON numeric/boolean literals; never evaluates an expression. */
export function expressionConstant(value: string): string | undefined {
  const body = /^\$\{([\s\S]*)\}$/.exec(value.trim())?.[1];
  if (body == null) return undefined;
  try {
    const text = body.trim();
    if (text.startsWith('"') || text.startsWith("'")) return parseExpressionString(text);
    const scalar: unknown = JSON.parse(body);
    // Numeric text must not round through JS Number (large integers, precise
    // decimals and -0). It is only a preview until the user edits a fixed value.
    return typeof scalar === 'number' ? body.trim()
      : typeof scalar === 'boolean' ? String(scalar) : undefined;
  } catch {return undefined;}
}

export function expressionIssue(value: string): string | undefined {
  if (value.length > 500) return '表达式不能超过 500 个字符';
  if (value && !/^\$\{[\s\S]+\}$/.test(value.trim())) return '表达式必须使用 ${…} 格式';
  let code: string;
  try {code = maskExpressionStrings(value);} catch (reason) {return (reason as Error).message;}
  if (/(?:@|\bnew\s|\bclass\b|\bT\s*\(|\bRuntime\b|\bProcessBuilder\b|\bgetClass\b|\bforName\b|[A-Za-z_$][A-Za-z0-9_$]*\s*\(|[\]\)]\s*\(|\b(?:execution|task|processEngine|processEngineConfiguration|delegateTask)\b)/i.test(code)
    || /\[\s*['"](?:class|getClass|forName)['"]\s*\]/i.test(code)) {
    return '表达式不允许函数、方法或对象调用';
  }
  return undefined;
}
