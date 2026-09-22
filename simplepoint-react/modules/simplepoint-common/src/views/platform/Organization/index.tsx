import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTablePageRequest} from '@simplepoint/components/SimpleTable/types';
import type {OrganizationOption} from '@simplepoint/components/OrganizationSelect';
import {get} from '@simplepoint/shared/api/methods';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import type {Key} from 'react';
import {useCallback, useState, useSyncExternalStore} from 'react';
import {EMPTY_QUERY_SCOPE, getQueryScopeSnapshot, subscribeQueryScope} from '@simplepoint/shared/api/queryScope';
import OrganizationMembersPanel from './OrganizationMembersPanel';
import './index.css';

const baseConfig = api['platform.organizations'];
const treeEndpoint = `${baseConfig.baseUrl}/tree`;

type OrganizationRow = OrganizationOption & {
  children?: OrganizationRow[];
};

const hasChildren = (record: OrganizationRow) => record.hasChildren;

const App = () => {
  const scope = useSyncExternalStore(subscribeQueryScope, getQueryScopeSnapshot, () => EMPTY_QUERY_SCOPE);
  return <OrganizationWorkspace key={scope} scope={scope}/>;
};

const OrganizationWorkspace = ({scope}: {scope: string}) => {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<any | null>(null);
  const [initialValues, setInitialValues] = useState<any>({});
  const [treeMode, setTreeMode] = useState(true);
  const [selectedOrganization, setSelectedOrganization] = useState<OrganizationRow>();

  const loadPage = useCallback(async ({page, size, filters, sort, signal}: SimpleTablePageRequest) => {
    const flat = Object.keys(filters).length > 0 || (Array.isArray(sort) ? sort.length > 0 : Boolean(sort));
    setTreeMode(!flat);
    return normalizePage(await get<RawPage<OrganizationRow>>(flat ? baseConfig.baseUrl : treeEndpoint, {
      ...filters, page, size, ...(sort ? {sort} : {}),
    }, {signal}));
  }, []);

  const loadChildrenPage = useCallback(async (record: OrganizationRow, page: number, signal: AbortSignal) => {
    const result = normalizePage(await get<RawPage<OrganizationRow>>(treeEndpoint, {
      treeParentId: record.id, page, size: 50,
    }, {signal}));
    return {content: result.content, hasNext: result.page.number + 1 < result.page.totalPages};
  }, []);

  const customButtonEvents = {
    add: (_keys: Key[], rows: any[]) => {
      setEditingRecord(null);
      setInitialValues({
        parentId: rows?.[0]?.id ?? undefined,
        enabled: true,
      });
      setDrawerOpen(true);
    },
  };

  const formSchemaTransform = useCallback((schema: any, currentEditing: any | null) => {
    const parentId = schema?.properties?.parentId;
    if (!parentId) return schema;
    const xUi = parentId['x-ui'] ?? {};
    return {
      ...schema,
      properties: {
        ...schema.properties,
        parentId: {
          ...parentId,
          'x-ui': {
            ...xUi,
            widget: 'OrganizationSelect',
            options: {
              ...(xUi.options ?? {}),
              ...(currentEditing?.id ? {excludeId: String(currentEditing.id)} : {}),
            },
          },
        },
      },
    };
  }, []);

  const handleDrawerOpenChange = (open: boolean) => {
    setDrawerOpen(open);
    if (!open) {
      setEditingRecord(null);
      setInitialValues({});
    }
  };

  return <div className="organization-workspace">
    <div className="organization-workspace-list">
    <SimpleTable
      {...baseConfig}
      loadPage={loadPage}
      tree={treeMode ? {hasChildren, loadChildrenPage} : undefined}
      drawerOpen={drawerOpen}
      onDrawerOpenChange={handleDrawerOpenChange}
      editingRecord={editingRecord}
      onEditingRecordChange={setEditingRecord}
      initialValues={initialValues}
      customButtonEvents={customButtonEvents}
      formSchemaTransform={formSchemaTransform}
      rowSelection={{selectedKeys: selectedOrganization ? [selectedOrganization.id] : []}}
      onSelectionChange={(_keys, rows) => setSelectedOrganization(rows[rows.length - 1])}
    />
    </div>
    <OrganizationMembersPanel organization={selectedOrganization} scope={scope}/>
  </div>;
};

export default App;
