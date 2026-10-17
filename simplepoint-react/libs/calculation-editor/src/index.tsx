import {useId, useMemo, useState, type DragEvent, type ReactNode} from 'react';
import {FormulaError, node, parseFormula, printFormula, replaceFormulaNode, validateFormula,
  type FormulaField, type FormulaFunction, type FormulaNode} from '@simplepoint/calculation';
import './style.css';

export interface CalculatorLabels {
  visual: string; expression: string; fields: string; functions: string; operators: string; literals: string;
  search: string; empty: string; insert: string; selected: string; remove: string; addArgument: string;
  text: string; number: string; boolean: string; null: string; value: string;
  error: (code: string, line: number, column: number) => string;
}
export const englishCalculatorLabels: CalculatorLabels = {
  visual: 'Visual', expression: 'Expression', fields: 'Fields', functions: 'Functions', operators: 'Operators', literals: 'Values',
  search: 'Search fields or functions', empty: 'Drop here or select a block from the left', insert: 'Insert', selected: 'Selected block',
  remove: 'Clear block', addArgument: 'Add argument', text: 'Text', number: 'Number', boolean: 'Boolean', null: 'Empty', value: 'Value',
  error: (code, line, column) => `${code} (${line}:${column + 1})`,
};
export interface CalculationEditorProps {
  value: string;
  onChange: (expression: string) => void;
  fields: readonly FormulaField[];
  functions: readonly FormulaFunction[];
  labels?: CalculatorLabels;
  readOnly?: boolean;
  /** Optional host-provided evaluation or help; the editor performs no network calls. */
  footer?: ReactNode;
}
const operators = ['+', '-', '*', '/', '^', '&', '=', '<>', '<', '<=', '>', '>='];
const empty = () => node('NULL', 'NULL');
const pathKey = (path: readonly number[]) => path.join('.');
const getNode = (root: FormulaNode, path: readonly number[]): FormulaNode | undefined => {
  let current: FormulaNode | undefined = root;
  for (const index of path) current = current?.children[index];
  return current;
};

