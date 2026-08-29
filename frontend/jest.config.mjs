import nextJest from "next/jest.js";

// next.config.ts / .env 로딩을 next/jest 에 위임한다 (Next.js 공식 테스트 가이드 패턴).
const createJestConfig = nextJest({ dir: "./" });

/** @type {import('jest').Config} */
const customJestConfig = {
  setupFilesAfterEnv: ["<rootDir>/jest.setup.ts"],
  testEnvironment: "jest-environment-jsdom",
  moduleNameMapper: {
    "^@/(.*)$": "<rootDir>/src/$1",
  },
  // NOTE (card F2): react-markdown ships ESM-only dependencies that Jest cannot
  // `require()` unmodified. The fix lives in next.config.ts's `transpilePackages`
  // (which this file's `createJestConfig` already loads via `nextJest({ dir: "./" })`
  // above), not here -- next/jest hardcodes an unconditional `/node_modules/` entry into
  // `transformIgnorePatterns` whenever `transpilePackages` is empty, appended *after* any
  // custom patterns from this file, so nothing added here can carve out an exception (see
  // next.config.ts's own comment for the full explanation).
};

export default createJestConfig(customJestConfig);
