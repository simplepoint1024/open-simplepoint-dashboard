import {autocompletion} from '@codemirror/autocomplete';
import {linter, type Diagnostic} from '@codemirror/lint';
import CodeMirror, {EditorState} from '@uiw/react-codemirror';
import {memo, useMemo} from 'react';
import {theme} from 'antd';
import {expressionIssue, type ExpressionVariable} from './model';

/** Loaded only on entering advanced mode; completions come from the caller's variable contract. */
export default memo(function AdvancedEditor({value, onChange, variables, disabled}: {
  value: string; onChange: (value: string) => void; variables: readonly ExpressionVariable[]; disabled?: boolean;
}) {
  const {token} = theme.useToken();
  const extensions = useMemo(() => [
    // CR inside a Flowable string is data, not an editor line separator.
    EditorState.lineSeparator.of('\n'),
    autocompletion({override: [context => {
      const word = context.matchBefore(/[A-Za-z_][A-Za-z0-9_.]*/);
      if (!word && !context.explicit) return null;
      return {from: word?.from ?? context.pos, options: variables.map(variable => ({
        label: variable.name, displayLabel: variable.label, detail: variable.type, type: 'variable',
      }))};
    }]}),
    linter(view => {
      const issue = expressionIssue(view.state.doc.toString());
      return issue ? [{from: 0, to: view.state.doc.length, severity: 'error', message: issue} as Diagnostic] : [];
    }, {delay: 300}),
  ], [variables]);
  return <CodeMirror value={value} onChange={onChange} extensions={extensions} editable={!disabled}
    minHeight="104px" maxHeight="240px" basicSetup={{lineNumbers: true, foldGutter: false}}
    style={{border: `1px solid ${token.colorBorder}`, borderRadius: token.borderRadius,
      color: token.colorText, background: token.colorBgContainer}}/>;
});
