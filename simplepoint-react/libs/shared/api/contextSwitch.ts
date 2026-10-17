/** Validate the destination before publishing a scope change; abandoned/stale intent never commits. */
export async function switchAuthorizedContext({confirm, current, authorize, commit, unavailable}: {
  confirm: () => Promise<boolean>;
  current: () => boolean;
  authorize: () => Promise<string | undefined>;
  commit: (contextId: string) => void;
  unavailable: string;
}): Promise<boolean> {
  if (!current() || !await confirm() || !current()) return false;
  const contextId = await authorize();
  if (!current()) return false;
  if (!contextId) throw new Error(unavailable);
  commit(contextId);
  return true;
}
