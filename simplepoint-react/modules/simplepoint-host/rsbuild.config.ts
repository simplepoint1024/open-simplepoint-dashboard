import {pluginModuleFederation} from '@module-federation/rsbuild-plugin';
import {defineConfig} from '@rsbuild/core';
import {pluginReact} from '@rsbuild/plugin-react';

export default defineConfig({
  plugins: [
    pluginReact(),
    pluginModuleFederation({
      name: 'host',
      manifest: false,
      shared: require('@simplepoint/shared/types/module.shared').default,
      shareStrategy: 'loaded-first',
    }),
  ],
  output: {
    distPath: {
      root: './dist',
      js: 'assets/js',
      css: 'assets/css',
      assets: 'assets',
      svg: 'assets/svg',
      font: 'assets/fonts',
      image: 'assets/images',
      media: 'assets/media',
    },
  },
  server: {
    publicDir: {
      copyOnBuild: true,
    }
  }
});
