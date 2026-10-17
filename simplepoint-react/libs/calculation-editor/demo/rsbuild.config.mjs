import {defineConfig} from '@rsbuild/core';
import {pluginReact} from '@rsbuild/plugin-react';
import {fileURLToPath} from 'node:url';
export default defineConfig({
  plugins: [pluginReact()],
  source: {entry: {index: fileURLToPath(new URL('./main.tsx', import.meta.url))}},
  output: {distPath: {root: fileURLToPath(new URL('./dist', import.meta.url))}},
  html: {title: 'Standalone calculation editor'},
});
