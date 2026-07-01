import {pluginModuleFederation} from '@module-federation/rsbuild-plugin';
import {pluginReact} from '@rsbuild/plugin-react';
import {defineConfig} from '@rslib/core';
import '@ant-design/v5-patch-for-react-19';

export default defineConfig({
  server: {
    base: '/dna/mf',
    port: 3003,
  },
  source: {
    tsconfigPath: './tsconfig.json',
  },
  plugins: [pluginReact()],
  lib: [
    {
      format: 'mf',
      output: {
        distPath: {
          root: './dist/mf',
        },
      },
      plugins: [
        pluginModuleFederation({
          name: 'dna',
          exposes: require('./module.exposes').default,
          shared: require('@simplepoint/shared/types/module.shared').default,
        }),
      ],
    },
    {
      format: 'esm',
      dts: true,
      output: {
        distPath: {
          root: './dist/esm',
        },
      },
    },
    {
      format: 'cjs',
      dts: true,
      output: {
        distPath: {
          root: './dist/cjs',
        },
      },
    },
  ]
});
