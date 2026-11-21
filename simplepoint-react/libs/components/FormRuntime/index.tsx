import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {mappingIntent, reconcileMappings, type MappingDraft, type MappingIntent} from './referenceMapping/model';
import {localizeFormDocument} from './localization';
import type {CalculationPreviewLoader} from './calculationPreview';
import {Alert, Drawer, Modal} from 'antd';
import {useEffect, useMemo, useRef, useState, type ReactNode, type Ref} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import PageSection from '@simplepoint/shared/page/Section';
import type {SFormHandle} from '../SForm';
import TableActions from '../Table/Actions';
import type {ApplicationFormDocument} from './contracts.ts';
import type {RecordState} from './recordTableModel.ts';
import type {FormComponentRegistry} from './componentRegistry.ts';
import type {RuntimeFormAction} from './model.ts';
import SceneForm from './SceneForm';
import RecordMetadata from './RecordMetadata';
import {canOpenScene, sameFormValues, sceneDraft, type SceneDraft, type FormValues} from './sceneState.ts';
import './style.css';
import {ReferenceRuntimeContext, type ReferenceSources} from '../FormComponents/Reference/RuntimeContext';

import {ReferenceSnapshotContext} from '../FormComponents/Reference/SnapshotContext';
import {snapshotLabels, type FormReferenceSnapshot} from '../FormComponents/Reference/snapshots';

export type FormRuntimeProps = {
  referenceSnapshots?: readonly FormReferenceSnapshot[];
  references?: ReferenceSources;
  fixedReferenceField?: string;
  calculationPreview?: CalculationPreviewLoader;
  document: ApplicationFormDocument;
  sceneId: string;
  value?: FormValues;
  systemValues?: Record<string, unknown>;
  systemLabels?: Record<string, unknown>;
  recordState?: RecordState;
  disabled?: boolean;
  /** Browse immutable snapshots and secondary scenes without enabling any editing actions. */
  readOnly?: boolean;
  /** The owning dialog renders its own submit action while scene navigation remains available. */
  externalSubmit?: boolean;
  submitting?: boolean;
  formRef?: Ref<SFormHandle>;
  registry?: FormComponentRegistry;
  notice?: ReactNode;
  onChange?: (value: FormValues) => void;
  onSubmit?: (value: FormValues, mappings?: MappingIntent) => void;
  onAction?: (action: 'SAVE_DRAFT' | 'SUBMIT', value: FormValues, mappings?: MappingIntent) => boolean | void | Promise<boolean | void>;
};

/** Shared scene host for published pages, design previews and workflow form adapters. */
export default function FormRuntime(props: FormRuntimeProps) {
  const scope = JSON.stringify(useQueryScope());
  const [owner, setOwner] = useState({document: props.document, references: props.references,
    snapshots: props.referenceSnapshots, scope, generation: 0});
  if (owner.document !== props.document || owner.references !== props.references
    || owner.snapshots !== props.referenceSnapshots || owner.scope !== scope) {
    setOwner({document: props.document, references: props.references, snapshots: props.referenceSnapshots,
      scope, generation: owner.generation + 1});
  }
  // Scene overlays, preparation leases and confirmation drafts belong to one immutable owner.
  // A new owner unmounts pending callbacks instead of reusing an old scene's save/discard state.
  return <FormRuntimeSession key={owner.generation} {...props}/>;
}

