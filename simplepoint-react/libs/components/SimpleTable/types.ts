import type { ColumnType } from 'antd/es/table';
import type {Page} from '@simplepoint/shared/types/request';
import type { TableButtonProps } from '../Table';

export type SimpleTableRefreshTargets = {
  page?: boolean;
  schema?: boolean;
};

export type SimpleTableColumnOverride<T> = Partial<ColumnType<T>> & {
  order?: number;
};

export type SimpleTableSubmitAction = 'add' | 'edit';
export type SimpleTableErrorAction = SimpleTableSubmitAction | 'delete';
export type SimpleTableErrorMessageResolver = (
  error: unknown,
  action: SimpleTableErrorAction,
) => string | undefined;

export type SimpleTablePageRequest = {
  page: number;
  size: number;
  filters: Record<string, string>;
  sort?: string;
  signal?: AbortSignal;
};

export type SimpleTableTreeConfig<T> = {
  hasChildren: (record: T) => boolean;
  loadChildren: (record: T) => Promise<T[]>;
};

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
  isButtonDisabled?: (
    button: TableButtonProps,
    selectedRowKeys: React.Key[],
    selectedRows: T[],
  ) => boolean;
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
  errorMessageResolver?: SimpleTableErrorMessageResolver;
  loadPage?: (request: SimpleTablePageRequest) => Promise<Page<T>>;
  tree?: SimpleTableTreeConfig<T>;
}
