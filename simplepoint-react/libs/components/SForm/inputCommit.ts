/** Non-native editors opt in when their callbacks run outside React's event boundary. */
export function commitFormInput(update: () => void, writable: boolean, flush: (update: () => void) => void): boolean {
  if (!writable) return false;
  flush(update);
  return true;
}
