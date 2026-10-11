import {CompressOutlined, ZoomInOutlined, ZoomOutOutlined} from '@ant-design/icons';
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer';
import {Alert, Button, Space, Tooltip} from 'antd';
import {useEffect, useMemo, useRef, useState} from 'react';
import flowableModdle from '../WorkflowDesigner/flowableModdle';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
import './style.css';
import {activityMarkers, markerClass, type ProcessActivityState} from './state';
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
    active: '当前节点', completed: '已完成', canceled: '已取消/异常',
    fit: '适配画布', zoomIn: '放大', zoomOut: '缩小', ...labelOverrides,
  }), [labelOverrides]);
  const containerRef = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<InstanceType<typeof NavigatedViewer> | undefined>(undefined);
  const [error, setError] = useState<string>();
  const [ready, setReady] = useState(false);
  const [nodes, setNodes] = useState<Array<{id: string; name: string}>>([]);
  const selection = useRef(onSelectActivity);
  selection.current = onSelectActivity;
  const viewport = useRef({fitActive, activeActivityIds});
  viewport.current = {fitActive, activeActivityIds};

  const states = useMemo(() => activityMarkers(activities, activeActivityIds), [activeActivityIds, activities]);

  useEffect(() => {
    if (!containerRef.current || !xml) return;
    let active = true;
    setReady(false); setError(undefined); setNodes([]);
    const viewer = new NavigatedViewer({
      container: containerRef.current,
      moddleExtensions: {flowable: flowableModdle},
    });
    viewerRef.current = viewer;
    viewer.on('element.click', (event: {element?: {id?: string; type?: string}}) => {
      const element = event.element;
      if (element?.id && /(?:Task|Event|Gateway|SubProcess)$/.test(element.type ?? '')) selection.current?.(element.id);
    });
    void viewer.importXML(xml).then(() => {
      if (!active) return;
      setError(undefined);
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
      setReady(true);
    }).catch((reason: unknown) => {
      if (active) setError(reason instanceof Error ? reason.message : '流程图加载失败');
    });
    // A new source owns a new viewer. A slow old import must never replace the new instance's canvas.
    return () => {active = false; viewer.destroy(); if (viewerRef.current === viewer) viewerRef.current = undefined;};
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
  const summary = activities.map(activity =>
    `${activity.activityName || activity.activityId}: ${activity.status}`).join('; ');

  return <figure className={`process-status-diagram ${className ?? ''}`} style={{height}}
    aria-busy={!ready && !error} aria-label={`流程状态图。${summary}`}>
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
    {error && <Alert className="process-status-diagram__error" type="error" showIcon message={error}/>}
    <div ref={containerRef} className="process-status-diagram__canvas"/>
    {onSelectActivity && <nav className="process-status-diagram__nodes" aria-label={t('workflow.diagram.selectNode', '选择节点查看处理记录')}>
      {/* Native buttons are the keyboard equivalent of selecting a BPMN shape, not business actions. */}
      {nodes.map(node => <button key={node.id} type="button" aria-pressed={selectedActivityId === node.id}
        onClick={() => onSelectActivity(node.id)}>{node.name}</button>)}
    </nav>}
  </figure>;
}
