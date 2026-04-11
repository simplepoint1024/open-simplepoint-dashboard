import { Page } from '@simplepoint/shared/types/request';
export interface FeatureRelevantVo {
    id: string;
    name: string;
    code: string;
    description: string;
}
export interface FeaturePermissionsRelevanceDto {
    featureCode: string | null;
    permissionAuthority?: string[];
}
export declare function fetchItems(params: Record<string, string>): Promise<Page<FeatureRelevantVo>>;
export declare function fetchSelectedItems(codes: string[]): Promise<FeatureRelevantVo[]>;
export declare function fetchAuthorized(params: Pick<FeaturePermissionsRelevanceDto, 'featureCode'>): Promise<string[]>;
export declare function fetchAuthorize(data: FeaturePermissionsRelevanceDto): Promise<FeaturePermissionsRelevanceDto>;
export declare function fetchUnauthorized(data: FeaturePermissionsRelevanceDto): Promise<FeaturePermissionsRelevanceDto>;