function FormRuntimeSession(props: FormRuntimeProps) {
  const {document: sourceDocument, sceneId, value = {}, disabled = false, submitting = false, formRef,
    registry, notice, onChange, onSubmit, onAction, recordState, readOnly = false, externalSubmit = false} = props;
  const {t, locale} = useI18n();
  const frozenLabels = useMemo(() => props.referenceSnapshots === undefined ? undefined : snapshotLabels(props.referenceSnapshots), [props.referenceSnapshots]);
  const document = useMemo(() => localizeFormDocument(sourceDocument, locale), [sourceDocument, locale]);
  const selected = usePageSelected();
  const [mappings, setMappings] = useState<MappingDraft>([]);
  const changeRoot = (values: FormValues, draft = reconcileMappings(mappings, values)) => {setMappings(draft); onChange?.(values);};
  const [stack, setStack] = useState<SceneDraft[]>([]);
  const [discard, setDiscard] = useState(false);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState(false);
  const stackRef = useRef(stack), alive = useRef(true), writing = useRef(false);
  stackRef.current = stack;
  useEffect(() => {alive.current = true; return () => {alive.current = false;};}, []);
  const busy = submitting || pending;
  const dirty = stack.some(frame => (!sameFormValues(frame.initial, frame.values) || !sameFormValues(frame.initialMappings, frame.mappings)));
  usePageRetention(!dirty && !busy, dirty || busy);
  const activeIds = [sceneId, ...stack.map(frame => frame.sceneId)];
  const allowed = (action: RuntimeFormAction) => canOpenScene(document, action, activeIds);
  const open = (action: RuntimeFormAction) => {
    if (!selected || disabled || busy || discard || !allowed(action)) return;
    const current = stackRef.current[stackRef.current.length - 1]?.values ?? value;
    const next = [...stackRef.current, sceneDraft(action.targetSceneId!, current, stackRef.current[stackRef.current.length - 1]?.mappings ?? mappings)];
    stackRef.current = next; setStack(next); setFailure(false);
  };
  const replaceTop = (values: FormValues, draft?: MappingDraft) => {
    if (!selected || disabled || readOnly || busy || discard) return;
    setStack(current => current.map((frame, index) => index === current.length - 1 ? {...frame, values, mappings: draft ?? reconcileMappings(frame.mappings, values)} : frame));
  };
  const dismiss = () => {
    const next = stackRef.current.slice(0, -1);
    stackRef.current = next; setStack(next); setDiscard(false);
  };
  const close = () => {
    if (!selected || busy || discard) return;
    const top = stackRef.current[stackRef.current.length - 1];
    if (top && (!sameFormValues(top.initial, top.values) || !sameFormValues(top.initialMappings, top.mappings))) setDiscard(true);
    else dismiss();
  };
  const apply = (values: FormValues) => {
    if (!selected || disabled || readOnly || busy || discard) return;
    const draft = reconcileMappings(stackRef.current[stackRef.current.length - 1]?.mappings ?? [], values);
    const next = stackRef.current.slice(0, -1);
    if (next.length) next[next.length - 1] = {...next[next.length - 1], values, mappings: draft};
    else changeRoot(values, draft);
    stackRef.current = next; setStack(next);
  };
  const persist = async (action: 'SAVE_DRAFT' | 'SUBMIT', values: FormValues) => {
    if (!selected || disabled || readOnly || busy || writing.current || discard || !onAction) return;
    writing.current = true; setPending(true); setFailure(false);
    try {
      const result = await onAction(action, values, mappingIntent(stackRef.current[stackRef.current.length - 1]?.mappings ?? mappings, values));
      if (alive.current && result === true) {stackRef.current = []; setStack([]);}
    } catch {
      if (alive.current) setFailure(true);
    } finally {
      writing.current = false;
      if (alive.current) setPending(false);
    }
  };
  const feedback = <>{notice}{failure && <Alert type="error" showIcon
    title={t('app.runtime.saveFailed', '数据保存失败')}/>}</>;
  return <ReferenceSnapshotContext.Provider value={frozenLabels}><ReferenceRuntimeContext.Provider value={frozenLabels === undefined ? props.references : undefined}>
    <PageSection selected={stack.length === 0}>
      {stack.length === 0 && feedback}
      <SceneForm calculationPreview={props.calculationPreview} document={document} sceneId={sceneId} value={value} disabled={disabled} readOnly={readOnly} externalSubmit={externalSubmit} recordState={recordState}
        fixedReferenceField={props.fixedReferenceField}
        submitting={busy} formRef={formRef} registry={registry} onChange={values => changeRoot(values)}
        mappingDraft={mappings} onMappingChange={changeRoot}
        onSubmit={onSubmit ? values => onSubmit(values, mappingIntent(mappings, values)) : undefined}
        onAction={onAction ? (action, values) => void persist(action, values) : undefined}
        canOpen={allowed} onOpen={open}/>
      {readOnly && <RecordMetadata values={props.systemValues} labels={props.systemLabels}/>}
    </PageSection>
    {stack.map((frame, index) => {
      const scene = document.scenes.find(candidate => candidate.sceneId === frame.sceneId)!;
      const top = index === stack.length - 1;
      const content = <PageSection selected={top && !discard}>
        {top && feedback}
        <SceneForm calculationPreview={props.calculationPreview} document={document} sceneId={frame.sceneId} value={frame.values} recordState={recordState}
          fixedReferenceField={props.fixedReferenceField}
          disabled={disabled} readOnly={readOnly} externalSubmit={externalSubmit} submitting={busy} registry={registry} onChange={replaceTop}
          mappingDraft={frame.mappings} onMappingChange={replaceTop}
          canOpen={allowed} onOpen={open} onApply={apply} onClose={close}
          onAction={onAction ? (action, values) => void persist(action, values) : undefined}/>
      </PageSection>;
      // Modal and Drawer are scene containers; forms and every scene action use platform renderers.
      return scene.type === 'DIALOG'
        ? <Modal key={frame.sceneId} title={scene.name} width={scene.width ?? undefined} footer={null}
            wrapClassName="sp-form-runtime-dialog" open={selected && top} destroyOnHidden={false}
            maskClosable={false} keyboard={!busy} closable={!busy} onCancel={close}>{content}</Modal>
        : <Drawer key={frame.sceneId} title={scene.name} size={scene.width ?? 480}
            rootClassName="sp-form-runtime-drawer" open={selected && top} destroyOnHidden={false}
            maskClosable={false} keyboard={!busy} closable={!busy} onClose={close}>{content}</Drawer>;
    })}
    <Modal open={discard && selected} title={t('app.draft.discardTitle', '放弃未保存的修改？')}
      wrapClassName="sp-form-runtime-discard" onCancel={() => setDiscard(false)}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'continue', sort: 0, text: t('app.draft.continue', '继续编辑')},
        {key: 'discard', sort: 1, text: t('app.draft.discard', '放弃修改'), danger: true},
      ]} onButtonEvents={{continue: () => setDiscard(false), discard: dismiss}}/>}/>
  </ReferenceRuntimeContext.Provider></ReferenceSnapshotContext.Provider>;
}

export type {ApplicationFormDocument, ApplicationFormRuntimeView} from './contracts.ts';
export {runtimeFormModel} from './model.ts';
export {FormComponentRegistry, platformFormComponents} from './componentRegistry.ts';
export {createIdempotencyKey, editableValues, editableFieldIds, runtimeIdentity, runtimeDescriptorKey, recordMutation,
  recordPage, recordPageQuery, recordEditorSchema} from './recordTableModel.ts';
export type {FormRecord, FormRecordMutation, FormRecordPage, RecordRow, RecordState} from './recordTableModel.ts';

export {formRuntimeFields} from '../FormComponents/runtimeFields';

export {detailPayload} from '../FormComponents/Detail/payload';

export {recordEditorUiSchema} from './recordEditorUiSchema.ts';
