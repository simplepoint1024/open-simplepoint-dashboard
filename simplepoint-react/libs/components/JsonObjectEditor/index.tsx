import CodeMirror from '@uiw/react-codemirror';
import {linter} from '@codemirror/lint';
import {theme} from 'antd';
import {memo, useMemo} from 'react';
import {parseJsonObject} from './model';

/** Reusable data editor; callers can supply domain validation without a second editor stack. */
export default memo(function JsonObjectEditor({value, onChange, disabled, validate = parseJsonObject}: {
  value: string; onChange: (value: string) => void; disabled?: boolean;
  validate?: (value: string) => unknown;
}) {
  const {token} = theme.useToken();
  const extensions = useMemo(() => [linter(view => {
    try {validate(view.state.doc.toString()); return [];}
    catch (error) {return [{from: 0, to: view.state.doc.length,
      severity: 'error' as const, message: (error as Error).message}];}
  }, {delay: 300})], [validate]);
  return <CodeMirror value={value} onChange={onChange} extensions={extensions}
    editable={!disabled} height="180px" maxHeight="400px"
    style={{border: `1px solid ${token.colorBorder}`, borderRadius: token.borderRadius}}/>;
});
