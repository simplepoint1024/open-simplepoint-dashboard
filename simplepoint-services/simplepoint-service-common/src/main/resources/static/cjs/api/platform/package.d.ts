import { Page } from '@simplepoint/shared/types/request';
export interface PackageRelevantVo {
    id: string;
    name: string;
    code: string;
    description: string;
}
export interface PackageApplicationsRelevanceDto {
    packageCode: string | null;
    applicationCodes?: string[];
}
export declare function fetchItems(params: Record<string, string>): Promise<Page<PackageRelevantVo>>;
export declare function fetchAuthorized(params: Pick<PackageApplicationsRelevanceDto, 'packageCode'>): Promise<string[]>;
export declare function fetchAuthorize(data: PackageApplicationsRelevanceDto): Promise<PackageApplicationsRelevanceDto>;
export declare function fetchUnauthorized(data: PackageApplicationsRelevanceDto): Promise<PackageApplicationsRelevanceDto>;
