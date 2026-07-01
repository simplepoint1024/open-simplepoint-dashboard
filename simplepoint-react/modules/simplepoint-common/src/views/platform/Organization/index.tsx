import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {Key} from 'react';
import {useCallback, useState} from 'react';

const baseConfig = api['platform.organizations'];

const App = () => {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<any | null>(null);
  const [initialValues, setInitialValues] = useState<any>({});

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
    const next = structuredClone(schema);
    const options = next?.properties?.parentId?.oneOf;
    if (!Array.isArray(options) || !currentEditing?.id) {
      return next;
    }
    next.properties.parentId.oneOf = options.filter(
      (option: any) => String(option?.const ?? '') !== String(currentEditing.id)
    );
    return next;
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
