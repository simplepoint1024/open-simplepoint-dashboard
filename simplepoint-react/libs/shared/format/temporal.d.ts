export type TemporalKind = 'date' | 'time' | 'datetime';
type Options = {
    locale?: string;
    kind?: TemporalKind;
    timeZone?: string;
    empty?: string;
};
/** Dates and civil times never shift zones. Only offset-bearing instants use the viewer's zone. */
export declare function formatTemporal(value: unknown, { locale, kind, timeZone, empty }?: Options): string;
export declare const formatDateTime: (value: unknown, locale?: string) => string;
export {};
