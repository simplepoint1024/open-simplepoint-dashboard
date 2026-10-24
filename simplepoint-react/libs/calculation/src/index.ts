import {CharStreams, CommonTokenStream, ErrorListener, ParserRuleContext, Token} from 'antlr4';
import CalculationLexer from './generated/CalculationLexer.ts';
import CalculationParser, {AtomContext, FormulaContext, PercentageContext, UnaryContext} from './generated/CalculationParser.ts';

export type FormulaKind = 'NUMBER' | 'STRING' | 'BOOLEAN' | 'NULL' | 'FIELD' | 'CALL' | 'UNARY' | 'BINARY';
export interface FormulaNode {readonly kind: FormulaKind; readonly text: string; readonly children: readonly FormulaNode[]}
export interface FormulaField {id: string; label: string; type: string; group?: string}
export interface FormulaFunction {name: string; category: string; minArguments: number; maxArguments: number; resultType: string; parameters: readonly string[]}
export class FormulaError extends Error {
  readonly code: string;
  readonly line: number;
  readonly column: number;
  readonly path?: readonly number[];
  constructor(code: string, line = 1, column = 0, path?: readonly number[]) {
    super(code); this.name = 'FormulaError'; this.code = code; this.line = line; this.column = column;
    this.path = path && Object.freeze([...path]);
  }
}
export const node = (kind: FormulaKind, text: string, children: readonly FormulaNode[] = []): FormulaNode =>
  Object.freeze({kind, text, children: Object.freeze([...children])});

const sourceLocations = new WeakMap<FormulaNode, {line: number; column: number}>();
function build(context: ParserRuleContext): FormulaNode {
  const result = buildNode(context);
  if (!sourceLocations.has(result)) sourceLocations.set(result, {line: context.start.line, column: context.start.column});
  return result;
}
function buildNode(context: ParserRuleContext): FormulaNode {
  if (context instanceof FormulaContext) return build(context.comparison());
  if (context instanceof PercentageContext) {
    let value = build(context.unary());
    for (const _token of context.PERCENT_list()) value = node('UNARY', '%', [value]);
    return value;
  }
  if (context instanceof UnaryContext) {
    let value = build(context.atom());
    for (let i = context.getChildCount() - 2; i >= 0; i--) value = node('UNARY', context.getChild(i).getText(), [value]);
    return value;
  }
  if (context instanceof AtomContext) {
    if (context.NUMBER()) return node('NUMBER', context.NUMBER().getText());
    if (context.STRING()) return node('STRING', context.STRING().getText().slice(1, -1).split('""').join('"'));
    if (context.FIELD()) return node('FIELD', context.FIELD().getText().slice(1, -1).split(']]').join(']'));
    if (context.TRUE() || context.FALSE()) return node('BOOLEAN', context.getText().toUpperCase());
    if (context.NULL()) return node('NULL', 'NULL');
    if (context.IDENTIFIER()) return node('CALL', context.IDENTIFIER().getText().toUpperCase(), context.comparison_list().map(build));
    return build(context.comparison(0));
  }
  let value = build(context.getChild(0) as ParserRuleContext);
  for (let i = 1; i < context.getChildCount(); i += 2) {
    value = node('BINARY', context.getChild(i).getText(), [value, build(context.getChild(i + 1) as ParserRuleContext)]);
  }
  return value;
}

export function validateTree(root: FormulaNode): void {
  const queue: {value: FormulaNode; depth: number}[] = [{value: root, depth: 1}];
  let count = 0;
  while (queue.length) {
    const {value, depth} = queue.pop()!;
    if (++count > 1024 || depth > 64) throw new FormulaError('EXPRESSION_DEPTH');
    if (!value || typeof value.text !== 'string' || value.text.length > 8192 || !Array.isArray(value.children)
      || value.children.length > 256) throw new FormulaError('INVALID_AST');
    const size = value.children.length;
    switch (value.kind) {
      case 'UNARY': if (size !== 1 || !['+', '-', '%'].includes(value.text)) throw new FormulaError('INVALID_AST'); break;
      case 'BINARY': if (size !== 2 || !['+', '-', '*', '/', '^', '&', '=', '<>', '<', '<=', '>', '>='].includes(value.text)) throw new FormulaError('INVALID_AST'); break;
      case 'CALL': if (!/^[A-Z_][A-Z_0-9.]*$/.test(value.text)) throw new FormulaError('INVALID_AST'); break;
      case 'BOOLEAN': if (!['TRUE', 'FALSE'].includes(value.text)) throw new FormulaError('INVALID_AST'); break;
      case 'NULL': if (value.text !== 'NULL') throw new FormulaError('INVALID_AST'); break;
      case 'NUMBER': if (!/^(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[Ee][+-]?[0-9]+)?$/.test(value.text)) throw new FormulaError('INVALID_AST'); break;
      case 'FIELD': if (!value.text || /[\r\n]/.test(value.text)) throw new FormulaError('INVALID_AST'); break;
      case 'STRING': if (/[\r\n]/.test(value.text)) throw new FormulaError('INVALID_AST'); break;
      default: throw new FormulaError('INVALID_AST');
    }
    if (!['CALL', 'UNARY', 'BINARY'].includes(value.kind) && size !== 0) throw new FormulaError('INVALID_AST');
    for (const child of value.children) queue.push({value: child, depth: depth + 1});
  }
}

