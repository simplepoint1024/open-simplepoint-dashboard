import {autocompletion, snippetCompletion, type Completion, type CompletionSource} from '@codemirror/autocomplete';
import {HighlightStyle, syntaxHighlighting, syntaxTree} from '@codemirror/language';
import {linter, lintGutter, type Diagnostic} from '@codemirror/lint';
import {tags} from '@lezer/highlight';
import {
  MariaSQL,
  MSSQL,
  MySQL,
  PLSQL,
  PostgreSQL,
  SQLite,
  StandardSQL,
  keywordCompletionSource,
  sql as sqlLang,
  type SQLDialect,
} from '@codemirror/lang-sql';
import {EditorView, hoverTooltip, keymap} from '@codemirror/view';
import {executionSql} from './sqlConsoleUtils';
import {
  analyzeSqlCompletion,
  completeSqlMetadata,
  resolveSqlSymbol,
  type SqlCompletionMetadataProvider,
  type SqlCompletionSuggestion,
  type SqlMetadataTarget,
} from './sqlCompletion';
import CodeMirror, {Prec, type ReactCodeMirrorRef} from '@uiw/react-codemirror';
import {forwardRef, useCallback, useImperativeHandle, useMemo, useRef} from 'react';

export type SqlFormatResult = 'document' | 'selection' | 'empty' | 'unchanged';

export type SqlEditorRef = {
  insertText: (text: string) => void;
  appendStatement: (text: string) => void;
  getAllSql: () => string;
  getExecutionSql: () => string;
  formatSql: () => Promise<SqlFormatResult>;
};

type SqlEditorProps = {
  value: string;
  onChange: (value: string) => void;
  readOnly?: boolean;
  onExecute?: () => void;
  dark?: boolean;
  completion?: SqlCompletionMetadataProvider;
  onFormat?: () => void;
  onRevealMetadata?: (target: SqlMetadataTarget) => void;
  revealMetadataLabel?: string;
  syntaxErrorMessage?: string;
};

