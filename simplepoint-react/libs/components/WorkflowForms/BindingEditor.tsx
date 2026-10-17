import {Alert, Modal, Space, Typography} from 'antd';
import {useRef, useState} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import TableActions from '../Table/Actions';
import type {WorkflowFormBinding, WorkflowVariable} from '../WorkflowDesigner/contracts';
import SForm from '../SForm/Lazy';
import {useWorkflowFormAdapters} from './useWorkflowFormAdapters';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';

/** The design integration point appears only for real registered adapters or an existing saved binding. */
export default function BindingEditor({elementId, binding, startBinding, variables = [], onChange, disabled}: {
  elementId: string; binding?: WorkflowFormBinding;
  variables?: readonly WorkflowVariable[];
  startBinding?: WorkflowFormBinding;
  onChange: (binding?: WorkflowFormBinding) => void; disabled?: boolean;
}) {
  const {t} = useI18n();
  const selected = usePageSelected();
  const [chosenProvider, setChosenProvider] = useState<string>();
  const [unbindTarget, setUnbindTarget] = useState<WorkflowFormBinding>(), targetRef = useRef<WorkflowFormBinding | undefined>(undefined);
  usePageRetention(!unbindTarget, !!unbindTarget);
  const adapters = useWorkflowFormAdapters();
  const providers = adapters.filter(provider => provider.renderBindingEditor);
  if (!binding && !providers.length) return null;
  const provider = chosenProvider ?? binding?.provider;
  const adapter = adapters.find(adapter => adapter.provider === provider);
  return <Space direction="vertical" style={{width: '100%'}}>
    <Typography.Text strong>{t('workflow.form.binding', '表单绑定')}</Typography.Text>
    {providers.length > 0 && <SForm hideSubmit disabled={disabled || !!unbindTarget}
      schema={{type: 'object', properties: {provider: {type: 'string', title: t('workflow.form.provider', '表单引擎'),
        oneOf: providers.map(item => ({const: item.provider, title: item.provider}))}}}}
      formData={{provider}} onChange={event => {
        // Provider selection itself does not destroy the previous binding; its editor must emit a new one.
        setChosenProvider(event.formData?.provider);
      }}/>}
    {adapter?.renderBindingEditor ? adapter.renderBindingEditor({elementId, variables, startBinding,
      binding: binding?.provider === provider ? binding : undefined, readOnly: Boolean(disabled || unbindTarget),
      onChange: next => {if (!disabled && !targetRef.current && selected) onChange(next);}})
      : <Alert type="warning" showIcon title={t('workflow.form.providerUnavailable', '此流程绑定的表单提供方尚未接入，暂时不能提交')}
        description={binding ? `${binding.provider} · ${binding.formId} · v${binding.version}` : undefined}/>}
    {binding && !disabled && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'unbind', sort: 1, text: t('workflow.form.unbind', '解除绑定'), danger: true},
    ]} isButtonDisabled={() => !!unbindTarget || !selected} onButtonEvents={{unbind: () => {
      if (!binding || disabled || targetRef.current || !selected) return;
      const captured = {...binding, variableMappings: {...binding.variableMappings}};
      targetRef.current = captured; setUnbindTarget(captured);
    }}}/>}
    <Modal wrapClassName="workflow-binding-unbind" open={!!unbindTarget && selected} destroyOnHidden={!unbindTarget}
      title={t('workflow.form.unbindConfirm', '解除当前草稿的表单绑定？')}
      onCancel={() => {targetRef.current = undefined; setUnbindTarget(undefined);}}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 1, text: t('workflow.dialog.cancel', '取消')},
        {key: 'unbind', sort: 2, text: t('workflow.form.unbind', '解除绑定'), danger: true, type: 'primary', disabled},
      ]} onButtonEvents={{cancel: () => {targetRef.current = undefined; setUnbindTarget(undefined);}, unbind: () => {
        const captured = targetRef.current;
        if (!captured || disabled || !selected) return;
        // A changed binding cannot be removed by a confirmation for its predecessor.
        if (JSON.stringify(binding) === JSON.stringify(captured)) {onChange(undefined); setChosenProvider(undefined);}
        targetRef.current = undefined; setUnbindTarget(undefined);
      }}}/>}>
      {t('workflow.form.unbindBoundary', '已发布版本及运行中的实例不变。')}
    </Modal>
  </Space>;
}
