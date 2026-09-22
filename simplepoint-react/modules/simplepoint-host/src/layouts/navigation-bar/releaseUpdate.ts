/** Only the immutable host entry identifies a build; login/error HTML must not count. */
export function hostEntry(sources: readonly string[]): string | undefined {
  return sources.map(source => {
    try { return new URL(source, 'https://local.invalid').pathname; } catch { return ''; }
  }).find(source => /^\/assets\/js\/(?:index|main)\.[a-f0-9]{8,}\.js$/i.test(source));
}
