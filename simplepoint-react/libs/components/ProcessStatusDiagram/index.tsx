import {CompressOutlined, ZoomInOutlined, ZoomOutOutlined} from '@ant-design/icons';
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer';
import {Button, Space, Tooltip} from 'antd';
import {useEffect, useMemo, useRef, useState} from 'react';
import flowableModdle from '../WorkflowDesigner/flowableModdle';
import ImportProblemNotice from '../WorkflowDesigner/ImportProblemNotice';
import {importProblem, type ImportProblem} from '../WorkflowDesigner/importIntegrity';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
import './style.css';
import {activityMarkers, markerClass, type ProcessActivityState} from './state';
import {observeDiagramViewport} from './viewport';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
export type {ProcessActivityState} from './state';

export type ProcessStatusDiagramProps = {
  xml: string;
  activities: ProcessActivityState[];
  activeActivityIds?: string[];
  height?: number | string;
  className?: string;
  fitActive?: boolean;
  selectedActivityId?: string;
  onSelectActivity?: (activityId: string) => void;
  labels?: Partial<{active: string; completed: string; canceled: string; fit: string; zoomIn: string; zoomOut: string}>;
};

const EMPTY_IDS: string[] = [];
const STATE_MARKERS = ['workflow-status-active', 'workflow-status-completed', 'workflow-status-canceled', 'workflow-status-failed'];
type DiagramElement = {id: string; type?: string; businessObject?: {name?: string}};
type RegistryApi = {get: (id: string) => DiagramElement | undefined; getAll: () => DiagramElement[]};
type CanvasApi = {
  resized: () => void;
  zoom: (scale?: number | 'fit-viewport') => number;
  scrollToElement: (element: DiagramElement) => void;
  addMarker: (id: string, marker: string) => void;
  removeMarker: (id: string, marker: string) => void;
};

