/** Platform list paging. Directory lookups and protocol cursors have their own bounded contracts. */
export const TABLE_PAGE_SIZES = [50, 100, 200, 500] as const;
export const DEFAULT_TABLE_PAGE_SIZE: number = TABLE_PAGE_SIZES[0];
