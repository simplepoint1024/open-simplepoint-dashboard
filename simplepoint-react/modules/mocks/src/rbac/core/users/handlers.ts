import {http, HttpResponse} from 'msw';

// ─── 用户 mock 数据生成 ───────────────────────────────────────────────────────

const FAMILY_NAMES = ['王', '李', '张', '刘', '陈', '杨', '赵', '黄', '周', '吴',
    '徐', '孙', '胡', '朱', '高', '林', '何', '郭', '马', '罗'];
const GIVEN_NAMES = ['伟', '芳', '娜', '秀英', '敏', '静', '丽', '强', '磊', '军',
    '洋', '勇', '艳', '杰', '涛', '明', '超', '秀兰', '霞', '平',
    '建国', '志强', '丹', '玲', '亮', '刚', '健', '燕', '桂英', '凯',
    '文', '彬', '浩', '宇', '晨', '辉', '鹏', '思远', '佳明', '雪'];
const NICKNAMES = ['极客达人', '代码狂人', '云端行者', '数字游侠', '技术大拿', '键盘侠',
    '全栈战士', '架构师兄', '晨曦', '星河', '破晓', '夜雨', '青衫', '白鹿',
    '追风者', '独行客', '低调的人', '简单快乐', '咖啡续命', '代码诗人'];
const LOCALES = ['zh_CN', 'zh_TW', 'en_US', 'ja_JP', 'ko_KR'];
const ZONES = ['Asia/Shanghai', 'Asia/Taipei', 'America/New_York', 'Europe/London', 'Asia/Tokyo'];

type MockUser = Record<string, any>;

function snowflakeId(index: number): string {
    return String(BigInt('1941503407424671744') + BigInt(index));
}

function isoDate(daysAgo: number): string {
    const d = new Date('2025-10-01T00:00:00Z');
    d.setDate(d.getDate() - daysAgo);
    return d.toISOString().replace('Z', 'Z');
}

function phone(i: number): string {
    const prefix = ['138', '139', '150', '151', '158', '182', '186', '188', '176', '177'];
    return `${prefix[i % prefix.length]}${String(10000000 + i).padStart(8, '0')}`;
}

const ALL_USERS: MockUser[] = (() => {
    const users: MockUser[] = [
        {
            id: '1941503407424671744',
            createdBy: null, updatedBy: null, createdAt: null,
            updatedAt: '2025-07-05T14:24:18.395521Z',
            username: 'system', email: 'system@simplepoint.org',
            address: null, birthdate: null, emailVerified: true,
            familyName: null, gender: null, givenName: null,
            locale: 'zh_CN', middleName: 'System', name: '系统管理员',
            nickname: '系统账户', twoFactorEnabled: true, picture: null,
            phoneNumber: '18288888888', phoneNumberVerified: true,
            preferredUsername: 'system', profile: null,
            website: 'http://127.0.0.1', zoneinfo: 'Asia/Shanghai',
            enabled: true, accountNonExpired: true, accountNonLocked: true,
            credentialsNonExpired: true, superAdmin: true, authorities: null,
        },
    ];
    for (let i = 1; i < 100; i++) {
        const fi = i % FAMILY_NAMES.length;
        const gi = (i * 3) % GIVEN_NAMES.length;
        const familyName = FAMILY_NAMES[fi];
        const givenName = GIVEN_NAMES[gi];
        const name = `${familyName}${givenName}`;
        const username = `user${String(i).padStart(3, '0')}`;
        const localeIdx = i % LOCALES.length;
        users.push({
            id: snowflakeId(i),
            createdBy: 'system',
            updatedBy: i % 5 === 0 ? 'system' : null,
            createdAt: isoDate(200 - i),
            updatedAt: isoDate(100 - (i % 90)),
            username,
            email: `${username}@example.com`,
            address: i % 7 === 0 ? `北京市朝阳区建国路${90 + i}号` : null,
            birthdate: i % 3 === 0 ? `${1985 + (i % 20)}-${String((i % 12) + 1).padStart(2, '0')}-${String((i % 28) + 1).padStart(2, '0')}` : null,
            emailVerified: i % 4 !== 0,
            familyName,
            gender: i % 3 === 0 ? 'male' : i % 3 === 1 ? 'female' : null,
            givenName,
            locale: LOCALES[localeIdx],
            middleName: null,
            name,
            nickname: NICKNAMES[i % NICKNAMES.length],
            twoFactorEnabled: i % 10 === 0,
            picture: null,
            phoneNumber: i % 6 === 0 ? null : phone(i),
            phoneNumberVerified: i % 6 !== 0,
            preferredUsername: username,
            profile: i % 8 === 0 ? `${name}，${['后端工程师', '前端工程师', '全栈工程师', '架构师', '产品经理', 'DevOps'][i % 6]}` : null,
            website: null,
            zoneinfo: ZONES[localeIdx],
            enabled: i % 15 !== 0,
            accountNonExpired: true,
            accountNonLocked: i % 20 !== 0,
            credentialsNonExpired: true,
            superAdmin: false,
            authorities: null,
        });
    }
    return users;
})();

