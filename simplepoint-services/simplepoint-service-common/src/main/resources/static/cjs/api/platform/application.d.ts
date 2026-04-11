import { Page } from '@simplepoint/shared/types/request';
export interface ApplicationRelevantVo {
    id: string;
    name: string;
    code: string;
    description: string;
}
export interface ApplicationFeaturesRelevanceDto {
    applicationCode: string | null;
    featureCodes?: string[];
}
export declare function fetchItems(params: Record<string, string>): Promise<Page<ApplicationRelevantVo>>;
export declare function fetchAuthorized(params: Pick<ApplicationFeaturesRelevanceDto, 'applicationCode'>): Promise<string[]>;
export declare function fetchAuthorize(data: ApplicationFeaturesRelevanceDto): Promise<ApplicationFeaturesRelevanceDto>;
export declare function fetchUnauthorized(data: ApplicationFeaturesRelevanceDto): Promise<ApplicationFeaturesRelevanceDto>;
