import {Drawer} from 'antd';
import {useCallback, useEffect, useRef, useState, type ReactNode, type MouseEvent, type KeyboardEvent} from 'react';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {clampDrawerHeight, drawerHeightBounds} from './height';

type Props = {
  open: boolean;
  title: ReactNode;
  children: ReactNode;
  rootClassName?: string;
  /** A nested form, confirmation, or write must finish/close before its owner can close. */
  locked?: boolean;
  onClose: () => void;
  defaultHeight?: number;
  minHeight?: number;
  /** Preserve established full-height editors that own their internal padding. */
  bodyPadding?: number;
};
const viewportHeight = () => typeof window === 'undefined' ? 900 : window.innerHeight;

/** Common's existing bottom configuration drawer, with one retained-page/resize lifecycle. */
export default function ResourceDrawer({open, title, children, rootClassName, locked = false, onClose,
  defaultHeight = 480, minHeight = 240, bodyPadding}: Props) {
  const selected = usePageSelected();
  const {t} = useI18n();
  const [viewport, setViewport] = useState(viewportHeight);
  const [height, setHeight] = useState(() => clampDrawerHeight(defaultHeight, viewportHeight(), minHeight));
  const dragCleanup = useRef<(() => void) | undefined>(undefined);
  usePageRetention(!open && !locked, open || locked);
  useEffect(() => {
    if (!open || !selected) return;
    const resize = () => {setViewport(viewportHeight()); setHeight(previous => clampDrawerHeight(previous, viewportHeight(), minHeight));};
    resize();
    window.addEventListener('resize', resize);
    return () => {window.removeEventListener('resize', resize); dragCleanup.current?.();};
  }, [open, selected, minHeight]);
  useEffect(() => {
    if (!open) setHeight(clampDrawerHeight(defaultHeight, viewportHeight(), minHeight));
    if (!open || !selected || locked) dragCleanup.current?.();
  }, [open, selected, locked, defaultHeight, minHeight]);
  const startResize = useCallback((event: MouseEvent) => {
    if (!open || !selected || locked) return;
    event.preventDefault(); dragCleanup.current?.();
    const startY = event.clientY, startHeight = height;
    const move = (next: globalThis.MouseEvent) => setHeight(clampDrawerHeight(startHeight + startY - next.clientY, viewportHeight(), minHeight));
    const cleanup = () => {
      window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', cleanup);
      if (dragCleanup.current === cleanup) dragCleanup.current = undefined;
    };
    dragCleanup.current = cleanup;
    window.addEventListener('mousemove', move); window.addEventListener('mouseup', cleanup);
  }, [open, selected, locked, height, minHeight]);
  const bounds = drawerHeightBounds(viewport, minHeight);
  const resizeWithKeyboard = (event: KeyboardEvent) => {
    if (!open || !selected || locked || !['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault(); event.stopPropagation();
    setHeight(previous => clampDrawerHeight(event.key === 'Home' ? bounds.minimum : event.key === 'End' ? bounds.maximum
      : previous + (event.key === 'ArrowUp' ? 24 : -24), viewport, minHeight));
  };
  return <Drawer rootClassName={rootClassName} title={title} placement="bottom" height={height}
    open={open && selected} destroyOnHidden={!open} closable={!locked} keyboard={!locked} maskClosable={false}
    onClose={() => {if (selected && !locked) onClose();}}
    styles={{body: {position: 'relative', paddingTop: 12, ...(bodyPadding === undefined ? {} : {padding: bodyPadding}), minHeight: 0, display: 'flex', flexDirection: 'column'}}}>
    {/* This specialized resize separator is not a second action toolbar or form control. */}
    <div role="separator" aria-orientation="horizontal" aria-label={t('table.resizeConfiguration', '调整配置面板高度')}
      aria-valuemin={bounds.minimum} aria-valuemax={bounds.maximum} aria-valuenow={height} tabIndex={locked ? -1 : 0}
      style={{position: 'absolute', top: 0, left: 0, right: 0, height: 8, cursor: 'ns-resize', zIndex: 10}}
      onMouseDown={startResize} onKeyDown={resizeWithKeyboard}/>
    {children}
  </Drawer>;
}
