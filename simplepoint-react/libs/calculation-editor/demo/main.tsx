import {useState} from 'react';
import {createRoot} from 'react-dom/client';
import CalculationEditor from '../src/index';
const functions = [
  {name: 'SUM', category: 'AGGREGATE', minArguments: 1, maxArguments: 256, resultType: 'NUMBER', parameters: ['values']},
  {name: 'IF', category: 'LOGIC', minArguments: 2, maxArguments: 3, resultType: 'ANY', parameters: ['condition', 'true', 'false']},
  {name: 'ROUND', category: 'MATH', minArguments: 2, maxArguments: 2, resultType: 'NUMBER', parameters: ['number', 'digits']},
];
function Demo() {
  const [value, setValue] = useState('=ROUND([单价]*[数量],2)');
  const [readOnly, setReadOnly] = useState(false);
  const [acceptChanges, setAcceptChanges] = useState(true);
  return <main style={{maxWidth: 960, margin: '24px auto', padding: 12}}>
    <fieldset><legend>Host controls</legend>
      <label><input type="checkbox" checked={readOnly} onChange={event => setReadOnly(event.target.checked)}/>Read only</label>
      <label><input type="checkbox" checked={acceptChanges} onChange={event => setAcceptChanges(event.target.checked)}/>Accept changes</label>
      <button type="button" onClick={() => setValue('=SUM(7,11)')}>Replace from host</button>
    </fieldset>
    <CalculationEditor value={value} readOnly={readOnly} onChange={next => {if (acceptChanges) setValue(next);}} functions={functions}
      fields={[{id: '单价', label: '单价', type: 'DECIMAL'}, {id: '数量', label: '数量', type: 'INT32'}]}/>
    <output data-testid="formula">{value}</output>
  </main>;
}
createRoot(document.getElementById('root')!).render(<Demo/>);
