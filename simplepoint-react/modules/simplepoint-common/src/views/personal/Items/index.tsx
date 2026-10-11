import {useSearchParams} from 'react-router';
import {Alert} from 'antd';
import WorkflowMatters from '@simplepoint/components/WorkflowRuntime/Matters';
import TableActions from '@simplepoint/components/Table/Actions';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import LegacyItems from '../components/LegacyItems';

/** Preserve existing personal-item links/data while making workflow the primary matters view. */
export default function PersonalItems() {
  const [params, setParams] = useSearchParams(), {t} = useI18n();
  if (!params.has('item') && params.get('legacy') !== '1') return <WorkflowMatters/>;
  return <><Alert showIcon type="info" title={t('workflow.personal.legacyItems', '历史个人事项，现有数据保留')}
    action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{
      key: 'workflow', sort: 1, text: t('workflow.personal.backToMatters', '返回流程事项'),
    }]} onButtonEvents={{workflow: () => setParams({})}}/>}/><LegacyItems/></>;
}
