export function tablePreferenceIdentity(resource: string | undefined, route: string, columns: string) {
  return resource ?? `auto.${JSON.stringify([route, columns])}`;
}
