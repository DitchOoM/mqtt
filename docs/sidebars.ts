import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docsSidebar: [
    'intro',
    'getting-started',
    {
      type: 'category',
      label: 'Core Concepts',
      items: [
        'core-concepts/mqtt-client',
        'core-concepts/connection-options',
        'core-concepts/persistence',
      ],
    },
    {
      type: 'category',
      label: 'Recipes',
      items: [
        'recipes/typed-payloads',
        'recipes/quality-of-service',
        'recipes/reconnection-and-high-availability',
        'recipes/transports',
      ],
    },
    {
      type: 'category',
      label: 'Platforms',
      items: [
        'platforms/jvm',
        'platforms/android',
        'platforms/apple',
        'platforms/javascript',
        'platforms/linux',
      ],
    },
    'migration',
  ],
};

export default sidebars;
