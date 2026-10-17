import {useEffect, useRef, useState, type RefObject} from 'react';
import {anchoredPage, cardGeometry} from './cardLayout';

/** Shares responsive page sizing while leaving query ownership in the existing table controller. */
export function useCardLayout(root: RefObject<HTMLDivElement | null>, mode: 'table' | 'cards',
  enabled: boolean, rows: number, page: number, size: number, total: number,
  change: (page: number, size: number) => void, maxPageSize?: number, minWidth?: number) {
  const [width, setWidth] = useState(0);
  const tableSize = useRef(size);
  const latest = useRef({page, size, total, change});
  latest.current = {page, size, total, change};
  const previous = useRef('table');
  const requested = useRef('');
  useEffect(() => {
    const element = root.current;
    if (!element || typeof ResizeObserver === 'undefined') return;
    let timer: ReturnType<typeof setTimeout>;
    const measure = () => {
      clearTimeout(timer);
      timer = setTimeout(() => setWidth(element.clientWidth), 150);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => {clearTimeout(timer); observer.disconnect();};
  }, [root]);
  const geometry = cardGeometry(width, rows, maxPageSize, minWidth);
  useEffect(() => {
    if (!enabled || width <= 0) return;
    const current = latest.current;
    if (mode === 'table' && previous.current === 'table') tableSize.current = current.size;
    const targetSize = mode === 'cards' ? geometry.pageSize : tableSize.current;
    const signature = `${mode}:${targetSize}`;
    if (signature === requested.current) return;
    requested.current = signature;
    previous.current = mode;
    if (targetSize !== current.size) {
      current.change(anchoredPage(current.page, current.size, targetSize, current.total), targetSize);
    }
  }, [enabled, mode, width, geometry.pageSize, size]);
  return geometry;
}
