import {memo} from 'react';
import JsonObjectEditor from '../JsonObjectEditor';
import {parseVariables} from './model';

export default memo(function JsonVariablesEditor({value, onChange, disabled}: {
  value: string; onChange: (value: string) => void; disabled?: boolean;
}) {
  return <JsonObjectEditor value={value} onChange={onChange} disabled={disabled} validate={parseVariables}/>;
});
