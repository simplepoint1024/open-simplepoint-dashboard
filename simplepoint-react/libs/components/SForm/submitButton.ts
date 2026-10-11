import {getUiOptions, type UiSchema} from '@rjsf/utils';

/** RJSF normalizes root submit options into ui:options before calling button templates. */
export function submitButtonState(uiSchema: UiSchema | undefined,
  context: {submitLoading?: boolean; submitDisabled?: boolean} | undefined, fallback: string) {
  const options = getUiOptions(uiSchema).submitButtonOptions ?? {};
  return {
    text: options.submitText || fallback,
    hidden: Boolean(options.norender),
    loading: Boolean(context?.submitLoading),
    disabled: Boolean(options.props?.disabled || context?.submitDisabled || context?.submitLoading),
  };
}
