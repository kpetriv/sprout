import { defineConfig } from 'vitest/config';

// The suites share one Firestore emulator and one project (demo-sprout), so files run one after another.
export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    fileParallelism: false,
    testTimeout: 30_000,
    hookTimeout: 60_000,
  },
});
