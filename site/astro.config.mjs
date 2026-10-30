// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import threebolt from '../editors/vscode/syntaxes/threebolt.tmLanguage.json' with { type: 'json' };

export default defineConfig({
  integrations: [
    starlight({
      title: 'Threebolt',
      sidebar: [
        { label: 'Tour of Threebolt', items: [{ autogenerate: { directory: 'tour' } }] },
      ],
      expressiveCode: {
        // The VS Code extension's TextMate grammar is the single source of highlighting.
        shiki: { langs: [{ ...threebolt, name: 'threebolt', aliases: ['3b'] }] },
      },
    }),
  ],
});
