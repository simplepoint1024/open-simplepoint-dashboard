type Messages = Record<string, string>;

const wait = (milliseconds: number) => new Promise<void>(resolve => setTimeout(resolve, milliseconds));

/** Failed bundles must remain retryable instead of being cached as successfully loaded namespaces. */
export async function loadMessageBundle(
  load: () => Promise<unknown>,
  isTransient: (error: unknown) => boolean,
  pause: (milliseconds: number) => Promise<void> = wait,
): Promise<Messages> {
  const delays = [250, 750];
  for (let attempt = 0; ; attempt += 1) {
    try {
      const data = await load();
      if (!data || typeof data !== 'object' || Array.isArray(data)
        || Object.values(data).some(value => typeof value !== 'string')) {
        throw new Error('Invalid translation bundle');
      }
      return data as Messages;
    } catch (error) {
      if (attempt >= delays.length || !isTransient(error)) throw error;
      await pause(delays[attempt]);
    }
  }
}
