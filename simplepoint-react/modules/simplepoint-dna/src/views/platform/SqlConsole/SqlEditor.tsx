import {sql as sqlLang} from '@codemirror/lang-sql';
import {EditorView, keymap} from '@codemirror/view';
import {executionSql} from './sqlConsoleUtils';
import CodeMirror, {Prec, type ReactCodeMirrorRef} from '@uiw/react-codemirror';
import {forwardRef, useImperativeHandle, useMemo, useRef} from 'react';

export type SqlEditorRef = {
  insertText: (text: string) => void;
  getExecutionSql: () => string;
};

type SqlEditorProps = {
  value: string;
  onChange: (value: string) => void;
  onExecute?: () => void;
  dark?: boolean;
};

export const SqlEditor = forwardRef<SqlEditorRef, SqlEditorProps>(
  ({value, onChange, onExecute, dark}, ref) => {
    const editorRef = useRef<ReactCodeMirrorRef>(null);
    const onExecuteRef = useRef(onExecute);
    onExecuteRef.current = onExecute;

    useImperativeHandle(ref, () => ({
      getExecutionSql() {
        const view = editorRef.current?.view;
        if (!view) return value;
        const {from, to} = view.state.selection.main;
        return executionSql(view.state.doc.toString(), from, to);
      },
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
    }), [value]);

    const extensions = useMemo(
      () => [
        sqlLang(),
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
        ])),
      ],
      [],
    );

    return (
      <CodeMirror
        ref={editorRef}
        theme={dark ? 'dark' : 'light'}
        value={value}
        onChange={onChange}
        extensions={extensions}
        height="100%"
        style={{height: '100%', fontSize: 14}}
        basicSetup={{
          lineNumbers: true,
          highlightActiveLine: true,
          autocompletion: true,
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
