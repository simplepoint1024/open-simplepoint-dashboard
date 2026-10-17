import {Alert, Button} from 'antd';
import {useEffect, useState} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {importDiagnosticMessage, importProblemMessage, type ImportProblem} from './importIntegrity';
import './importIntegrity.css';

/** Preserve the exact source, not a serialization of an incomplete imported model. */
export default function ImportProblemNotice({problem, xml, progress = false}: {problem: ImportProblem; xml: string; progress?: boolean}) {
  const {t} = useI18n();
  const [download, setDownload] = useState<{xml: string; url: string}>();
  useEffect(() => {
    const url = URL.createObjectURL(new Blob([xml], {type: 'application/xml;charset=utf-8'}));
    setDownload({xml, url});
    return () => URL.revokeObjectURL(url);
  }, [xml]);
  return <Alert className="workflow-import-problem workflow-designer__import-problem" type={progress ? 'warning' : 'error'} showIcon
    title={progress ? t('workflow.import.progress', '流程图无法完整显示，请以节点处理记录和流程操作记录为准') : importProblemMessage(problem, t)} description={<>
      <div>{progress ? t('workflow.import.progressPreserved', '该版本原始文件未被修改，审批记录仍可查看。可下载原始 BPMN 交由维护人员检查。')
        : t('workflow.import.preserved', '原始文件未被修改。请修复源文件后重新打开；可先下载原始 BPMN 交由维护人员检查。')}</div>
      {problem.diagnostics.length > 0 && <details>
        <summary>{t('workflow.import.details', '查看导入问题（最多显示 20 项）')}</summary>
        <ul>{problem.diagnostics.map((diagnostic, index) => <li key={index}>
          {importDiagnosticMessage(diagnostic, t)}
        </li>)}</ul>
      </details>}
      {/* Source-file recovery is a canvas operation, not a server resource action. */}
      <Button type="link" href={download?.xml === xml ? download.url : undefined}
        disabled={download?.xml !== xml} download="workflow-original.bpmn">
        {t('workflow.import.download', '下载原始 BPMN')}
      </Button>
    </>}/>;
}
