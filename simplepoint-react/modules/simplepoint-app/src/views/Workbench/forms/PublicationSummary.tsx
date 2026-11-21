import {Alert, Collapse, Tag} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {get, useData} from '@simplepoint/shared/api/methods';
import {createIcon} from '@simplepoint/shared/types/icon';
import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import type {DesignDocument, FormSummary} from '@simplepoint/components/FormDesigner/contracts';
import type {PublicationIntent, PublicationProgress} from './publication';
import {publicationChanges} from '@simplepoint/components/FormDesigner/definitionChanges';

type Props = {base: string; document: DesignDocument; revision: number; intent?: PublicationIntent; progress?: PublicationProgress | null; open: boolean};
export default function PublicationSummary({base, document, revision, intent, progress, open}: Props) {
  const {t} = useI18n(), selected = usePageSelected();
  const query = useData(['app', 'publication-summary', base, revision, intent?.version], async ({signal}) => {
    const [form, versions] = await Promise.all([
      get<FormSummary>(base, undefined, {signal}),
      get<{version: number; document: DesignDocument}[]>(base + '/versions', undefined, {signal}),
    ]);
    const before = form.publishedVersion == null ? undefined : versions.find(version => version.version === form.publishedVersion)?.document;
    if (form.publishedVersion != null && !before) throw new Error('Published definition unavailable');
    const candidate = intent?.version == null ? document : versions.find(version => version.version === intent.version)?.document;
    if (!candidate || intent && intent.version == null && intent.revision !== revision) throw new Error('Captured definition unavailable');
    return {before, candidate, version: form.publishedVersion};
  }, {enabled: open && selected, retry: false, refetchOnMount: 'always', refetchOnWindowFocus: false});
  const labels = {
    added: t('app.publication.fieldAdded', '新增字段'), removed: t('app.publication.fieldRemoved', '移除字段'),
    formula: t('app.publication.formulaChanged', '计算方式变化'), attributes: t('app.publication.fieldChanged', '字段属性变化'),
  };
  const changes = query.data && !query.error && !query.isFetching ? publicationChanges(query.data.before, query.data.candidate) : [];
  const conversions = query.data?.candidate.relations?.filter(relation => relation.retiredByCollectionId
    && query.data?.before?.relations?.some(previous => previous.relationId === relation.relationId && !previous.retiredByCollectionId)) ?? [];
  const replacements = query.data?.candidate.relations?.filter(relation => relation.collectionReplacement
    && query.data?.before?.relations?.some(previous => previous.relationId === relation.collectionReplacement?.sourceRelationId)) ?? [];
  return <Collapse className="app-publication-summary" defaultActiveKey={['summary']} items={[
    {key: 'summary', label: <span>{createIcon('DiffOutlined')} {t('app.publication.summary', '发布影响')}</span>, children: <>
      {query.error ? <Alert type="warning" showIcon title={t('app.publication.summaryFailed', '发布摘要加载失败，请重试；不能据此判断没有影响。')}
        action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('app.retry', '重试')}]}
          onButtonEvents={{retry: () => void query.refetch()}}/>}/> : query.isFetching ? <span>{t('app.publication.summaryLoading', '正在读取已发布定义…')}</span>
        : query.data && <>
          <Tag>{query.data.version == null ? t('app.publication.firstRelease', '首次发布')
            : t('app.publication.compareVersion', '对比已发布版本 {version}', {version: query.data.version})}</Tag>
          {conversions.length > 0 && <Alert type="warning" showIcon
            title={t('app.relation.collection.conversionEffect', '发布会把现有引用迁入新集合，并停用原引用字段。历史记录保留。')}/>}
          {replacements.length > 0 && <Alert type="warning" showIcon
            title={t('app.relation.collection.rebindEffect', '发布会按映射迁移现有关联，切换后停用旧关联，并保留两张表单的业务记录。')}/>}
          {!changes.length ? !conversions.length && !replacements.length && <p>{t('app.publication.noFieldChanges', '未发现字段定义变化。')}</p> : <DataTable rowKey="key" dataSource={changes} cards={false} settings={false} selectable={false} pagination={changes.length > 5 ? {pageSize: 5} : false}
            scroll={{x: 350, y: 210}} columns={[
              {key: 'label', dataIndex: 'label', title: t('app.designer.fieldLabel', '字段名称')},
              {key: 'kind', title: t('app.publication.change', '变更'), render: (_, row) => labels[row.kind]},
            ]}/>}
        </>}
      <p>{progress ? t('app.publication.affectedRecords', '本次核算记录数：{count}', {count: progress.totalRecords})
        : t('app.publication.recordsPending', '影响记录数将在服务端发布核算后确认。')}</p>
      <p>{t('app.publication.backfillHint', '涉及计算结果或数据约束的变更，将按服务端评估执行存量校验与回填。核算通过前不会切换页面。')}</p>
    </>},
  ]}/>;
}
