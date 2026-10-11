import api from '@/api/index';
import SimpleTable from '@simplepoint/components/SimpleTable';
import ResourceDrawer from '@simplepoint/components/ResourceDrawer';
import {hasRecordIdentity} from '@simplepoint/components/SimpleTable/recordIdentity';
import {useCallback, useEffect, useMemo, useRef, useState, type Key} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import ApplicationConfig from './config/application';

const baseConfig = api['platform.packages'];
type PackageRow = {id: string; code?: string};
const usableCode = (row?: PackageRow) => typeof row?.code === 'string' && !!row.code.trim() && !/^\*+$/.test(row.code.trim());

function PackageSession() {
  const operation = useOperationScope();
  const [configuration, setConfiguration] = useState<PackageRow>();
  const [formOpen, setFormOpen] = useState(false);
  const [childLocked, setChildLocked] = useState(false);
  const childLock = useRef(false);
  const configurationLock = useRef<PackageRow | undefined>(undefined);
  const rootLock = useRef(false);
  const {t, ensure, locale} = useI18n();
  const permissions = useSchema(baseConfig.baseUrl, {staleTime: 30_000, retry: false});
  useEffect(() => {void ensure([...baseConfig.i18nNamespaces, 'applications', 'table', 'common']);}, [ensure, locale]);
  const interactionLock = useCallback((locked: boolean) => {childLock.current = locked; setChildLocked(locked);}, []);
  const rootInteraction = useCallback((locked: boolean) => {rootLock.current = locked;}, []);
  const close = useCallback(() => {
    if (!operation.current() || !operation.isSelected() || childLock.current) return;
    configurationLock.current = undefined; setConfiguration(undefined); interactionLock(false);
  }, [operation.current, operation.isSelected, interactionLock]);
  const customButtonEvents = useMemo(() => ({
    'config.application': (_keys: Key[], rows: PackageRow[]) => {
      if (!operation.current() || !operation.isSelected() || configurationLock.current || rootLock.current || formOpen || childLock.current || permissions.error
        || !permissions.data?.buttons.some(button => button.key === 'config.application') || rows.length !== 1
        || !hasRecordIdentity(rows[0]) || !usableCode(rows[0])) return;
      const captured = structuredClone(rows[0]); configurationLock.current = captured; setConfiguration(captured);
    },
  }), [operation.current, operation.isSelected, configuration, formOpen, permissions.data, permissions.error]);
  return <div style={{height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column'}}>
    <SimpleTable {...baseConfig} schemaStaleTime={30_000} allowTabEviction={!configuration} confirmDiscard
      customButtonEvents={customButtonEvents} drawerOpen={formOpen} onDrawerOpenChange={setFormOpen}
      onInteractionLockChange={rootInteraction}
      isButtonDisabled={(button, _keys, rows: PackageRow[]) => !!configurationLock.current || rootLock.current || childLocked || formOpen || !operation.selected
        || !!permissions.error || !permissions.data || button.key === 'config.application' && (rows.length !== 1 || !usableCode(rows[0]))}
      rowSelection={{isRowSelectable: () => !configurationLock.current && !rootLock.current && !formOpen}}/>
    <ResourceDrawer rootClassName="package-application-config" title={t('packages.button.config.application', '配置应用')}
      open={!!configuration} locked={childLocked} onClose={close}>
      {configuration?.code && <ApplicationConfig packageCode={configuration.code} onInteractionLockChange={interactionLock}/>}
    </ResourceDrawer>
  </div>;
}

export default function Package() {return <PackageSession key={JSON.stringify(useQueryScope())}/>;}