const completionWord = /^(?:["`\[]?[\p{L}\p{Nl}\p{N}\p{Mn}\p{Mc}_$]*["`\]]?)$/u;

const lightSqlHighlight = HighlightStyle.define([
  {tag: [tags.keyword, tags.modifier], color: '#6d28d9', fontWeight: '600'},
  {tag: [tags.string, tags.special(tags.string)], color: '#047857'},
  {tag: [tags.number, tags.bool, tags.null, tags.atom], color: '#b45309'},
  {tag: tags.function(tags.variableName), color: '#0369a1', fontWeight: '600'},
  {tag: [tags.typeName, tags.className], color: '#0f766e'},
  {tag: tags.propertyName, color: '#0e7490'},
  {tag: [tags.variableName, tags.name], color: '#1d4ed8'},
  {tag: [tags.operator, tags.compareOperator, tags.arithmeticOperator, tags.logicOperator], color: '#c2410c'},
  {tag: tags.comment, color: '#64748b', fontStyle: 'italic'},
  {tag: tags.invalid, color: '#dc2626', textDecoration: 'underline wavy'},
]);

const darkSqlHighlight = HighlightStyle.define([
  {tag: [tags.keyword, tags.modifier], color: '#d8b4fe', fontWeight: '600'},
  {tag: [tags.string, tags.special(tags.string)], color: '#6ee7b7'},
  {tag: [tags.number, tags.bool, tags.null, tags.atom], color: '#fbbf24'},
  {tag: tags.function(tags.variableName), color: '#7dd3fc', fontWeight: '600'},
  {tag: [tags.typeName, tags.className], color: '#5eead4'},
  {tag: tags.propertyName, color: '#67e8f9'},
  {tag: [tags.variableName, tags.name], color: '#93c5fd'},
  {tag: [tags.operator, tags.compareOperator, tags.arithmeticOperator, tags.logicOperator], color: '#fdba74'},
  {tag: tags.comment, color: '#94a3b8', fontStyle: 'italic'},
  {tag: tags.invalid, color: '#f87171', textDecoration: 'underline wavy'},
]);

const dialectFor = (productName?: string): SQLDialect => {
  const normalized = productName?.toLowerCase() ?? '';
  if (normalized.includes('postgres')) return PostgreSQL;
  if (normalized.includes('mariadb')) return MariaSQL;
  if (normalized.includes('mysql')) return MySQL;
  if (normalized.includes('sql server') || normalized.includes('microsoft')) return MSSQL;
  if (normalized.includes('sqlite')) return SQLite;
  if (normalized.includes('oracle')) return PLSQL;
  return StandardSQL;
};

const snippets = (): Completion[] => [
  snippetCompletion('SELECT ${columns}\nFROM ${table};', {
    label: 'SELECT … FROM …', type: 'keyword', boost: 40,
  }),
  snippetCompletion('INSERT INTO ${table} (${columns})\nVALUES (${values});', {
    label: 'INSERT INTO …', type: 'keyword', boost: 40,
  }),
  snippetCompletion('UPDATE ${table}\nSET ${column} = ${value}\nWHERE ${condition};', {
    label: 'UPDATE … SET …', type: 'keyword', boost: 40,
  }),
  snippetCompletion('DELETE FROM ${table}\nWHERE ${condition};', {
    label: 'DELETE FROM …', type: 'keyword', boost: 40,
  }),
  snippetCompletion('JOIN ${table} ${alias}\n  ON ${condition}', {
    label: 'JOIN … ON …', type: 'keyword', boost: 35,
  }),
  snippetCompletion('WITH ${name} AS (\n  SELECT ${columns}\n  FROM ${table}\n)\nSELECT *\nFROM ${name};', {
    label: 'WITH … AS …', type: 'keyword', boost: 35,
  }),
  snippetCompletion('CASE\n  WHEN ${condition} THEN ${value}\n  ELSE ${fallback}\nEND', {
    label: 'CASE … WHEN …', type: 'keyword', boost: 30,
  }),
  snippetCompletion('COUNT(${expression})', {
    label: 'COUNT(…)', type: 'function', detail: 'COUNT(expression) → BIGINT', boost: 30,
  }),
  snippetCompletion('SUM(${expression})', {
    label: 'SUM(…)', type: 'function', detail: 'SUM(expression) → numeric', boost: 30,
  }),
  snippetCompletion('AVG(${expression})', {
    label: 'AVG(…)', type: 'function', detail: 'AVG(expression) → numeric', boost: 30,
  }),
  snippetCompletion('COALESCE(${expression}, ${fallback})', {
    label: 'COALESCE(…)', type: 'function', detail: 'COALESCE(value, fallback) → value', boost: 30,
  }),
  snippetCompletion('NULLIF(${left}, ${right})', {
    label: 'NULLIF(…)', type: 'function', detail: 'NULLIF(left, right) → value', boost: 30,
  }),
  snippetCompletion('CAST(${expression} AS ${type})', {
    label: 'CAST(… AS …)', type: 'function', detail: 'CAST(expression AS type)', boost: 30,
  }),
  snippetCompletion('ROW_NUMBER() OVER (\n  PARTITION BY ${partition}\n  ORDER BY ${ordering}\n)', {
    label: 'ROW_NUMBER() OVER …', type: 'function', detail: 'ROW_NUMBER() OVER (…)', boost: 25,
  }),
];

const toCompletion = (suggestion: SqlCompletionSuggestion): Completion => ({
  label: suggestion.label,
  apply: suggestion.apply,
  type: suggestion.type,
  detail: suggestion.detail,
  info: suggestion.info,
  boost: suggestion.boost,
});

const completionSource = (
  dialect: SQLDialect,
  metadata?: SqlCompletionMetadataProvider,
): CompletionSource => {
  const keywords = keywordCompletionSource(dialect, true);
  const templates = snippets();
  return async context => {
    const document = context.state.doc.toString();
    const analysis = analyzeSqlCompletion(document, context.pos);
    if (analysis.blocked) return null;
    const keywordResult = await keywords(context);
    let metadataOptions: Completion[] = [];
    if (metadata) {
      try {
        metadataOptions = (await completeSqlMetadata(analysis, metadata)).map(toCompletion);
      } catch {
        // Metadata availability must not disable local keywords and snippets.
      }
    }
    const keywordOptions = keywordResult && keywordResult.from === analysis.from
      ? keywordResult.options
      : [];
    const templateOptions = analysis.qualifier.length === 0 ? templates : [];
    const options = [...metadataOptions, ...templateOptions, ...keywordOptions];
    if (options.length === 0 || (!context.explicit && analysis.prefix === ''
      && analysis.expected === 'expression' && metadataOptions.length === 0)) {
      return null;
    }
    return {
      from: analysis.from,
      options,
      validFor: completionWord,
    };
  };
};

const sqlSyntaxLinter = (message: string) => linter(view => {
  const text = view.state.doc.toString();
  if (!text.trim() || /^\s*FLUSH\s+CACHE\s*;?\s*$/i.test(text)) return [];
  const diagnostics: Diagnostic[] = [];
  const locations = new Set<string>();
  syntaxTree(view.state).iterate({
    enter(node) {
      if (!node.type.isError) return;
      const from = node.from === node.to ? Math.max(0, node.from - 1) : node.from;
      const key = `${from}:${node.to}`;
      if (locations.has(key)) return;
      locations.add(key);
      diagnostics.push({from, to: node.to, severity: 'error', message, source: 'SQL'});
    },
  });
  return diagnostics;
}, {delay: 650});

const metadataInteractions = (
  metadata: SqlCompletionMetadataProvider,
  revealLabel: string,
  onReveal?: (target: SqlMetadataTarget) => void,
) => [
  hoverTooltip(async (view, position) => {
    const document = view.state.doc.toString();
    try {
      const resolved = await resolveSqlSymbol(document, position, metadata);
      if (!resolved || view.state.doc.toString() !== document) return null;
      return {
        pos: resolved.from,
        end: resolved.to,
        above: true,
        create() {
          const dom = window.document.createElement('div');
          dom.className = 'dna-sql-symbol-tooltip';
          const title = window.document.createElement('strong');
          title.textContent = resolved.suggestion.label;
          dom.append(title);
          if (resolved.suggestion.detail) {
            const detail = window.document.createElement('div');
            detail.className = 'dna-sql-symbol-tooltip-detail';
            detail.textContent = resolved.suggestion.detail;
            dom.append(detail);
          }
          if (resolved.suggestion.info) {
            const info = window.document.createElement('div');
            info.className = 'dna-sql-symbol-tooltip-info';
            info.textContent = resolved.suggestion.info;
            dom.append(info);
          }
          if (resolved.suggestion.metadataTarget && onReveal) {
            const button = window.document.createElement('button');
            button.type = 'button';
            button.className = 'dna-sql-symbol-tooltip-action';
            button.textContent = revealLabel;
            button.addEventListener('click', () => onReveal(resolved.suggestion.metadataTarget!));
            dom.append(button);
          }
          return {dom};
        },
      };
    } catch {
      return null;
    }
  }),
  EditorView.domEventHandlers({
    mousedown(event, view) {
      if ((!event.ctrlKey && !event.metaKey) || !onReveal) return false;
      const position = view.posAtCoords({x: event.clientX, y: event.clientY});
      if (position == null) return false;
      event.preventDefault();
      const document = view.state.doc.toString();
      void resolveSqlSymbol(document, position, metadata)
        .then(resolved => {
          if (resolved?.suggestion.metadataTarget && view.state.doc.toString() === document) {
            onReveal(resolved.suggestion.metadataTarget);
          }
        })
        .catch(() => undefined);
      return true;
    },
  }),
];

export const SqlEditor = forwardRef<SqlEditorRef, SqlEditorProps>(
  ({value, onChange, readOnly = false, onExecute, onFormat, dark, completion, onRevealMetadata,
    revealMetadataLabel = 'Reveal in metadata tree', syntaxErrorMessage = 'SQL syntax error'}, ref) => {
    const editorRef = useRef<ReactCodeMirrorRef>(null);
    const onExecuteRef = useRef(onExecute);
    const onFormatRef = useRef(onFormat);
    onExecuteRef.current = onExecute;
    onFormatRef.current = onFormat;

    const databaseProductName = completion?.dataSources
      .find(source => source.code === completion.catalogCode)?.databaseProductName;

    const formatSql = useCallback(async (): Promise<SqlFormatResult> => {
      const view = editorRef.current?.view;
      if (!view) return 'empty';
      const {from, to} = view.state.selection.main;
      const selection = from !== to;
      const source = view.state.doc.sliceString(selection ? from : 0, selection ? to : view.state.doc.length);
      if (!source.trim()) return 'empty';
      const {formatSqlText} = await import('./sqlFormatting');
      const formatted = formatSqlText(source, databaseProductName);
      if (formatted === source) return 'unchanged';
      const changeFrom = selection ? from : 0;
      const changeTo = selection ? to : view.state.doc.length;
      view.dispatch({
        changes: {from: changeFrom, to: changeTo, insert: formatted},
        ...(selection ? {selection: {anchor: changeFrom, head: changeFrom + formatted.length}} : {}),
        scrollIntoView: true,
      });
      view.focus();
      return selection ? 'selection' : 'document';
    }, [databaseProductName]);

    useImperativeHandle(ref, () => ({
      getAllSql() {
        return editorRef.current?.view?.state.doc.toString() ?? value;
      },
      getExecutionSql() {
        const view = editorRef.current?.view;
        if (!view) return value;
        const {from, to} = view.state.selection.main;
        return executionSql(view.state.doc.toString(), from, to);
      },
      formatSql,
      insertText(text: string) {
        const view = editorRef.current?.view;
        if (!view) return;
        const {from, to} = view.state.selection.main;
        view.dispatch({
          changes: {from, to, insert: text},
          selection: {anchor: from + text.length},
        });
        view.focus();
      },
      appendStatement(text: string) {
        const view = editorRef.current?.view;
        if (!view) return;
        const document = view.state.doc.toString();
        const separator = document.trim() ? '\n\n' : '';
        const insertion = `${separator}${text}`;
        view.dispatch({
          changes: {from: document.length, insert: insertion},
          selection: {anchor: document.length + insertion.length},
          scrollIntoView: true,
        });
        view.focus();
      },
    }), [formatSql, value]);

    const dialect = useMemo(() => dialectFor(databaseProductName), [databaseProductName]);

    const interactionExtensions = useMemo(
      () => completion ? metadataInteractions(completion, revealMetadataLabel, onRevealMetadata) : [],
      [completion, onRevealMetadata, revealMetadataLabel],
    );

    const extensions = useMemo(
      () => [
        sqlLang({dialect}),
        syntaxHighlighting(dark ? darkSqlHighlight : lightSqlHighlight),
        autocompletion({override: [completionSource(dialect, completion)], activateOnTyping: true, maxRenderedOptions: 100}),
        sqlSyntaxLinter(syntaxErrorMessage),
        lintGutter(),
        ...interactionExtensions,
        EditorView.theme({"&": {height: "100%"}, ".cm-scroller": {overflow: "auto", fontFamily: "var(--sql-font)"}}),
        Prec.highest(keymap.of([
          {
            key: 'Ctrl-Enter',
            mac: 'Cmd-Enter',
            run: () => {
              onExecuteRef.current?.();
              return true;
            },
          },
          {
            key: 'Mod-Shift-f',
            run: () => {
              onFormatRef.current?.();
              return true;
            },
          },
        ])),
      ],
      [completion, dark, dialect, interactionExtensions, syntaxErrorMessage],
    );

    return (
      <CodeMirror
        ref={editorRef}
        theme={dark ? 'dark' : 'light'}
        value={value}
        editable={!readOnly}
        readOnly={readOnly}
        onChange={onChange}
        extensions={extensions}
        height="100%"
        style={{height: '100%', fontSize: 14}}
        basicSetup={{
          lineNumbers: true,
          highlightActiveLine: true,
          autocompletion: false,
          syntaxHighlighting: true,
          bracketMatching: true,
          closeBrackets: true,
          tabSize: 2,
        }}
      />
    );
  },
);

SqlEditor.displayName = 'SqlEditor';
