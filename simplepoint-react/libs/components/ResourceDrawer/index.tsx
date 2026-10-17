import {Drawer, Tooltip} from 'antd';
import {createIcon} from '@simplepoint/shared/types/icon';
import {useCallback, useEffect, useRef, useState, type ReactNode, type MouseEvent, type KeyboardEvent} from 'react';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {clampDrawerHeight, drawerHeightBounds, clampDrawerWidth, drawerWidthBounds} from './height';

type Props = {
  open: boolean;
  title: ReactNode;
  children: ReactNode;
  rootClassName?: string;
  /** A nested form, confirmation, or write must finish/close before its owner can close. */
  locked?: boolean;
  onClose: () => void;
  placement?: 'bottom' | 'right';
  defaultWidth?: number;
  minWidth?: number;
  /** Collapsing inspectors keeps their size and active tab; resource dialogs retain their existing close lifecycle. */
  collapsible?: boolean;
  defaultHeight?: number;
  minHeight?: number;
  /** Preserve established full-height editors that own their internal padding. */
  bodyPadding?: number;
};
const viewportSize = (right: boolean) => typeof window === 'undefined' ? 900 : right ? window.innerWidth : window.innerHeight;

/** Shared configuration drawer: retained-page ownership, bounded resize and optional inspector collapse. */
export default function ResourceDrawer({open, title, children, rootClassName, locked = false, onClose,
  defaultHeight = 480, minHeight = 240, placement = 'bottom', defaultWidth = 880, minWidth = 320, collapsible = false, bodyPadding}: Props) {
  const selected = usePageSelected();
  const right = placement === 'right';
  const initialSize = right ? defaultWidth : defaultHeight, minimumSize = right ? minWidth : minHeight;
  const clamp = right ? clampDrawerWidth : clampDrawerHeight;
  const {t} = useI18n();
  const [viewport, setViewport] = useState(() => viewportSize(right));
  const [size, setSize] = useState(() => clamp(initialSize, viewportSize(right), minimumSize));
  const dragCleanup = useRef<(() => void) | undefined>(undefined);
  usePageRetention(!open && !locked, open || locked);
  useEffect(() => {
    if (!open || !selected) return;
    const resize = () => {setViewport(viewportSize(right)); setSize(previous => clamp(previous, viewportSize(right), minimumSize));};
    resize();
    window.addEventListener('resize', resize);
    return () => {window.removeEventListener('resize', resize); dragCleanup.current?.();};
  }, [open, selected, right, clamp, minimumSize]);
  useEffect(() => {
    if (!open && !collapsible) setSize(clamp(initialSize, viewportSize(right), minimumSize));
    if (!open || !selected || locked) dragCleanup.current?.();
  }, [open, selected, locked, initialSize, minimumSize, right, clamp, collapsible]);
  const startResize = useCallback((event: MouseEvent) => {
    if (!open || !selected || locked || event.button !== 0) return;
    event.preventDefault(); dragCleanup.current?.();
    const startPosition = right ? event.clientX : event.clientY, startSize = size;
    const move = (next: globalThis.MouseEvent) => setSize(clamp(startSize + startPosition - (right ? next.clientX : next.clientY), viewportSize(right), minimumSize));
    const cleanup = () => {
      window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', cleanup);
      if (dragCleanup.current === cleanup) dragCleanup.current = undefined;
    };
    dragCleanup.current = cleanup;
    window.addEventListener('mousemove', move); window.addEventListener('mouseup', cleanup);
  }, [open, selected, locked, size, right, clamp, minimumSize]);
  const bounds = (right ? drawerWidthBounds : drawerHeightBounds)(viewport, minimumSize);
  const resizeWithKeyboard = (event: KeyboardEvent) => {
    const increase = right ? 'ArrowLeft' : 'ArrowUp', decrease = right ? 'ArrowRight' : 'ArrowDown';
    if (!open || !selected || locked || ![increase, decrease, 'Home', 'End'].includes(event.key)) return;
    event.preventDefault(); event.stopPropagation();
    setSize(previous => clamp(event.key === 'Home' ? bounds.minimum : event.key === 'End' ? bounds.maximum
      : previous + (event.key === increase ? 24 : -24), viewport, minimumSize));
  };
  // A focusable separator owns geometry only; actions remain in the platform action renderer.
  const resizeHandle = (
    <div role="separator" aria-orientation={right ? 'vertical' : 'horizontal'} aria-label={right ? t('table.resizeConfigurationWidth', '调整配置面板宽度') : t('table.resizeConfiguration', '调整配置面板高度')}
      aria-valuemin={bounds.minimum} aria-valuemax={bounds.maximum} aria-valuenow={size} tabIndex={locked ? -1 : 0}
      style={{position: 'absolute', pointerEvents: locked ? 'none' : 'auto', top: 0, left: 0, ...(right ? {bottom: 0, width: 8, cursor: 'ew-resize'} : {right: 0, height: 8, cursor: 'ns-resize'}), zIndex: 10}}
      onMouseDown={startResize} onKeyDown={resizeWithKeyboard}/>
  );
  return <Drawer rootClassName={rootClassName} title={title} placement={placement} size={size}
    open={open && selected} destroyOnHidden={!collapsible && !open} closable={!locked} keyboard={!locked} mask={{closable: false}}
    closeIcon={collapsible ? <Tooltip title={t('table.collapseConfiguration', '收起面板')}>{createIcon(right ? 'RightOutlined' : 'DownOutlined')}</Tooltip> : undefined}
    onClose={() => {if (selected && !locked) onClose();}}
    drawerRender={panel => <>{resizeHandle}{panel}</>}
    styles={{body: {position: 'relative', paddingTop: 12, ...(bodyPadding === undefined ? {} : {padding: bodyPadding}), minHeight: 0, display: 'flex', flexDirection: 'column'}}}>
    {children}
  </Drawer>;
}
