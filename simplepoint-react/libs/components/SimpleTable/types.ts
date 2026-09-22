import type { ColumnType } from 'antd/es/table';
import type {Page} from '@simplepoint/shared/types/request';
import type { TableButtonProps, TableProps } from '../Table';

/** Business pages may own their queries/forms while reusing the platform table view.
 * This mode does not fetch a CRUD schema or create a second pagination/query controller.
 */
export type ControlledSimpleTableProps<T extends object> = TableProps<T> & {
  mode: 'controlled';
};

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
  /** Transport-safe conditions: columns named page/size/sort are merged into $query. */
  filters: Record<string, string>;
  sort?: string | string[];
  signal?: AbortSignal;
};

export type SimpleTableTreeConfig<T> = {
  hasChildren: (record: T) => boolean;
  loadChildren?: (record: T, signal?: AbortSignal) => Promise<T[]>;
  loadChildrenPage?: (record: T, page: number, signal: AbortSignal) => Promise<import('../TreeTable/childPages').ChildPage<T>>;
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
  /** Opt in only if the entire route has no state outside this table that must survive eviction. */
  allowTabEviction?: boolean;
  schemaStaleTime?: number;
  virtual?: boolean;
  queryContext?: string;
  confirmDiscard?: boolean;
  drawerTitle?: string;
  formCustomValidate?: import('@rjsf/utils').CustomValidator<any>;
  onRowDoubleClick?: (record: T, key: React.Key) => void;
  onRowClick?: (record: T, key: React.Key) => void;
  rowSelection?: TableProps<T>['rowSelection'];
  onSelectionChange?: TableProps<T>['onSelectionChange'];
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
