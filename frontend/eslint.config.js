import js from '@eslint/js';
import globals from 'globals';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';

export default [
  { ignores: ['dist/', 'node_modules/', 'coverage/'] },
  js.configs.recommended,
  {
    files: ['**/*.{js,jsx,mjs,cjs}'],
    plugins: { react, 'react-hooks': reactHooks },
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser },
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    settings: { react: { version: 'detect' } },
    rules: {
      ...react.configs.recommended.rules,
      ...react.configs['jsx-runtime'].rules,
      ...reactHooks.configs.recommended.rules,
      'react/prop-types': 'off', // the project does not use PropTypes
      // Vite's React plugin uses the automatic JSX runtime, so `import React` is optional; many files keep it. Destructuring
      // used to leave a property out (`const { a, ...rest } = obj`) is a normal pattern, not an unused variable.
      'no-unused-vars': ['error', { varsIgnorePattern: '^React$', ignoreRestSiblings: true, caughtErrors: 'none' }],
    },
  },
  {
    // Tests, build scripts and tool configs run in Node, not in the browser
    files: ['**/*.test.{js,jsx}', 'scripts/**', '*.config.{js,cjs,mjs}'],
    languageOptions: { globals: { ...globals.node } },
  },
  { files: ['**/*.cjs'], languageOptions: { sourceType: 'commonjs', globals: { ...globals.node } } },
];
