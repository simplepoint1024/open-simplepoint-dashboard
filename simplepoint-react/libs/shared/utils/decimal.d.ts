export type ExactDecimal = {
    coefficient: bigint;
    scale: number;
};
/** Bounded decimal arithmetic for transport/form/query values, never IEEE-754 coercion. */
export declare function exactDecimal(value: unknown): ExactDecimal;
export declare function compareDecimals(a: ExactDecimal, b: ExactDecimal): number;
