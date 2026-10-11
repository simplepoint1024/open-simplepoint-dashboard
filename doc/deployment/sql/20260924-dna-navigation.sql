-- Only replace the former built-in label; preserve tenant/user custom translations.
UPDATE simpoint_i18n_messages SET message = '仪表盘'
WHERE locale = 'zh-CN' AND code = 'menu.dna.dashboard' AND message = 'DNA 仪表盘';
UPDATE simpoint_i18n_messages SET message = 'Dashboard'
WHERE locale = 'en-US' AND code = 'menu.dna.dashboard' AND message = 'DNA Dashboard';
