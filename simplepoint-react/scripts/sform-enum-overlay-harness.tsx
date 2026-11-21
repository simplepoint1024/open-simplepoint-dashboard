import {useState} from 'react';
import {Modal, Drawer} from 'antd';
import PageSection from '../libs/shared/page/Section';
import SForm from '../libs/components/SForm';

/** Isolated real platform enum fields inside the same clipping boundaries as inline row editors. */
export default function EnumOverlayHarness() {
  const [state, setState] = useState({drawer: false, open: true, selected: true, disabled: false});
  const [values, setValues] = useState<Record<string, unknown>>({number: 0, truth: false, many: []});
  Object.assign(window, {enumFixture: {values: () => values, set: (patch: Partial<typeof state>) => setState(current => ({...current, ...patch}))}});
  const fields = <div style={{height: 300}}><div id="enum_clip" style={{height: 70, overflow: 'auto'}}><SForm
    hideSubmit disabled={state.disabled} idPrefix="enum_fixture" formData={values}
    schema={{type: 'object', properties: {
      number: {type: 'integer', oneOf: [{const: 0, title: 'Zero'}, {const: 2, title: 'Two'}]},
      truth: {type: 'boolean', oneOf: [{const: false, title: 'False'}, {const: true, title: 'True'}]},
      many: {type: 'array', uniqueItems: true, items: {type: 'string', enum: ['日本語', 'English']}},
    }}} uiSchema={{truth: {'ui:widget': 'select'}, many: {'ui:widget': 'select'}}}
    onChange={({formData}, changed) => {if (changed && formData) setValues(formData);}}/></div></div>;
  return <PageSection selected={state.selected}>{state.drawer
    ? <Drawer open={state.open} width={600} title="Enum drawer" destroyOnHidden={false}>{fields}</Drawer>
    : <Modal open={state.open} width={600} title="Enum modal" footer={null} destroyOnHidden={false}>{fields}</Modal>}
  </PageSection>;
}
