import {timeClock, changedTime} from '../../FormComponents/Standard/timeValue';
import '../../FormComponents/Standard/style.css';
import type {WidgetProps} from '@rjsf/utils';
import {Cascader, Rate, Switch, TimePicker, Transfer} from 'antd';
import dayjs from 'dayjs';
import customParseFormat from 'dayjs/plugin/customParseFormat';
import type {FormValue} from '../../FormRuntime/contracts';
import {selectedKeys, selectionOptions} from '../../FormComponents/Standard/model';
dayjs.extend(customParseFormat);
const properties = (props: WidgetProps) => (props.options.componentProperties ?? {}) as Record<string, FormValue>;
/** Shared SForm adapters own typed serialization; business pages never instantiate these controls. */
export function SwitchInput(props: WidgetProps) {
  return <Switch id={props.id} aria-label={props.label} checked={props.value === true} disabled={props.disabled || props.readonly}
    autoFocus={props.autofocus} onChange={value => props.onChange(value)}/>;
}
export function RatingInput(props: WidgetProps) {
  return <Rate id={props.id} aria-label={props.label} count={Number(properties(props).count?.value ?? 5)} value={props.value ?? 0}
    disabled={props.disabled || props.readonly} onChange={props.onChange}/>;
}
export function TimeInput(props: WidgetProps) {
  const clock = timeClock(props.value);
  const time = clock ? dayjs(clock, 'HH:mm:ss', true) : null;
  return <TimePicker id={props.id} aria-label={props.label} value={time?.isValid() ? time : null} inputReadOnly format={value => changedTime(props.value, value.format('HH:mm:ss')) ?? ''}
    disabled={props.disabled || props.readonly} placeholder={props.placeholder} status={props.rawErrors?.length ? 'error' : undefined}
    style={{width: '100%'}} onChange={value => props.onChange(changedTime(props.value, value?.format('HH:mm:ss')))}/>;
}
export function CascaderInput(props: WidgetProps) {
  return <Cascader id={props.id} aria-label={props.label} options={selectionOptions(properties(props).options?.value ?? undefined)}
    value={selectedKeys(props.value)} disabled={props.disabled || props.readonly} showSearch allowClear
    style={{width: '100%'}} placeholder={props.placeholder} status={props.rawErrors?.length ? 'error' : undefined}
    onChange={value => props.onChange(value?.length ? JSON.stringify(value) : undefined)}/>;
}
export function TransferInput(props: WidgetProps) {
  const choices = selectionOptions(properties(props).options?.value ?? undefined);
  return <div id={props.id} aria-label={props.label} className="sp-form-transfer"><Transfer showSearch
    dataSource={choices.map(option => ({key: option.value, title: option.label}))} targetKeys={selectedKeys(props.value)}
    disabled={props.disabled || props.readonly} render={item => item.title} pagination={{pageSize: 10}}
    onChange={keys => props.onChange(JSON.stringify(keys))} styles={{section: {width: 'min(240px, 44%)', height: 280}}}/></div>;
}
