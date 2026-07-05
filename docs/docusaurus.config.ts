import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'MQTT',
  tagline: 'Kotlin Multiplatform MQTT 3.1.1 + 5.0 client with automatic reconnection and message persistence',
  favicon: 'img/favicon.ico',

  future: {
    v4: true,
  },

  staticDirectories: ['static'],

  url: 'https://ditchoom.github.io',
  baseUrl: '/mqtt/',

  organizationName: 'DitchOoM',
  projectName: 'mqtt',
  trailingSlash: false,

  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  // Kotlin Playground for interactive examples
  scripts: [
    {
      src: 'https://unpkg.com/kotlin-playground@1',
      async: true,
    },
  ],

  themes: ['docusaurus-theme-github-codeblock'],

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/DitchOoM/mqtt/tree/main/docs/',
          routeBasePath: '/', // Docs at root
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    colorMode: {
      respectPrefersColorScheme: true,
    },
    codeblock: {
      showGithubLink: true,
      githubLinkLabel: 'View on GitHub',
    },
    navbar: {
      title: 'MQTT',
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Docs',
        },
        {
          to: '/getting-started',
          label: 'Getting Started',
          position: 'left',
        },
        {
          type: 'dropdown',
          label: 'API Reference',
          position: 'left',
          items: [
            {
              href: 'pathname:///api/mqtt-client/index.html',
              label: 'MQTT Client',
            },
            {
              href: 'pathname:///api/mqtt-base-models/index.html',
              label: 'Base Models',
            },
            {
              href: 'pathname:///api/mqtt-4-models/index.html',
              label: 'MQTT 3.1.1 (v4) Models',
            },
            {
              href: 'pathname:///api/mqtt-5-models/index.html',
              label: 'MQTT 5.0 Models',
            },
          ],
        },
        {
          href: 'https://github.com/DitchOoM/mqtt',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Documentation',
          items: [
            {label: 'Introduction', to: '/'},
            {label: 'Getting Started', to: '/getting-started'},
          ],
        },
        {
          title: 'Resources',
          items: [
            {label: 'GitHub', href: 'https://github.com/DitchOoM/mqtt'},
            {label: 'Maven Central', href: 'https://search.maven.org/artifact/com.ditchoom/mqtt-client'},
          ],
        },
        {
          title: 'More',
          items: [
            {label: 'DitchOoM', href: 'https://github.com/DitchOoM'},
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} DitchOoM. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['kotlin', 'groovy', 'java', 'bash'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