/** Read-only reusable BPMN execution-state viewer. */
export default function ProcessStatusDiagram({
  xml,
  activities,
  activeActivityIds = EMPTY_IDS,
  height = 520,
  className,
  fitActive = false,
  selectedActivityId,
  onSelectActivity,
  labels: labelOverrides,
}: ProcessStatusDiagramProps) {
  const {t} = useI18n();
  const labels = useMemo(() => ({
    active: t('workflow.diagram.active', '当前节点'), completed: t('workflow.status.COMPLETED', '已完成'),
    canceled: t('workflow.diagram.canceled', '已取消/异常'), fit: t('workflow.designer.fit', '适配画布'),
    zoomIn: t('workflow.designer.zoomIn', '放大'), zoomOut: t('workflow.designer.zoomOut', '缩小'), ...labelOverrides,
  }), [labelOverrides, t]);
  const containerRef = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<InstanceType<typeof NavigatedViewer> | undefined>(undefined);
  const [problem, setProblem] = useState<ImportProblem>();
  const [ready, setReady] = useState(false);
  const [nodes, setNodes] = useState<Array<{id: string; name: string}>>([]);
  const selection = useRef(onSelectActivity);
  selection.current = onSelectActivity;
  const viewport = useRef({fitActive, activeActivityIds});
  viewport.current = {fitActive, activeActivityIds};

  const states = useMemo(() => activityMarkers(activities, activeActivityIds), [activeActivityIds, activities]);

  useEffect(() => {
    setReady(false); setProblem(undefined); setNodes([]);
    if (!containerRef.current || !xml) {setProblem(importProblem([], true)); return;}
    let active = true;
    let stopResize: (() => void) | undefined;
    const viewer = new NavigatedViewer({
      container: containerRef.current,
      moddleExtensions: {flowable: flowableModdle},
    });
    viewerRef.current = viewer;
    viewer.on('element.click', (event: {element?: {id?: string; type?: string}}) => {
      const element = event.element;
      if (element?.id && /(?:Task|Event|Gateway|SubProcess)$/.test(element.type ?? '')) selection.current?.(element.id);
    });
    void viewer.importXML(xml).then(result => {
      if (!active) return;
      setProblem(importProblem(result.warnings));
      const canvas = viewer.get<CanvasApi>('canvas');
      canvas.zoom('fit-viewport');
      setNodes(viewer.get<RegistryApi>('elementRegistry').getAll()
        .filter((element: {type?: string}) => /(?:Task|Event|Gateway|SubProcess)$/.test(element.type ?? ''))
        .map((element: {id: string; businessObject?: {name?: string}}) => ({id: element.id, name: element.businessObject?.name || element.id})));
      if (viewport.current.fitActive && viewport.current.activeActivityIds.length === 1) {
        const registry = viewer.get<RegistryApi>('elementRegistry');
        const element = registry.get(viewport.current.activeActivityIds[0]);
        if (element) canvas.scrollToElement(element);
      }
      stopResize = observeDiagramViewport(containerRef.current!, () => {
        if (!active || viewerRef.current !== viewer) return;
        canvas.resized();
        canvas.zoom('fit-viewport');
        if (viewport.current.fitActive && viewport.current.activeActivityIds.length === 1) {
          const element = viewer.get<RegistryApi>('elementRegistry').get(viewport.current.activeActivityIds[0]);
          if (element) canvas.scrollToElement(element);
        }
      });
      setReady(true);
    }).catch(() => {
      if (active) {
        viewer.clear();
        setNodes([]);
        setProblem(importProblem([], true));
      }
    });
    // A new source owns a new viewer. A slow old import must never replace the new instance's canvas.
    return () => {active = false; stopResize?.(); viewer.destroy(); if (viewerRef.current === viewer) viewerRef.current = undefined;};
  }, [xml]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!ready || !viewer) return;
    const canvas = viewer.get<CanvasApi>('canvas'), registry = viewer.get<RegistryApi>('elementRegistry');
    registry.getAll().forEach((element: {id: string}) => {
      STATE_MARKERS.forEach(marker => canvas.removeMarker(element.id, marker));
      canvas.removeMarker(element.id, 'workflow-status-selected');
    });
    states.forEach((status, id) => {if (registry.get(id)) canvas.addMarker(id, markerClass(status));});
    if (selectedActivityId && registry.get(selectedActivityId)) canvas.addMarker(selectedActivityId, 'workflow-status-selected');
  }, [ready, states, selectedActivityId]);

  const canvas = () => viewerRef.current?.get<CanvasApi>('canvas');
  const zoom = (delta: number) => {
    const current = Number(canvas()?.zoom?.()) || 1;
    canvas()?.zoom?.(Math.max(0.2, Math.min(4, current + delta)));
  };
  const statusLabels: Record<string, string> = {
    ACTIVE: t('workflow.status.ACTIVE', '当前处理'), COMPLETED: t('workflow.status.COMPLETED', '已完成'),
    CANCELED: t('workflow.status.CANCELED', '取消'), WITHDRAWN: t('workflow.status.WITHDRAWN', '撤回'),
    REJECTED: t('workflow.status.REJECTED', '驳回'), FAILED: t('workflow.diagram.failed', '异常'),
  };
  const summary = activities.map(activity =>
    `${activity.activityName || activity.activityId}: ${statusLabels[activity.status] ?? activity.status}`).join('; ');

  return <figure className={`process-status-diagram ${className ?? ''}`} style={{height}}
    aria-busy={!ready && !problem} aria-label={`${t('workflow.diagram.title', '流程进度')}。${summary}`}>
    <div className="process-status-diagram__toolbar">
      <Space className="process-status-diagram__legend" size="middle" wrap>
        <span><i className="is-active"/>{labels.active}</span>
        <span><i className="is-completed"/>{labels.completed}</span>
        <span><i className="is-canceled"/>{labels.canceled}</span>
      </Space>
      <Space.Compact>
        <Tooltip title={labels.zoomOut}><Button aria-label={labels.zoomOut}
          icon={<ZoomOutOutlined/>} onClick={() => zoom(-0.1)}/></Tooltip>
        <Tooltip title={labels.zoomIn}><Button aria-label={labels.zoomIn}
          icon={<ZoomInOutlined/>} onClick={() => zoom(0.1)}/></Tooltip>
        <Tooltip title={labels.fit}><Button aria-label={labels.fit}
          icon={<CompressOutlined/>} onClick={() => canvas()?.zoom?.('fit-viewport')}/></Tooltip>
      </Space.Compact>
    </div>
    {problem && <ImportProblemNotice progress problem={problem} xml={xml}/>}
    <div ref={containerRef} className="process-status-diagram__canvas"/>
    {onSelectActivity && <nav className="process-status-diagram__nodes" aria-label={t('workflow.diagram.selectNode', '选择节点查看处理记录')}>
      {/* Native buttons are the keyboard equivalent of selecting a BPMN shape, not business actions. */}
      {nodes.map(node => <button key={node.id} type="button" aria-pressed={selectedActivityId === node.id}
        onClick={() => onSelectActivity(node.id)}>{node.name}</button>)}
    </nav>}
  </figure>;
}
