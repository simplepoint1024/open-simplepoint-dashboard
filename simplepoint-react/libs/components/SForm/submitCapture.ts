type SubmitEvent = {target: unknown; nativeEvent: {bubbles: boolean}; preventDefault: () => void; stopPropagation: () => void};

/** Guard the genuine form event before RJSF queues validation/submit callbacks. */
export function guardFormSubmit(event: SubmitEvent, form: unknown, available: boolean, begin: () => boolean | void) {
  // RJSF submit() first dispatches a non-bubbling, pre-cancelled probe, then requestSubmit().
  // Its probe is not a submission and must not acquire a lease or block the subsequent real event.
  if (!event.nativeEvent.bubbles || event.target !== form) return 'ignored';
  if (available && begin() !== false) return 'accepted';
  event.preventDefault(); event.stopPropagation(); return 'blocked';
}
