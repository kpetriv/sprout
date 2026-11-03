import { defineConfig } from 'vitest/config';

// The suites share one Auth and one Firestore emulator (project demo-sprout) and clear them between tests, so files
// run one after another.
export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    fileParallelism: false,
    testTimeout: 30_000,
    hookTimeout: 60_000,
    setupFiles: ['test/setup.ts'],
  },
});
