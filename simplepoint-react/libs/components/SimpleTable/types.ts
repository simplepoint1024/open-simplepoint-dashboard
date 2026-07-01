import type { ColumnType } from 'antd/es/table';
import type { TableButtonProps } from '../Table';

export type SimpleTableRefreshTargets = {
  page?: boolean;
  schema?: boolean;
};

export type SimpleTableColumnOverride<T> = Partial<ColumnType<T>> & {
  order?: number;
};

export type SimpleTableSubmitAction = 'add' | 'edit';

export type SimpleTableBeforeSubmitContext = {
  action: SimpleTableSubmitAction;
  formData: any;
  currentEditing: any | null;
  baseUrl: string;
};

export type SimpleTableAfterSubmitContext = SimpleTableBeforeSubmitContext & {
  submittedData: any;
  result: unknown;
};

export interface SimpleTableProps<T> {
  name: string;
  baseUrl: string;
  initialFilters?: Record<string, string>;
  customButtonEvents?: Record<string, (selectedRowKeys: React.Key[], selectedRows: T[], props: TableButtonProps) => void>;
  customButtons?: TableButtonProps[];
  drawerOpen?: boolean;
  onDrawerOpenChange?: (open: boolean) => void;
  editingRecord?: any | null;
  onEditingRecordChange?: (record: any | null) => void;
  initialValues?: any;
  onSubmit?: (action: SimpleTableSubmitAction, formData: any, currentEditing: any | null) => Promise<unknown> | unknown;
  beforeSubmit?: (context: SimpleTableBeforeSubmitContext) => Promise<any> | any;
  afterSubmit?: (context: SimpleTableAfterSubmitContext) => Promise<void> | void;
  formSchemaTransform?: (schema: any, editingRecord: any | null) => any;
  formUiSchema?: Record<string, any>;
  columnOverrides?: Record<string, SimpleTableColumnOverride<T>>;
  i18nNamespaces: string[];
  submitRefreshTargets?: SimpleTableRefreshTargets;
  deleteRefreshTargets?: SimpleTableRefreshTargets;
}


