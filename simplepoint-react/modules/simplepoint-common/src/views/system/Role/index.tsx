import SimpleTable from '@simplepoint/components/SimpleTable';
import ResourceDrawer from '@simplepoint/components/ResourceDrawer';
import TableActions from '@simplepoint/components/Table/Actions';
import {hasRecordIdentity} from '@simplepoint/components/SimpleTable/recordIdentity';
import api from '@/api/index';
import {lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState, type Key} from 'react';
import {Modal, Spin} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import type {EditorState} from './config/resource';
import {normalizeRoleMetadata, RoleMetadataError} from './metadata';

const ResourceConfig = lazy(() => import('./config/resource'));
const baseConfig = api['rbac-roles'];
const resourceConfigNamespaces = [...baseConfig.i18nNamespaces, 'data-scopes', 'field-scopes', 'table', 'common'];
const defaultHeight = () => typeof window === 'undefined' ? 620
  : Math.min(Math.max(560, Math.round(window.innerHeight * 0.72)), window.innerHeight - 64);
type RoleRow = {id: string};
const emptyEditor: EditorState = {dirty: false, saving: false, locked: false};

function RoleSession() {
  const operation = useOperationScope();
  const {t, ensure, locale} = useI18n();
  const [configuration, setConfiguration] = useState<RoleRow>();
  const configurationRef = useRef<RoleRow | undefined>(undefined);
  const [editorState, setEditorState] = useState(emptyEditor);
  const editorRef = useRef(emptyEditor);
  const [discardFor, setDiscardFor] = useState<string>();
  const discardRef = useRef<string | undefined>(undefined);
  const [formOpen, setFormOpen] = useState(false);
  const rootLock = useRef(false);
  const [height] = useState(defaultHeight);
  const permissions = useSchema(baseConfig.baseUrl, {staleTime: 30_000, retry: false});
  useEffect(() => {void ensure(resourceConfigNamespaces);}, [ensure, locale]);
  const editorChanged = useCallback((state: EditorState) => {editorRef.current = state; setEditorState(state);}, []);
  const rootInteraction = useCallback((locked: boolean) => {rootLock.current = locked;}, []);
  const canInteract = useCallback(() => operation.current() && operation.isSelected() && !discardRef.current,
    [operation.current, operation.isSelected]);
  const close = useCallback(() => {
    if (!operation.current() || !operation.isSelected() || editorRef.current.saving) return;
    configurationRef.current = undefined; discardRef.current = undefined;
    setConfiguration(undefined); setDiscardFor(undefined); editorChanged(emptyEditor);
  }, [operation.current, operation.isSelected, editorChanged]);
  const requestClose = useCallback(() => {
    if (!operation.current() || !operation.isSelected() || editorRef.current.locked || discardRef.current) return;
    if (!editorRef.current.dirty) {close(); return;}
    const id = configurationRef.current?.id; if (!id) return;
    discardRef.current = id; setDiscardFor(id);
  }, [operation.current, operation.isSelected, close]);
  const cancelDiscard = () => {
    if (!operation.current() || !operation.isSelected()) return;
    discardRef.current = undefined; setDiscardFor(undefined);
  };
  const customButtonEvents = useMemo(() => ({
    'config.resource': (_keys: Key[], rows: RoleRow[]) => {
      if (!operation.current() || !operation.isSelected() || configurationRef.current || rootLock.current
        || permissions.error || !permissions.data?.buttons.some(button => button.key === 'config.resource')
        || rows.length !== 1 || !hasRecordIdentity(rows[0])) return;
      const captured = structuredClone(rows[0]); configurationRef.current = captured; setConfiguration(captured);
    },
  }), [operation.current, operation.isSelected, permissions.data, permissions.error]);
  return <div style={{height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column'}}>
    <SimpleTable {...baseConfig} schemaStaleTime={30_000} allowTabEviction={!configuration} confirmDiscard
      customButtonEvents={customButtonEvents} drawerOpen={formOpen} onDrawerOpenChange={setFormOpen}
      onInteractionLockChange={rootInteraction}
      isButtonDisabled={() => !!configurationRef.current || rootLock.current || !operation.selected || !!permissions.error || !permissions.data}
      rowSelection={{isRowSelectable: () => !configurationRef.current && !rootLock.current}}
      beforeSubmit={({formData}) => {
        try {return normalizeRoleMetadata(formData, permissions.data?.schema.properties ?? {});}
        catch (error) {if (error instanceof RoleMetadataError) throw new Error(t('roles.validation.blankMetadata', '角色名称和权限标识不能只包含空白')); throw error;}
      }}
      submitRefreshTargets={{page: true, schema: false}} deleteRefreshTargets={{page: true, schema: false}}/>
    <ResourceDrawer rootClassName="role-resource-drawer" title={t('roles.config.resource', '资源授权')}
      open={!!configuration} locked={editorState.locked || !!discardFor} onClose={requestClose}
      defaultHeight={height} minHeight={420} bodyPadding={0}>
      {configuration && <Suspense fallback={<div className="role-resource-loading"><Spin/></div>}>
        <ResourceConfig roleId={configuration.id} disabled={!!discardFor} canInteract={canInteract} onStateChange={editorChanged}/>
      </Suspense>}
    </ResourceDrawer>
    {discardFor && operation.selected && <Modal rootClassName="role-resource-close-discard" open destroyOnHidden maskClosable={false}
      title={t('roles.resourceConfig.discardOnClose', '存在未保存修改，确定放弃并关闭？')} onCancel={cancelDiscard}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 0, text: t('action.cancel', '取消'), onClick: cancelDiscard},
        {key: 'discard', sort: 1, danger: true, text: t('roles.resourceConfig.discardResourcesButton', '放弃修改'),
          onClick: () => {if (discardRef.current === configurationRef.current?.id) close();}},
      ]}/>}/>}
  </div>;
}

export default function Role() {return <RoleSession key={JSON.stringify(useQueryScope())}/>;}