export function parseFormula(expression: string): FormulaNode {
  if (!expression?.trim() || expression.length > 8192) throw new FormulaError('EXPRESSION_SIZE');
  class Errors<T> extends ErrorListener<T> {
    override syntaxError(_recognizer: unknown, _symbol: T, line: number, column: number): void {
      throw new FormulaError('SYNTAX', line, column);
    }
  }
  const lexer = new CalculationLexer(CharStreams.fromString(expression));
  lexer.removeErrorListeners(); lexer.addErrorListener(new Errors<number>());
  const tokens = new CommonTokenStream(lexer); tokens.fill();
  if (tokens.tokens.length > 2048) throw new FormulaError('EXPRESSION_SIZE');
  let depth = 0;
  for (const token of tokens.tokens) {
    if (token.type === CalculationLexer.LP && ++depth > 64) throw new FormulaError('EXPRESSION_DEPTH');
    if (token.type === CalculationLexer.RP) depth--;
  }
  const parser = new CalculationParser(tokens);
  parser.removeErrorListeners(); parser.addErrorListener(new Errors<Token>());
  const result = build(parser.formula()); validateTree(result);
  return result;
}

export function printFormula(root: FormulaNode): string {
  validateTree(root);
  const render = (value: FormulaNode): string => {
    switch (value.kind) {
      case 'STRING': return '"' + value.text.split('"').join('""') + '"';
      case 'FIELD': return '[' + value.text.split(']').join(']]') + ']';
      case 'NUMBER': case 'BOOLEAN': case 'NULL': return value.text;
      case 'CALL': return value.text + '(' + value.children.map(render).join(',') + ')';
      case 'UNARY': return value.text === '%' ? '(' + render(value.children[0]) + ')%' : value.text + '(' + render(value.children[0]) + ')';
      case 'BINARY': return '(' + render(value.children[0]) + value.text + render(value.children[1]) + ')';
    }
  };
  const expression = '=' + render(root);
  if (expression.length > 8192) throw new FormulaError('EXPRESSION_SIZE');
  return expression;
}

export function formulaReferences(root: FormulaNode): ReadonlySet<string> {
  validateTree(root);
  const references = new Set<string>(), pending = [root];
  while (pending.length) {
    const value = pending.pop()!;
    if (value.kind === 'FIELD') references.add(value.text);
    pending.push(...value.children);
  }
  return references;
}

/** Presentation only. Stored formulas always retain stable field identities. */
export function displayFormula(root: FormulaNode, fields: readonly FormulaField[]): string {
  validateTree(root);
  const names = new Map(fields.map(field => [field.id, field.group ? `${field.group} · ${field.label}` : field.label]));
  const counts = new Map<string, number>();
  for (const name of names.values()) counts.set(name, (counts.get(name) ?? 0) + 1);
  const render = (value: FormulaNode): string => {
    if (value.kind === 'FIELD') {
      const label = names.get(value.text);
      return '[' + (label ? label + ((counts.get(label) ?? 0) > 1 ? ` · ${value.text}` : '') : value.text) + ']';
    }
    if (value.kind === 'STRING') return '"' + value.text.split('"').join('""') + '"';
    if (value.kind === 'CALL') return value.text + '(' + value.children.map(render).join(', ') + ')';
    if (value.kind === 'BINARY') return '(' + render(value.children[0]) + ' ' + value.text + ' ' + render(value.children[1]) + ')';
    if (value.kind === 'UNARY') return value.text === '%' ? '(' + render(value.children[0]) + ')%' : value.text + '(' + render(value.children[0]) + ')';
    return value.text;
  };
  return render(root);
}

/** Catalogs are supplied by the host and must match the evaluator's version. */
export function validateFormula(root: FormulaNode, fields: readonly FormulaField[], functions: readonly FormulaFunction[]): void {
  validateTree(root);
  const knownFields = new Set(fields.map(field => field.id));
  const registry = new Map(functions.map(fn => [fn.name, fn]));
  const pending = [{value: root, path: [] as number[]}];
  while (pending.length) {
    const {value, path} = pending.pop()!;
    const fail = (code: string): never => {
      const location = sourceLocations.get(value);
      throw new FormulaError(code, location?.line ?? 1, location?.column ?? 0, path);
    };
    if (value.kind === 'FIELD' && !knownFields.has(value.text)) fail('UNKNOWN_FIELD');
    if (value.kind === 'CALL') {
      const fn = registry.get(value.text);
      if (!fn) fail('UNKNOWN_FUNCTION');
      if (value.children.length < fn!.minArguments || value.children.length > fn!.maxArguments) fail('ARGUMENT_COUNT');
    }
    for (let index = value.children.length - 1; index >= 0; index--) pending.push({value: value.children[index], path: [...path, index]});
  }
}

/** Immutable replacement shared by pointer drop, keyboard insertion and property editing. */
export function replaceFormulaNode(root: FormulaNode, path: readonly number[], replacement: FormulaNode): FormulaNode {
  validateTree(root); validateTree(replacement);
  if (path.length > 64) throw new FormulaError('EXPRESSION_DEPTH');
  const replace = (current: FormulaNode, at: number): FormulaNode => {
    if (at === path.length) return replacement;
    const index = path[at];
    if (!Number.isSafeInteger(index) || index < 0 || index >= current.children.length) throw new FormulaError('INVALID_PATH');
    return node(current.kind, current.text, current.children.map((child, i) => i === index ? replace(child, at + 1) : child));
  };
  const result = replace(root, 0); validateTree(result); return result;
}
