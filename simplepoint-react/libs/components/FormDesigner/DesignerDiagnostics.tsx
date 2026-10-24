import {Alert} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import DataTable from '../DataTable';
import TableActions from '../Table/Actions';
import {calculationErrorText, designIssues, type DesignIssue} from './calculationDiagnostics';
import type {DesignDocument} from './contracts';
import type {DesignerCalculations} from './CalculationFieldEditor';
import type {FieldTarget} from './fieldAnalysis';

export default function DesignerDiagnostics({document, catalog, onLocate}: {
  document: DesignDocument; catalog: DesignerCalculations; onLocate: (target: FieldTarget) => void;
}) {
  const {t} = useI18n(), issues = designIssues(document, catalog?.functions);
  return <div className="sp-form-diagnostics">
    <Alert showIcon type={issues.length ? 'warning' : catalog ? 'success' : 'info'} title={issues.length
      ? t('app.designer.checkIssues', '发现 {count} 项配置问题', {count: issues.length})
      : catalog ? t('app.designer.checkPassed', '本地配置检查通过；发布时仍会执行完整服务端校验')
        : t('app.calculation.catalogRequired', '加载计算函数后可编辑公式')}/>
    <DataTable<DesignIssue> rowKey={row => `${row.key}:${row.code}`} dataSource={issues} cards={false} settings={false} selectable={false}
      pagination={{defaultPageSize: 50}} scroll={{x: 450, y: 400}} columns={[
        {key: 'label', dataIndex: 'label', title: t('app.designer.fieldLabel', '字段名称'), width: 160},
        {key: 'message', title: t('app.designer.issue', '问题'), render: (_, row) => calculationErrorText(row.code, t)},
        {key: 'actions', title: t('app.designer.actions', '操作'), width: 90, render: (_, row) =>
          <TableActions appearance="row" selectedKeys={[]} selectedRows={[]} buttons={[
            {key: 'locate', sort: 0, icon: 'AimOutlined', text: t('app.designer.locate', '定位')},
          ]} onButtonEvents={{locate: () => onLocate(row)}}/>},
      ]}/>
  </div>;
}
