import {CompressOutlined, ZoomInOutlined, ZoomOutOutlined} from '@ant-design/icons';
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer';
import {Alert, Button, Space, Tooltip} from 'antd';
import {useEffect, useMemo, useRef, useState} from 'react';
import flowableModdle from '../WorkflowDesigner/flowableModdle';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
import './style.css';

export type ProcessActivityState = {
  activityId: string;
  activityName?: string;
  activityType?: string;
  status: 'ACTIVE' | 'COMPLETED' | 'CANCELED' | 'FAILED' | string;
  startedAt?: string;
  endedAt?: string;
  assignee?: string;
};

export type ProcessStatusDiagramProps = {
  xml: string;
  activities: ProcessActivityState[];
  activeActivityIds?: string[];
  height?: number | string;
  className?: string;
  fitActive?: boolean;
  labels?: Partial<{active: string; completed: string; canceled: string; fit: string; zoomIn: string; zoomOut: string}>;
};

const statusPriority: Record<string, number> = {COMPLETED: 1, CANCELED: 2, FAILED: 3, ACTIVE: 4};

/** Read-only reusable BPMN execution-state viewer. */
export default function ProcessStatusDiagram({
  xml,
  activities,
  activeActivityIds = [],
  height = 520,
  className,
  fitActive = false,
  labels: labelOverrides,
}: ProcessStatusDiagramProps) {
  const labels = useMemo(() => ({
    active: '当前节点', completed: '已完成', canceled: '已取消/异常',
    fit: '适配画布', zoomIn: '放大', zoomOut: '缩小', ...labelOverrides,
  }), [labelOverrides]);
  const containerRef = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<any>(undefined);
  const [error, setError] = useState<string>();

  const states = useMemo(() => {
    const merged = new Map<string, string>();
    activities.forEach(activity => {
      const current = merged.get(activity.activityId);
      if (!current || (statusPriority[activity.status] ?? 0) >= (statusPriority[current] ?? 0)) {
        merged.set(activity.activityId, activity.status);
      }
    });
    activeActivityIds.forEach(id => merged.set(id, 'ACTIVE'));
    return merged;
  }, [activeActivityIds, activities]);

  useEffect(() => {
    if (!containerRef.current) return;
    const viewer = new NavigatedViewer({
      container: containerRef.current,
      moddleExtensions: {flowable: flowableModdle},
    });
    viewerRef.current = viewer;
    return () => {
      viewer.destroy();
      viewerRef.current = undefined;
    };
  }, []);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || !xml) return;
    let active = true;
    void viewer.importXML(xml).then(() => {
      if (!active) return;
      setError(undefined);
      const canvas = viewer.get('canvas');
      states.forEach((status, id) => {
        const marker = status === 'ACTIVE' ? 'workflow-status-active'
          : status === 'COMPLETED' ? 'workflow-status-completed'
            : status === 'FAILED' ? 'workflow-status-failed' : 'workflow-status-canceled';
        try { canvas.addMarker(id, marker); } catch { /* Historic model versions can omit an activity. */ }
      });
      canvas.zoom('fit-viewport');
      if (fitActive && activeActivityIds.length === 1) {
        const registry = viewer.get('elementRegistry');
        const element = registry.get(activeActivityIds[0]);
        if (element) canvas.scrollToElement(element);
      }
    }).catch((reason: unknown) => {
      if (active) setError(reason instanceof Error ? reason.message : '流程图加载失败');
    });
    return () => { active = false; };
  }, [activeActivityIds, fitActive, states, xml]);

  const canvas = () => viewerRef.current?.get('canvas');
  const zoom = (delta: number) => {
    const current = Number(canvas()?.zoom?.()) || 1;
    canvas()?.zoom?.(Math.max(0.2, Math.min(4, current + delta)));
  };
  const summary = activities.map(activity =>
    `${activity.activityName || activity.activityId}: ${activity.status}`).join('; ');

  return <figure className={`process-status-diagram ${className ?? ''}`} style={{height}}
    aria-label={`流程状态图。${summary}`}>
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
  </figure>;
}
