import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTablePageRequest} from '@simplepoint/components/SimpleTable/types';
import type {OrganizationOption} from '@simplepoint/components/OrganizationSelect';
import {get} from '@simplepoint/shared/api/methods';
import type {Page} from '@simplepoint/shared/types/request';
import type {Key} from 'react';
import {useCallback, useState} from 'react';

const baseConfig = api['platform.organizations'];
const optionsEndpoint = `${baseConfig.baseUrl}/options`;

type OrganizationRow = OrganizationOption & {
  children?: OrganizationRow[];
};

type OrganizationOptionPage = {
  content: OrganizationRow[];
  number: number;
  size: number;
  hasNext: boolean;
};

function toPage(result: OrganizationOptionPage): Page<OrganizationRow> {
  const content = result.content ?? [];
  const number = result.number ?? 0;
  const size = result.size ?? 20;
  return {
    content,
    page: {
      number,
      size,
      totalElements: number * size + content.length + (result.hasNext ? 1 : 0),
      totalPages: number + 1 + (result.hasNext ? 1 : 0),
    },
  };
}

async function decorateRows(rows: OrganizationRow[]) {
  if (rows.length === 0) return rows;
  const options = await get<OrganizationOptionPage>(optionsEndpoint, {
    ids: rows.map(row => row.id).join(','),
    size: String(Math.min(rows.length, 100)),
  });
  const optionById = new Map((options.content ?? []).map(option => [option.id, option]));
  return rows.map(row => ({...row, ...optionById.get(row.id)}));
}

const App = () => {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<any | null>(null);
  const [initialValues, setInitialValues] = useState<any>({});

  const loadPage = useCallback(async ({page, size, filters, sort, signal}: SimpleTablePageRequest) => {
    const hasFlatQuery = Object.keys(filters).length > 0 || Boolean(sort);
    if (hasFlatQuery) {
      const result = await get<Page<OrganizationRow>>(baseConfig.baseUrl, {
        page,
        size,
        ...filters,
        ...(sort ? {sort} : {}),
      }, {signal});
      return {...result, content: await decorateRows(result.content ?? [])};
    }
    const result = await get<OrganizationOptionPage>(optionsEndpoint, {
      page: String(page),
      size: String(size),
    }, {signal});
    return toPage(result);
  }, []);

  const loadChildren = useCallback(async (record: OrganizationRow) => {
    const result = await get<OrganizationOptionPage>(optionsEndpoint, {
      parentId: record.id,
      page: '0',
      size: '100',
    });
    return result.content ?? [];
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

  return (
    <SimpleTable
      {...baseConfig}
      loadPage={loadPage}
      tree={{
        hasChildren: (record: OrganizationRow) => record.hasChildren,
        loadChildren,
      }}
      drawerOpen={drawerOpen}
      onDrawerOpenChange={handleDrawerOpenChange}
      editingRecord={editingRecord}
      onEditingRecordChange={setEditingRecord}
      initialValues={initialValues}
      customButtonEvents={customButtonEvents}
      formSchemaTransform={formSchemaTransform}
    />
  );
};

export default App;
