import {useCallback, useEffect, useMemo, useState} from 'react';
import {Select} from 'antd';
import {ariaDescribedByIds, enumOptionSelectedValue, enumOptionValueDecoder, enumOptionValueEncoder,
  getOptionValueFormat, SelectedOptionDescription, type WidgetProps} from '@rjsf/utils';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {popupContainer, usePopupOwner} from './usePopupOwner';

/**
 * Platform enum widget: RJSF owns value encoding; the owning overlay owns popup placement.
 * The upstream widget mounts inside its field, which clips menus in scrolling tables/drawers.
 */
export default function SelectWidget(props: WidgetProps) {
  const {id, htmlName, autofocus, disabled, readonly, multiple, options, schema, value, placeholder,
    onChange, onBlur, onFocus, registry, uiSchema, rawErrors} = props;
  const selected = usePageSelected(), [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);
  const popupOwner = usePopupOwner(open, close);
  const enabled = selected && !disabled && !readonly;
  const {enumOptions, enumDisabled, emptyValue} = options;
  const format = getOptionValueFormat(options);
  useEffect(() => {if (!enabled) setOpen(false);}, [enabled]);
  const items = useMemo(() => {
    const rows = (enumOptions ?? []).map(({value: option, label}, index) => ({
      key: String(index), value: enumOptionValueEncoder(option, index, format), label,
      disabled: Array.isArray(enumDisabled) && enumDisabled.includes(option),
    }));
    if (!multiple && schema.default === undefined) return [{value: '', label: placeholder ?? ''}, ...rows];
    return rows;
  }, [enumOptions, enumDisabled, format, multiple, schema.default, placeholder]);
  return <div ref={popupOwner} style={{display: 'contents'}}>
    <Select id={id} {...{name: htmlName || id}} autoFocus={autofocus} style={{width: '100%'}}
      disabled={!enabled} mode={multiple ? 'multiple' : undefined} placeholder={placeholder}
      value={enumOptionSelectedValue(value, enumOptions, Boolean(multiple), format, emptyValue)} options={items}
      open={open && enabled} onOpenChange={next => setOpen(enabled && next)}
      getPopupContainer={popupContainer}
      status={rawErrors?.length ? 'error' : undefined} aria-describedby={ariaDescribedByIds(id)}
      showSearch={{filterOption: (input, option) => typeof option?.label === 'string' && option.label.toLocaleLowerCase().includes(input.toLocaleLowerCase())}}
      onChange={next => {if (enabled) {onChange(enumOptionValueDecoder(next, enumOptions, format, emptyValue)); if (!multiple) setOpen(false);}}}
      onBlur={() => {setOpen(false); if (enabled) onBlur(id, value);}} onFocus={() => {if (enabled) onFocus(id, value);}}/>
    <SelectedOptionDescription id={id} multiple={multiple} options={options} registry={registry} uiSchema={uiSchema} value={value}/>
  </div>;
}