/** Standalone specialized expression editor. Native controls deliberately avoid any platform UI dependency. */
export function CalculationEditor({value, onChange, fields, functions, labels = englishCalculatorLabels,
  readOnly = false, footer}: CalculationEditorProps) {
  const id = useId();
  const [mode, setMode] = useState<'visual' | 'expression'>('visual');
  const [selection, setSelection] = useState<readonly number[]>([]);
  const [search, setSearch] = useState('');
  const [editError, setEditError] = useState<FormulaError>();
  const parsed = useMemo(() => {
    try {
      const tree = value.trim() ? parseFormula(value) : empty();
      validateFormula(tree, fields, functions);
      return {tree, error: undefined};
    } catch (failure) {
      return {tree: undefined, error: failure instanceof FormulaError ? failure : new FormulaError('INVALID_AST')};
    }
  }, [value, fields, functions]);
  const tree = parsed.tree;
  const path = tree && getNode(tree, selection) ? selection : [];
  const selected = tree && getNode(tree, path);
  const fieldNames = new Map(fields.map(field => [field.id, field.label]));
  const palette = useMemo(() => [
    ...fields.map(field => ({key: 'field:' + field.id, group: labels.fields + (field.group ? ' · ' + field.group : ''),
      label: field.label, search: field.id, tree: node('FIELD', field.id)})),
    ...functions.map(fn => ({key: 'function:' + fn.name, group: labels.functions + ' · ' + fn.category,
      label: fn.name, search: fn.parameters.join(' '), tree: node('CALL', fn.name, Array.from({length: fn.minArguments}, empty))})),
    ...operators.map(operator => ({key: 'operator:' + operator, group: labels.operators, label: operator, search: '', tree: node('BINARY', operator, [empty(), empty()])})),
    ...[{kind: 'NUMBER' as const, text: '0', label: labels.number}, {kind: 'STRING' as const, text: '', label: labels.text},
      {kind: 'BOOLEAN' as const, text: 'TRUE', label: labels.boolean}, {kind: 'NULL' as const, text: 'NULL', label: labels.null}]
      .map(literal => ({key: 'literal:' + literal.kind, group: labels.literals, label: literal.label, search: '', tree: node(literal.kind, literal.text)})),
  ], [fields, functions, labels]);
  const groups = new Map<string, typeof palette>();
  for (const item of palette) {
    if (!(item.label + ' ' + item.search).toLocaleLowerCase().includes(search.toLocaleLowerCase())) continue;
    groups.set(item.group, [...(groups.get(item.group) ?? []), item]);
  }
  const commit = (next: FormulaNode) => {
    if (readOnly) return;
    try {
      validateFormula(next, fields, functions);
      const expression = printFormula(next);
      // Check generated text against the shared grammar before changing the controlled value.
      parseFormula(expression);
      onChange(expression); setEditError(undefined);
    } catch (failure) {setEditError(failure instanceof FormulaError ? failure : new FormulaError('INVALID_AST'));}
  };
  const insert = (replacement: FormulaNode, target: readonly number[] = path) => {
    try {
      if (tree) {commit(replaceFormulaNode(tree, target, replacement)); setSelection(target);}
    } catch (failure) {setEditError(failure instanceof FormulaError ? failure : new FormulaError('INVALID_AST'));}
  };
  const drag = (event: DragEvent, key: string) => {
    event.stopPropagation(); event.dataTransfer.setData('application/x-calculation-' + id.replaceAll(':', ''), key);
    event.dataTransfer.effectAllowed = 'copyMove';
  };
  const drop = (event: DragEvent, target: readonly number[]) => {
    event.preventDefault(); event.stopPropagation();
    if (readOnly || !tree) return;
    const key = event.dataTransfer.getData('application/x-calculation-' + id.replaceAll(':', ''));
    if (key.startsWith('node:')) {
      const source = key.slice(5) ? key.slice(5).split('.').map(Number) : [];
      // Moving into your own descendant would discard or recursively duplicate the subtree.
      if (source.every((index, i) => target[i] === index) && source.length <= target.length) return;
      const moving = getNode(tree, source);
      if (moving) {
        try {
          commit(replaceFormulaNode(replaceFormulaNode(tree, source, empty()), target, moving)); setSelection(target);
        } catch (failure) {setEditError(failure instanceof FormulaError ? failure : new FormulaError('INVALID_AST'));}
      }
    } else {
      const item = palette.find(item => item.key === key);
      if (item) insert(item.tree, target);
    }
  };
  const block = (current: FormulaNode, currentPath: readonly number[]): ReactNode => {
    const active = pathKey(path) === pathKey(currentPath);
    const label = current.kind === 'FIELD' ? fieldNames.get(current.text) ?? current.text
      : current.kind === 'NULL' ? labels.empty : current.kind === 'STRING' ? '“' + current.text + '”' : current.text;
    const fn = current.kind === 'CALL' ? functions.find(item => item.name === current.text) : undefined;
    return <div key={pathKey(currentPath)} className={'sp-calculator-block' + (active ? ' is-selected' : '')}
      data-kind={current.kind} onDragOver={event => {if (!readOnly) {event.preventDefault(); event.stopPropagation();}}}
      onDrop={event => drop(event, currentPath)}>
      <button type="button" className="sp-calculator-block-label" draggable={!readOnly}
        onDragStart={event => drag(event, 'node:' + pathKey(currentPath))}
        aria-pressed={active} onClick={() => setSelection(currentPath)}>{label}</button>
      {current.children.length > 0 && <div className="sp-calculator-arguments">
        {current.children.map((child, index) => <div className="sp-calculator-argument" key={index}>
          <span className="sp-calculator-argument-label">{fn?.parameters[index] ?? index + 1}</span>
          {!readOnly && fn && current.children.length > fn.minArguments && <button type="button" className="sp-calculator-inline"
            aria-label={`${labels.remove} ${index + 1}`} onClick={() => {
              insert(node(current.kind, current.text, current.children.filter((_, i) => i !== index)), currentPath);
              setSelection(currentPath);
            }}>×</button>}
          {block(child, [...currentPath, index])}
        </div>)}
      </div>}
      {!readOnly && fn && current.children.length < fn.maxArguments && <button type="button" className="sp-calculator-inline"
        onClick={() => insert(node(current.kind, current.text, [...current.children, empty()]), currentPath)}
        aria-label={labels.addArgument}>＋</button>}
    </div>;
  };
  const error = parsed.error ?? editError;
  return <div className="sp-calculator" data-mode={mode}>
    <div className="sp-calculator-tabs" role="tablist">
      {(['visual', 'expression'] as const).map(tab => <button key={tab} type="button" role="tab" id={`${id}-${tab}`}
        aria-controls={`${id}-panel-${tab}`} aria-selected={mode === tab} tabIndex={mode === tab ? 0 : -1}
        onKeyDown={event => {
          if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
          event.preventDefault();
          const next = event.key === 'Home' ? 'visual' : event.key === 'End' ? 'expression' : mode === 'visual' ? 'expression' : 'visual';
          setMode(next); setEditError(undefined); document.getElementById(`${id}-${next}`)?.focus();
        }} onClick={() => {setMode(tab); setEditError(undefined);}}>
        <span aria-hidden="true">{tab === 'visual' ? '▦' : 'ƒ'}</span> {labels[tab]}</button>)}
    </div>
    {error && <div className="sp-calculator-error" role="alert">{labels.error(error.code, error.line, error.column)}</div>}
    {mode === 'expression' ? <div role="tabpanel" id={`${id}-panel-expression`} aria-labelledby={`${id}-expression`}>
      <textarea aria-label={labels.expression} className="sp-calculator-expression" value={value} readOnly={readOnly}
        spellCheck={false} maxLength={8192} onChange={event => {onChange(event.target.value); setEditError(undefined);}}/>
    </div> : <div role="tabpanel" id={`${id}-panel-visual`} aria-labelledby={`${id}-visual`} className="sp-calculator-visual">
      <aside className="sp-calculator-palette">
        <input type="search" aria-label={labels.search} placeholder={labels.search} value={search} onChange={event => setSearch(event.target.value)}/>
        {[...groups].map(([group, items]) => <section key={group}>
          <h4>{group}</h4><div className="sp-calculator-palette-items">{items.map(item => <button type="button" key={item.key}
            disabled={readOnly || !tree} draggable={!readOnly} title={labels.insert + ': ' + item.label}
            onDragStart={event => drag(event, item.key)} onClick={() => insert(item.tree)}>{item.label}</button>)}</div>
        </section>)}
      </aside>
      <div className="sp-calculator-workarea">
        <div className="sp-calculator-canvas">{tree && block(tree, [])}</div>
        {selected && <div className="sp-calculator-properties">
          <span>{labels.selected}</span>
          {(['STRING', 'NUMBER'] as const).some(kind => selected.kind === kind) && <input
            aria-label={labels.value} readOnly={readOnly} value={selected.text} inputMode={selected.kind === 'NUMBER' ? 'decimal' : 'text'}
            onChange={event => insert(node(selected.kind, event.target.value))}/>}
          {selected.kind === 'BOOLEAN' && <select aria-label={labels.value} disabled={readOnly} value={selected.text}
            onChange={event => insert(node('BOOLEAN', event.target.value))}><option>TRUE</option><option>FALSE</option></select>}
          {!readOnly && <button type="button" className="sp-calculator-inline" onClick={() => insert(empty())}>× {labels.remove}</button>}
        </div>}
      </div>
    </div>}
    {footer && <div className="sp-calculator-footer">{footer}</div>}
  </div>;
}
export default CalculationEditor;
