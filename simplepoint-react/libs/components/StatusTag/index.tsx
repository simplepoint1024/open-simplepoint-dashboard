import {Tag} from 'antd';
export type StatusTone = 'neutral' | 'success' | 'error' | 'warning' | 'processing';
export type StatusPresentation = {label: string; tone: StatusTone};
/** Domains own labels and transitions; platform owns visual meaning. Unknown values stay neutral. */
export default function StatusTag({label, tone}: StatusPresentation) {
  return <Tag color={tone === 'neutral' ? 'default' : tone}>{label}</Tag>;
}
