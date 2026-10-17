import {useState} from 'react';
import {Alert, Modal, Space, Typography} from 'antd';
import EntitySelect, {type EntitySelectSource} from '@simplepoint/components/EntitySelect';
import {entitySearchParams, type EntityOption} from '@simplepoint/components/EntitySelect/query';
import TableActions from '@simplepoint/components/Table/Actions';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import useFormPublication from './useFormPublication';
import PublicationInspection from './PublicationInspection';
import type {DesignPage} from './designPage';

const source: EntitySelectSource = {key: 'app-publication-pages-v1', searchParams: entitySearchParams,
  option: row => typeof row.id === 'string' && typeof row.name === 'string'
    ? {value: row.id, label: row.name, metadata: row} : undefined};

type Props = {initialPage?: DesignPage; applicationId: string; formId: string; revision: number; checksum: string; open: boolean;
  fields: readonly {fieldId: string; label: string}[];
  enabled: boolean; onClose: () => void; onViewData: (pageId: string) => void; onBusyChange: (busy: boolean) => void};

/** One destination and one publish action; infrastructure preparation stays on the server. */
export default function FormPublication(props: Props) {
  const {t} = useI18n(), selected = usePageSelected();
  const [page, setPage] = useState<EntityOption | undefined>(() => props.initialPage ? {value: props.initialPage.id, label: props.initialPage.name, metadata: {formId: props.initialPage.formId}} : undefined);
  const base = `/app/applications/${encodeURIComponent(props.applicationId)}`;
  const publication = useFormPublication(`${base}/forms/${encodeURIComponent(props.formId)}`, props.enabled, props.open);
  const captured = publication.intent;
  const replacement = Boolean(page?.metadata?.formId && page.metadata.formId !== props.formId);
  const close = () => {if (!publication.busy) {props.onBusyChange(false); props.onClose();}};
  const publish = async () => {
    if (!page || !selected || !props.enabled) return;
    props.onBusyChange(true);
    try {await publication.run({formId: props.formId, revision: props.revision, checksum: props.checksum, pageId: page.value});}
    finally {props.onBusyChange(false);}
  };
  return <Modal rootClassName="app-form-publication" open={props.open && selected}
    title={t('app.publication.title', '发布表单')} destroyOnHidden={false}
    onCancel={close} closable={!publication.busy} maskClosable={!publication.busy}
    footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'close', sort: 0, text: t('app.publication.close', '关闭'), disabled: publication.busy},
      ...(publication.active ? [{key: 'data', sort: 1, type: 'primary' as const, text: t('app.publication.viewData', '查看数据')},
        {key: 'another', sort: 2, text: t('app.publication.another', '发布当前草稿')}] : [{key: 'publish', sort: 1, type: 'primary' as const,
        text: publication.busy || publication.pending ? t('app.publication.publishing', '正在发布')
          : captured ? t('app.publication.retry', '重试本次发布')
            : replacement ? t('app.publication.replace', '发布并替换页面') : t('app.publication.publish', '发布'),
        disabled: !page || !props.enabled || publication.busy || publication.pending || !selected}]),
    ]} onButtonEvents={{close, publish: () => void publish(), another: () => publication.reset(),
      data: () => {const pageId = publication.release?.pageBinding?.pageId; if (pageId) {close(); props.onViewData(pageId);}}}}/>}>
    <Space orientation="vertical" style={{width: '100%'}} size="middle">
      <Typography.Paragraph>{t('app.publication.hint', '选择应用中的页面作为入口。发布成功后，用户即可按页面权限填写和查看数据。')}</Typography.Paragraph>
      <label htmlFor="app-form-publication-page">{t('app.publication.page', '目标页面')}</label>
      <EntitySelect id="app-form-publication-page" endpoint={base + '/publication-pages'} source={source}
        value={captured?.pageId ?? page?.value} disabled={Boolean(captured) || publication.busy}
        onChange={(_, option) => setPage(option)} placeholder={t('app.publication.selectPage', '请选择目标页面')}/>
      {replacement && !publication.active && <Alert type="warning" showIcon
        title={t('app.publication.replaceHint', '此页面已绑定其他表单，发布后将切换为当前表单。原表单的数据会保留。')}/>}
      {captured && <Typography.Text type="secondary">{t('app.publication.snapshot', '本次发布固定使用草稿修订 {revision}，重试不会改用后续编辑内容。', {revision: captured.revision})}</Typography.Text>}
      {publication.error && <Alert type="error" showIcon title={publication.error}
        description={publication.invalidFields.length > 0 ? <ul>{props.fields.filter(field => publication.invalidFields.includes(field.fieldId))
          .map(field => <li key={field.fieldId}>{field.label}</li>)}</ul> : undefined}/>}
      {publication.pending && <Alert type="info" showIcon title={t('app.publication.wait', '正在准备表单和数据，请稍候。可以关闭此窗口，稍后继续查看。')}/>}
      <PublicationInspection inspection={publication.release?.inspection} onViewData={pageId => {close(); props.onViewData(pageId);}}/>
      {publication.cancelled && <Alert type="info" showIcon
        title={t('app.publication.cancelled', '本次发布已取消，现有页面保持不变。')}/>}
      {publication.active && <Alert type="success" showIcon title={t('app.publication.success', '表单已发布，可以开始使用。')}/>}
    </Space>
  </Modal>;
}
