import api from '@/api/index';
import SimpleTable from '@simplepoint/components/SimpleTable';
import ResourceDrawer from '@simplepoint/components/ResourceDrawer';
import {hasRecordIdentity} from '@simplepoint/components/SimpleTable/recordIdentity';
import {useCallback, useEffect, useMemo, useRef, useState, type Key} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import ItemConfig from './config/item';

const baseConfig = api['platform.dictionaries'];
type DictionaryRow = {id: string; code?: string; name?: string};
const usableCode = (row?: DictionaryRow) => typeof row?.code === 'string' && !!row.code.trim() && row.code !== '***';

const DictionarySession = () => {
  const operation = useOperationScope();
  const [dictionary, setDictionary] = useState<DictionaryRow>();
  const [formOpen, setFormOpen] = useState(false);
  const [childLocked, setChildLocked] = useState(false);
  const childLock = useRef(false);
  const interactionLock = useCallback((locked: boolean) => {childLock.current = locked; setChildLocked(locked);}, []);
  const {t, ensure, locale} = useI18n();
  const permissions = useSchema(baseConfig.baseUrl, {staleTime: 30_000, retry: false});
  useEffect(() => {void ensure([...baseConfig.i18nNamespaces, 'table', 'common']);}, [ensure, locale]);
  const close = useCallback(() => {
    if (!operation.current() || !operation.isSelected() || childLock.current) return;
    setDictionary(undefined); setChildLocked(false);
  }, [operation.current, operation.isSelected, childLocked]);

  const customButtonEvents = useMemo(() => ({
    'config.item': (_keys: Key[], rows: DictionaryRow[]) => {
      if (!operation.current() || !operation.isSelected() || dictionary || formOpen || childLocked || permissions.error
        || !permissions.data?.buttons.some(button => button.key === 'config.item') || rows.length !== 1
        || !hasRecordIdentity(rows[0]) || !usableCode(rows[0])) return;
      setDictionary(structuredClone(rows[0]));
    },
  }), [operation.current, operation.isSelected, dictionary, formOpen, childLocked, permissions.data, permissions.error]);

  return (
    <div style={{height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column'}}>
      <SimpleTable {...baseConfig} schemaStaleTime={30_000} customButtonEvents={customButtonEvents}
        drawerOpen={formOpen} onDrawerOpenChange={setFormOpen} confirmDiscard
        isButtonDisabled={(button, _keys, rows: DictionaryRow[]) => !!dictionary || childLocked || !operation.selected
          || !!permissions.error || !permissions.data || button.key === 'config.item' && (rows.length !== 1 || !usableCode(rows[0]))}
        rowSelection={{isRowSelectable: () => !dictionary}}/>
      <ResourceDrawer rootClassName="dictionary-item-config" title={t('dictionaries.button.config.item', '配置字典项')}
        open={!!dictionary} locked={childLocked} onClose={close}>
        {dictionary?.code && <ItemConfig dictionaryCode={dictionary.code} onInteractionLockChange={interactionLock}/>}
      </ResourceDrawer>
    </div>
  );
};

export default function Dictionary() {
  return <DictionarySession key={JSON.stringify(useQueryScope())}/>;
}
