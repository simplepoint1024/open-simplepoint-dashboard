import {emptyPage} from "@simplepoint/shared/types/request"
import Table from '../Table';
import type {TableButtonProps} from '../Table';
import TreeTable from '../TreeTable';
import {Alert, Button, Drawer, Empty, Modal, Space} from 'antd';
import {LoadingBoundary, LoadingPlaceholder} from '../LoadingFeedback';
import {createIcon} from '@simplepoint/shared/types/icon';
import type {ControlledSimpleTableProps, SimpleTableProps} from './types';
import {useSimpleTableController} from './useSimpleTableController';
import {hasRecordIdentity} from './recordIdentity';
import {lazy, Suspense, useEffect, useRef, useState} from 'react';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

const SForm = lazy(() => import('../SForm'));

const SchemaTable = (props: SimpleTableProps<any>) => {
  const selected = usePageSelected();
  const controller = useSimpleTableController(props);
  const { t } = controller;
  const { data: schemaData, formSchema, error: schemaError, retry: retrySchema, dictionariesError, retryDictionaries } = controller.schema;
  const { bootLoading, tableLoading, submitLoading, submitError, deleteOpen, drawerSchemaLoading, hasSchemaError, hasPageError, showPageWarning } = controller.status;
  const { data: pageData, filters, buttons } = controller.table;
  const { open: drawerOpen, editingRecord, setOpen: setDrawerOpen } = controller.drawer;
  const { defaultEvents, handleFormSubmit, retryPage } = controller.actions;
  const drawerWasOpenRef = useRef(false);
  const [dirty, setDirty] = useState(false);
  const [discard, setDiscard] = useState(false);
  const discardRef = useRef(false);
  const changeDiscard = (next: boolean) => {discardRef.current = next; setDiscard(next);};
  const [formDraft, setFormDraft] = useState<any>(() => (
    editingRecord ?? props.initialValues ?? {}
  ));

  // RJSF treats formData as controlled state. Recreating an empty object while a
  // request is in flight makes it discard the user's input when server-side
  // validation fails. Start a fresh draft only when a new drawer session opens;
  // loading/error rerenders then keep the same draft intact.
  useEffect(() => {
    if (drawerOpen && !drawerWasOpenRef.current) {
      setFormDraft(editingRecord ?? props.initialValues ?? {});
      setDirty(false);
    }
    if (!drawerOpen) {setDirty(false); changeDiscard(false);}
    drawerWasOpenRef.current = drawerOpen;
  }, [drawerOpen, editingRecord, props.initialValues]);
  const hasUnidentifiableRows = pageData?.content?.some((row: unknown) => !hasRecordIdentity(row)) ?? false;
  const buttonEvents = {
    ...defaultEvents,
    ...(props.customButtonEvents ?? {}),
  };
  // The server schema already filters buttons by the current authorization context.
  // Do not infer edit permission from page-local custom buttons.
  const editButton = schemaData?.buttons?.find((button: {key?: string}) => button.key === 'edit');

  const renderPageError = () => (
    <Alert
      type="error"
      showIcon
      message={t('table.loadFail', '加载失败')}
      description={controller.status.pageErrorMessage}
      action={
        <Space wrap>
          {(Object.keys(filters).length > 0 || controller.table.sorter) && (
            <Button size="small" onClick={() => controller.table.onQueryChange(props.initialFilters ?? {}, undefined)}>
              {t('table.query.reset', '重置筛选与排序')}
            </Button>
          )}
          <Button size="small" onClick={() => void retryPage()}>
            {t('table.retry', '重试')}
          </Button>
        </Space>
      }
    />
  );

  const renderSchemaError = () => (
    <Alert
      type="error"
      showIcon
      message={t('table.loadFail', '加载失败')}
      description={(schemaError as Error)?.message ?? t('table.schemaLoadFail', '页面结构加载失败，请稍后重试。')}
      action={
        <Button size="small" onClick={() => void retrySchema()}>
          {t('table.retry', '重试')}
        </Button>
      }
    />
  );

  return (
    <div data-resource={props.baseUrl} style={{height: '100%', display: 'flex', flexDirection: 'column'}}>
      {bootLoading ? (
        <LoadingPlaceholder />
      ) : hasSchemaError ? (
        <div style={{ padding: 16 }}>
          {renderSchemaError()}
        </div>
      ) : (
        <div style={{flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column'}}>
          {hasPageError || showPageWarning ? (
            <div style={{ padding: '0 0 16px 0', flexShrink: 0 }}>
              {renderPageError()}
            </div>
          ) : null}
          {hasUnidentifiableRows && <Alert type="info" showIcon style={{marginBottom: 12, flexShrink: 0}}
            message={t('table.restrictedRecordIdentity', '部分记录的标识不可用，无法选择、展开或执行记录操作。请检查当前角色的字段权限。')}/>}
          {(() => {
            const tableProps = {
              refresh: controller.table.refresh,
              pageable: pageData ?? emptyPage,
              schema: schemaData?.schema ?? [],
              columnOverrides: props.columnOverrides,
              filters,
              sorter: controller.table.sorter,
              onQueryChange: controller.table.onQueryChange,
              virtual: props.virtual ?? true,
              onChange: controller.table.onChange,
              onFilterChange: controller.table.onFilterChange,
              storageKey: props.storageKey ?? `${props.baseUrl}:${props.name}`,
              onButtonEvents: buttonEvents,
              isButtonDisabled: (button: TableButtonProps, keys: React.Key[], rows: any[]) =>
                tableLoading || submitLoading || deleteOpen || !!props.isButtonDisabled?.(button, keys, rows),
              onRowDoubleClick: props.onRowDoubleClick ?? (editButton && buttonEvents[editButton.key]
                ? (record: any, key: React.Key) => {
                  if (props.isButtonDisabled?.(editButton, [key], [record])) return;
                  if (tableLoading || submitLoading || deleteOpen) return;
                  buttonEvents[editButton.key]([key], [record], editButton);
                }
                : undefined),
              onRowClick: props.onRowClick,
              rowSelection: {
                ...props.rowSelection,
                isRowSelectable: (record: any) => !submitLoading && !deleteOpen && hasRecordIdentity(record)
                  && props.rowSelection?.isRowSelectable?.(record) !== false,
              },
              onSelectionChange: props.onSelectionChange,
              selectionResetKey: controller.table.selectionResetKey,
              buttons,
              loading: tableLoading || submitLoading,
              refreshDisabled: controller.table.refreshDisabled || deleteOpen,
            };
            return props.tree
              ? <TreeTable<any> {...tableProps} {...props.tree} />
              : <Table<any> {...tableProps} />;
          })()}
        </div>
      )}

      <Drawer
        rootClassName="sp-simple-table-drawer"
        closable={!submitLoading}
        title={props.drawerTitle ?? (
          <span style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 32, height: 32 }}>
            {createIcon(editingRecord ? 'EditOutlined' : 'PlusOutlined')}
          </span>
        )}
        placement="right"
        width={props.drawerWidth ?? 480}
        open={drawerOpen && selected}
        maskClosable={!submitLoading}
        keyboard={!submitLoading}
        onClose={() => {
          if (!controller.drawer.canClose()) {
            return;
          }
          if (props.confirmDiscard && dirty) changeDiscard(true);
          else setDrawerOpen(false);
        }}
        destroyOnHidden={!drawerOpen}
      >
        <LoadingBoundary local label={t('loading', '加载中...')}>
        {drawerSchemaLoading ? (
          <LoadingPlaceholder />
        ) : schemaError ? (
          renderSchemaError()
        ) : dictionariesError ? (
          <Alert
            type="error"
            showIcon
            title={t('table.loadFail', '加载失败')}
            description={(dictionariesError as Error).message}
            action={<Button onClick={() => void retryDictionaries()}>{t('table.retry', '重试')}</Button>}
          />
        ) : !schemaData ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('table.noSchema', '暂无可用表单')} />
        ) : (
          <Suspense fallback={<LoadingPlaceholder />}>
            {submitError && <Alert type="error" showIcon title={submitError} />}
            <SForm
              schema={formSchema ?? schemaData.schema}
              uiSchema={typeof props.formUiSchema === 'function' ? props.formUiSchema(formDraft, formSchema ?? schemaData.schema) : props.formUiSchema}
              formData={formDraft}
              onChange={(event) => {
                if (controller.status.isSubmitting() || discardRef.current) return;
                const next = event.formData ?? {};
                setFormDraft(props.formDataTransform ? props.formDataTransform(next, formDraft) : next); setDirty(true);
              }}
              onSubmit={handleFormSubmit}
              onBeforeSubmit={() => !discardRef.current && controller.actions.beginFormSubmit()}
              onError={controller.actions.handleFormError}
              submitLoading={submitLoading}
              submitDisabled={discard}
              disabled={submitLoading || discard}
              customValidate={props.formCustomValidate}
            />
          </Suspense>
        )}
        </LoadingBoundary>
      </Drawer>
      <Modal wrapClassName="sp-simple-table-discard" open={discard && selected && drawerOpen} title={t('personal.discard', '放弃未保存的修改？')}
        onCancel={() => {if (controller.drawer.canClose()) changeDiscard(false);}}
        onOk={() => {if (controller.drawer.canClose()) {changeDiscard(false); setDrawerOpen(false);}}}
        okText={t('personal.discardConfirm', '放弃修改')} cancelText={t('personal.continueEditing', '继续编辑')} />
      <Modal wrapClassName="sp-simple-table-delete" open={controller.deletion.open && selected}
        destroyOnHidden={!controller.deletion.open} title={t('table.confirmDeleteTitle', '确认删除')}
        confirmLoading={submitLoading} closable={!submitLoading} maskClosable={!submitLoading} keyboard={!submitLoading}
        cancelButtonProps={{disabled: submitLoading}} okButtonProps={{danger: true}}
        onOk={() => void controller.deletion.confirm()} onCancel={controller.deletion.cancel}>
        {t('table.confirmDeleteContent', '确定要删除选中的 {count} 条数据吗？', {count: controller.deletion.count})}
        {controller.deletion.error && <Alert type="error" showIcon title={controller.deletion.error}/>}
      </Modal>
    </div>
  );
};

function ControlledTable<T extends object>({mode: _mode, ...props}: ControlledSimpleTableProps<T>) {
  const {ensure, locale} = useI18n();
  useEffect(() => { void ensure(['table']); }, [ensure, locale]);
  return <Table<T> {...props} />;
}

/** Keep the schema-driven and externally controlled lifecycles in separate components. */
const App = <T extends object = any>(props: SimpleTableProps<T> | ControlledSimpleTableProps<T>) => {
  if ('mode' in props) {
    return <ControlledTable<T> {...props} />;
  }
  return <SchemaTable {...props} />;
};

export default App;
