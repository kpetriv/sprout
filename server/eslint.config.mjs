import js from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', '.vercel/**', '*.log'] },
  js.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  {
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    rules: {
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    },
  },
  {
    // Production code logs through src/log.ts only (ids and counts, never personal data or codes).
    files: ['src/**/*.ts', 'api/**/*.ts'],
    rules: {
      'no-console': 'error',
      'no-restricted-imports': [
        'error',
        {
          paths: [
            { name: 'hono/logger', message: 'Log through src/log.ts.' },
            { name: 'hono/vercel', message: 'Deprecated: use @hono/vercel.' },
          ],
          patterns: [
            {
              group: ['firebase-functions', 'firebase-functions/*'],
              message: 'The server is not Cloud Functions (D-M3-29): use src/https.ts and src/log.ts.',
            },
            { group: ['pino', 'pino/*', 'winston', 'winston/*', 'debug', 'loglevel'], message: 'Log through src/log.ts.' },
          ],
        },
      ],
    },
  },
  {
    files: ['src/log.ts'],
    rules: { 'no-console': 'off' },
  },
  {
    files: ['eslint.config.mjs'],
    ...tseslint.configs.disableTypeChecked,
  },
);
