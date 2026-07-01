import * as fs from 'fs';
import * as path from 'path';

// 递归扫描 src/views，自动生成多级目录的 exposes 配置
const viewsDir = path.join(__dirname, 'src', 'views');
const exposes: Record<string, string> = {};

// 新增：支持多种入口文件扩展
const INDEX_ENTRY_FILES = ['index.tsx', 'index.ts', 'index.jsx', 'index.js'];

function findIndexFile(dir: string): string | null {
  for (const name of INDEX_ENTRY_FILES) {
    if (fs.existsSync(path.join(dir, name))) return name;
  }
  return null;
}

function scan(dir: string, parts: string[] = []) {
  // 只要当前目录存在入口文件，就将其作为一个可暴露的入口
  if (parts.length > 0) {
    const indexFile = findIndexFile(dir);
    if (indexFile) {
      const rel = parts.join('/');
      const key = `./${rel}`;
      exposes[key] = `./src/views/${rel}/${indexFile}`;
    }
  }

  const entries = fs.readdirSync(dir, {withFileTypes: true});
  for (const entry of entries) {
    if (entry.isDirectory()) {
      scan(path.join(dir, entry.name), [...parts, entry.name]);
    }
  }
}

// 健壮性：views 目录不存在时不扫描
if (fs.existsSync(viewsDir)) {
  scan(viewsDir);
}

export default exposes;
