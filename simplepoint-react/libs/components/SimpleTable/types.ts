import type {RegistryFieldsType} from '@rjsf/utils';
import type { ColumnType } from 'antd/es/table';
import type {Page} from '@simplepoint/shared/types/request';
import type {ReactNode} from 'react';
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
/** Custom layouts share the table's draft, drawer guard, permission gate and write lifecycle. */
export type SimpleTableFormContext = {
  formData: any;
  editingRecord: any | null;
  disabled: boolean;
  readOnly: boolean;
  submitting: boolean;
  onChange: (value: any) => void;
  /** Call after layout-specific validation; the controller accepts at most one active write. */
  onSubmit: (value: any) => Promise<void>;
};
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
  /** Read-only asynchronous preparation is cancelable before a real write is dispatched. */
  signal: AbortSignal;
};

export type SimpleTableAfterSubmitContext = SimpleTableBeforeSubmitContext & {
  submittedData: any;
  result: unknown;
};

export interface SimpleTableProps<T> {
  cards?: TableProps<T>['cards'];
  /** Opt in only if the entire route has no state outside this table that must survive eviction. */
  allowTabEviction?: boolean;
  schemaStaleTime?: number;
  virtual?: boolean;
  queryContext?: string;
  confirmDiscard?: boolean;
  drawerTitle?: string;
  drawerWidth?: number | string;
  /** Freeze inputs while a custom writer retains an uncertain operation for exact retry. */
  formReadOnly?: boolean;
  /** Keep the editor available until a custom writer resolves an uncertain commit. */
  formCloseDisabled?: boolean;
  formCustomValidate?: import('@rjsf/utils').CustomValidator<any>;
  onRowDoubleClick?: (record: T, key: React.Key) => void;
  onRowClick?: (record: T, key: React.Key) => void;
  rowSelection?: TableProps<T>['rowSelection'];
  onSelectionChange?: TableProps<T>['onSelectionChange'];
  name: string;
  /** Preserve a resource's established table preference key when migrating its CRUD controller. */
  storageKey?: string;
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
  /** Let a containing configuration panel protect this table's nested editor/confirmation/write. */
  onInteractionLockChange?: (locked: boolean) => void;
  editingRecord?: any | null;
  onEditingRecordChange?: (record: any | null) => void;
  initialValues?: any;
  onSubmit?: (
    action: SimpleTableSubmitAction,
    formData: any,
    currentEditing: any | null,
    context: SimpleTableBeforeSubmitContext,
  ) => Promise<unknown> | unknown;
  beforeSubmit?: (context: SimpleTableBeforeSubmitContext) => Promise<any> | any;
  /** Synchronous final projection for the default writer, after captured-record merging and field ACL filtering. */
  defaultSubmitDataTransform?: (data: any, context: SimpleTableBeforeSubmitContext) => any;
  afterSubmit?: (context: SimpleTableAfterSubmitContext) => Promise<void> | void;
  formSchemaTransform?: (schema: any, editingRecord: any | null) => any;
  /** Shared presentation metadata for table filters and forms; query field identifiers stay unchanged. */
  schemaTransform?: (schema: any) => any;
  /** Loads a complete editing snapshot when list rows deliberately omit child or large values. */
  loadEditingRecord?: (record: T, signal: AbortSignal) => Promise<T>;
  formFields?: RegistryFieldsType;
  renderForm?: (context: SimpleTableFormContext) => ReactNode;
  formUiSchema?: Record<string, any> | ((formData: any, schema: any, current: T | null) => Record<string, any>);
  /** Genuine form-engine change hook, e.g. clear a typed selection when its directory discriminator changes. */
  formDataTransform?: (next: any, previous: any) => any;
  columnOverrides?: Record<string, SimpleTableColumnOverride<T>>;
  i18nNamespaces: string[];
  submitRefreshTargets?: SimpleTableRefreshTargets;
  deleteRefreshTargets?: SimpleTableRefreshTargets;
  errorMessageResolver?: SimpleTableErrorMessageResolver;
  loadPage?: (request: SimpleTablePageRequest) => Promise<Page<T>>;
  /** Executes a resource-specific optimistic delete while preserving the shared confirmation UI. */
  deleteRows?: (rows: T[], signal: AbortSignal) => Promise<unknown> | unknown;
  tree?: SimpleTableTreeConfig<T>;
}