const ROLE_CANDIDATES = [
    {id: '1', name: '超级管理员', description: '拥有系统内所有权限'},
    {id: '2', name: '用户', description: '普通用户'},
];

function buildPage<T>(request: Request, records: T[], matcher: (item: T, keyword: string) => boolean) {
    const url = new URL(request.url);
    const page = Math.max(0, parseInt(url.searchParams.get('page') ?? '0', 10));
    const size = Math.max(1, parseInt(url.searchParams.get('size') ?? '20', 10));
    const keyword = (url.searchParams.get('keyword') ?? '').toLowerCase();
    const result = keyword ? records.filter((item) => matcher(item, keyword)) : [...records];
    const totalElements = result.length;
    const totalPages = Math.ceil(totalElements / size);
    return {
        content: result.slice(page * size, page * size + size),
        page: {size, number: page, totalElements, totalPages},
    };
}

export default [
    http.get('/common/users/schema', () => {
        return HttpResponse.json(
            {
                "buttons": [{
                    "path": "[default]",
                    "color": "blue",
                    "variant": "outlined",
                    "icon": "PlusCircleOutlined",
                    "argumentMaxSize": 0,
                    "sort": 0,
                    "type": "primary",
                    "danger": false,
                    "title": "添加",
                    "argumentMinSize": 0,
                    "key": "add"
                }, {
                    "path": "[default]",
                    "color": "danger",
                    "variant": "outlined",
                    "icon": "MinusCircleOutlined",
                    "argumentMaxSize": 10,
                    "sort": 2,
                    "type": "primary",
                    "danger": true,
                    "title": "删除",
                    "argumentMinSize": 1,
                    "key": "delete"
                }, {
                    "path": "[default]",
                    "color": "orange",
                    "variant": "outlined",
                    "icon": "EditOutlined",
                    "argumentMaxSize": 1,
                    "sort": 1,
                    "type": "primary",
                    "danger": false,
                    "title": "编辑",
                    "argumentMinSize": 1,
                    "key": "edit"
                }, {
                    "path": "[default]",
                    "color": "orange",
                    "variant": "outlined",
                    "icon": "SafetyOutlined",
                    "argumentMaxSize": 1,
                    "sort": 2,
                    "type": "primary",
                    "title": "i18n:users.button.config.role",
                    "danger": false,
                    "argumentMinSize": 1,
                    "key": "config.role"
                }
                ], "schema": {
                    "$schema": "http://json-schema.org/draft-07/schema#", "type": "object", "properties": {
                        "address": {
                            "type": ["string", "null"],
                            "title": "i18n:users.title.address",
                            "description": "i18n:users.description.address",
                            "minLength": 5,
                            "maxLength": 255
                        },
                        "birthdate": {
                            "type": ["string", "null"],
                            "title": "i18n:users.title.birthdate",
                            "description": "i18n:users.description.birthdate",
                            "format": "date-time"
                        },
                        "email": {
                            "type": ["string", "null"],
                            "title": "i18n:users.title.email",
                            "description": "i18n:users.description.email",
                            "minLength": 5,
                            "maxLength": 64,
                            "format": "email",
                            "x-ui": {"x-list-visible": "true"}
                        },
                        "enabled": {
                            "type": "boolean",
                            "title": "i18n:users.title.enabled",
                            "description": "i18n:users.description.enabled",
                            "readOnly": true,
                            "x-ui": {"x-list-visible": "true"}
                        },
                        "familyName": {
                            "type": ["string", "null"],
                            "title": "i18n:users.title.familyName",
                            "description": "i18n:users.description.familyName",
                            "minLength": 1,
                            "maxLength": 50
                        },
                        "givenName": {
                            "type": ["string", "null"],
                            "title": "i18n:users.title.givenName",
                            "description": "i18n:users.description.givenName",
                            "minLength": 1,
                            "maxLength": 50
                        },
                        "middleName": {
                            "type": ["string", "null"],
                            "title": "i18n:users.title.middleName",
                            "description": "i18n:users.description.middleName",
                            "minLength": 1,
                            "maxLength": 50
                        },
                        "nickname": {
                            "type": ["string", "null"],
                            "title": "i18n:users.title.nickname",
                            "description": "i18n:users.description.nickname",
                            "minLength": 1,
                            "maxLength": 50,
                            "x-order": 2,
                            "x-ui": {"x-list-visible": "true"}
                        },
                        "password": {
                            "type": ["string", "null"],
                            "title": "i18n:users.title.password",
                            "description": "i18n:users.description.username",
                            "x-order": 2,
                            "x-ui": {"widget": "password"}
                        },
                        "phoneNumber": {
                            "type": ["string", "null"],
                            "title": "i18n:users.title.phoneNumber",
                            "description": "i18n:users.description.phoneNumber",
                            "minLength": 5,
                            "maxLength": 50,
                            "x-order": 3,
                            "x-ui": {"x-list-visible": "true"}
                        },
                        "picture": {
                            "type": ["string", "null"],
                            "title": "i18n:users.title.picture",
                            "description": "i18n:users.description.picture",
                            "format": "data-url"
                        },
                        "profile": {
                            "type": ["string", "null"],
                            "title": "i18n:users.title.profile",
                            "description": "i18n:users.description.profile"
                        },
                        "superAdmin": {
                            "type": "boolean",
                            "title": "i18n:users.title.superAdmin",
                            "description": "i18n:users.description.superAdmin",
                            "x-ui": {"x-list-visible": "true"}
                        },
                        "username": {
                            "type": ["string", "null"],
                            "title": "i18n:users.title.username",
                            "description": "i18n:users.description.username",
                            "x-order": 1,
                            "x-ui": {"x-list-visible": "true"}
                        }
                    }
                }
            }
        )
    }),
    http.get('/userinfo', () => {
        return HttpResponse.json(
            {
                "sub": "system",
                "zoneinfo": null,
                "address": null,
                "gender": null,
                "roles": [
                    "SYSTEM_ADMIN"
                ],
                "iss": "http://127.0.0.1:9000",
                "locale": "zh_CN",
                "middle_name": "System",
                "given_name": "管理员",
                "nonce": "hzmoVmvL15YR9w_QRpGf0iQKYhqT8N2ArMsY-vxejnU",
                "picture": null,
                "sid": "xGZqVY3VZiWZ_36TTXezOW0Ricez8jpCApiNC5F03BU",
                "aud": [
                    "simplepoint-client"
                ],
                "phone": "18288888888",
                "super_admin": true,
                "azp": "simplepoint-client",
                "auth_time": "2025-10-30T12:55:14Z",
                "name": "管理员",
                "nickname": "系统用户",
                "twoFactorEnabled": true,
                "exp": "2025-10-30T13:43:59Z",
                "iat": "2025-10-30T13:13:59Z",
                "email": "xxxx@gmail.com",
                "jti": "17d8b4f6-898d-4045-a9c4-adc44c93074a"
            }
        )
    }),
    http.get('/common/users', ({request}) => {
        const url = new URL(request.url);
        const page = Math.max(0, parseInt(url.searchParams.get('page') ?? '0', 10));
        const size = Math.max(1, parseInt(url.searchParams.get('size') ?? '20', 10));
        const keyword = (url.searchParams.get('keyword') ?? '').toLowerCase();
        const sortParam = url.searchParams.get('sort') ?? '';
        const sortIdx = sortParam.lastIndexOf(',');
        const sortField = sortIdx === -1 ? (sortParam || undefined) : sortParam.slice(0, sortIdx);
        const sortDir = sortIdx === -1 ? 'asc' : sortParam.slice(sortIdx + 1);

        let result = keyword
            ? ALL_USERS.filter(u =>
                u.username.includes(keyword) ||
                (u.name ?? '').includes(keyword) ||
                (u.nickname ?? '').includes(keyword) ||
                (u.email ?? '').includes(keyword))
            : [...ALL_USERS];

        if (sortField) {
            result.sort((a: any, b: any) => {
                const av = a[sortField] ?? '';
                const bv = b[sortField] ?? '';
                const cmp = String(av).localeCompare(String(bv), 'zh-CN', {numeric: true, sensitivity: 'base'});
                return sortDir === 'desc' ? -cmp : cmp;
            });
        }

        const totalElements = result.length;
        const totalPages = Math.ceil(totalElements / size);
        const content = result.slice(page * size, page * size + size);
        return HttpResponse.json({
            content,
            page: {size, number: page, totalElements, totalPages},
        });
    }),
    http.get('/common/users/role-candidates', ({request}) => {
        return HttpResponse.json(buildPage(request, ROLE_CANDIDATES, (role, keyword) =>
            role.name.toLowerCase().includes(keyword) ||
            role.description.toLowerCase().includes(keyword) ||
            role.id.includes(keyword)
        ));
    }),
    http.get('/common/users/authorized', () => {
        return HttpResponse.json(["1"])
    }),
    http.post('/common/users/change-password', async ({ request }) => {
        const body = await request.json() as any;
        if (!body?.currentPassword || !body?.newPassword || !body?.confirmPassword) {
            return HttpResponse.json({ message: '参数不完整' }, { status: 400 });
        }
        if (body.newPassword !== body.confirmPassword) {
            return HttpResponse.json({ message: '新密码与确认密码不一致' }, { status: 400 });
        }
        if (body.currentPassword === 'wrong') {
            return HttpResponse.json({ message: '当前密码不正确' }, { status: 403 });
        }
        return HttpResponse.json(null, { status: 200 });
    }),
];
