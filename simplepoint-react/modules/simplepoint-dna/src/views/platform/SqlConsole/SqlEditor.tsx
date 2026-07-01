import {sql as sqlLang} from '@codemirror/lang-sql';
import {keymap} from '@codemirror/view';
import CodeMirror, {type ReactCodeMirrorRef} from '@uiw/react-codemirror';
import {forwardRef, useImperativeHandle, useMemo, useRef} from 'react';

export type SqlEditorRef = {
  insertText: (text: string) => void;
};

type SqlEditorProps = {
  value: string;
  onChange: (value: string) => void;
  onExecute?: () => void;
};

export const SqlEditor = forwardRef<SqlEditorRef, SqlEditorProps>(
  ({value, onChange, onExecute}, ref) => {
    const editorRef = useRef<ReactCodeMirrorRef>(null);
    const onExecuteRef = useRef(onExecute);
    onExecuteRef.current = onExecute;

    useImperativeHandle(ref, () => ({
      insertText(text: string) {
        const view = editorRef.current?.view;
        if (!view) return;
        const {from} = view.state.selection.main;
        view.dispatch({
          changes: {from, to: from, insert: text},
          selection: {anchor: from + text.length},
        });
        view.focus();
      },
    }), []);

    const extensions = useMemo(
      () => [
        sqlLang(),
        keymap.of([
          {
            key: 'Ctrl-Enter',
            mac: 'Cmd-Enter',
            run: () => {
              onExecuteRef.current?.();
              return true;
            },
          },
        ]),
      ],
      [],
    );

    return (
      <CodeMirror
        ref={editorRef}
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
