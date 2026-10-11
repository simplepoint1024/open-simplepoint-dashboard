/** Native engine widget preparations own independent synchronous tokens; old releases cannot clear new work. */
export function beginPreparation(pending: Set<symbol>, allowed: boolean, changed: (busy: boolean) => void) {
  if (!allowed) return undefined;
  const token = Symbol(); pending.add(token); changed(true);
  return () => {if (pending.delete(token)) changed(pending.size > 0);};
}
