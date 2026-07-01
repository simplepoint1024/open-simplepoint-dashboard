import {ButtonEventProps} from "./ButtonEvents.tsx";

/**
 * 按钮禁用规则
 * 根据选中行数来判断按钮是否禁用
 * 根据key值来判断
 */
export const ButtonDisabledRules= {
  add: <T,>(props: ButtonEventProps<T>):boolean=> props.selectedRowKeys.length > 0,
  edit: <T,>(props: ButtonEventProps<T>):boolean=> props.selectedRowKeys.length != 1,
  del: <T,>(props: ButtonEventProps<T>):boolean=> props.selectedRowKeys.length === 0,
}