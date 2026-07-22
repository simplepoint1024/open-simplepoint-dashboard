import {emptyPage} from "@simplepoint/shared/types/request"
import Table from '../Table';
import SForm from '../SForm';
import {Alert, Button, Drawer, Empty, Skeleton, Spin} from 'antd';
import {createIcon} from '@simplepoint/shared/types/icon';
import type {SimpleTableProps} from './types';
import {useSimpleTableController} from './useSimpleTableController';

const App = (props: SimpleTableProps<any>) => {
  const controller = useSimpleTableController(props);
  const { t } = controller;
  const { data: schemaData, formSchema, error: schemaError, retry: retrySchema } = controller.schema;
  const { bootLoading, tableLoading, submitLoading, drawerSchemaLoading, hasSchemaError, hasPageError, showPageWarning } = controller.status;
  const { data: pageData, filters, buttons } = controller.table;
  const { open: drawerOpen, editingRecord, setOpen: setDrawerOpen } = controller.drawer;
  const { defaultEvents, handleFormSubmit, retryPage } = controller.actions;
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
      description={t('table.pageLoadFail', '列表数据加载失败，请稍后重试。')}
      action={
        <Button size="small" onClick={() => void retryPage()}>
          {t('table.retry', '重试')}
        </Button>
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
    <div style={{height: '100%', display: 'flex', flexDirection: 'column'}}>
      {bootLoading ? (
        <div style={{ padding: 16 }}>
          <Skeleton active paragraph={{ rows: 1 }} />
          <div style={{ height: 12 }} />
          <Skeleton active title={false} paragraph={{ rows: 8 }} />
        </div>
      ) : hasSchemaError ? (
        <div style={{ padding: 16 }}>
          {renderSchemaError()}
        </div>
      ) : hasPageError ? (
        <div style={{ padding: 16 }}>
          {renderPageError()}
        </div>
      ) : (
        <div style={{flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column'}}>
          {showPageWarning ? (
            <div style={{ padding: '0 0 16px 0', flexShrink: 0 }}>
              {renderPageError()}
            </div>
          ) : null}
          <Table<any>
            refresh={controller.table.refresh}
            pageable={
              pageData ?? emptyPage
            }
            schema={schemaData?.schema ?? []}
            columnOverrides={props.columnOverrides}
            filters={filters}
            sorter={controller.table.sorter}
            onChange={controller.table.onChange}
            onFilterChange={controller.table.onFilterChange}
            storageKey={`${props.baseUrl}:${props.name}`}
            onButtonEvents={buttonEvents}
            onRowDoubleClick={editButton && buttonEvents[editButton.key]
              ? (record, key) => buttonEvents[editButton.key]([key], [record], editButton)
              : undefined}
            buttons={buttons}
            loading={tableLoading || submitLoading}
            refreshDisabled={controller.table.refreshDisabled}
          />
        </div>
      )}

      <Drawer
        closable={!submitLoading}
        title={
          <span style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 32, height: 32 }}>
            {createIcon(editingRecord ? 'EditOutlined' : 'PlusOutlined')}
          </span>
        }
        placement="right"
        width={480}
        open={drawerOpen}
        maskClosable={!submitLoading}
        keyboard={!submitLoading}
        onClose={() => {
          if (submitLoading) {
            return;
          }
          setDrawerOpen(false);
        }}
        destroyOnHidden
      >
        {drawerSchemaLoading ? (
          <div style={{ display: 'flex', justifyContent: 'center', padding: 24 }}>
            <Spin />
          </div>
        ) : schemaError ? (
          renderSchemaError()
        ) : !schemaData ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('table.noSchema', '暂无可用表单')} />
        ) : (
          <SForm
            schema={formSchema ?? schemaData.schema}
            uiSchema={props.formUiSchema}
            formData={editingRecord ?? props.initialValues ?? {}}
            onSubmit={handleFormSubmit}
            submitLoading={submitLoading}
          />
        )}
      </Drawer>
    </div>
  );
};

export default App;
