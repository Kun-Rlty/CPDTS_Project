import { defineConfig } from '@playwright/test'

// Local HTTPS verification only. The certificate is generated outside the
// repository by scripts/create_local_https_cert.ps1; ignoreHTTPSErrors is a
// deliberate test-only setting for that self-signed origin. Production
// configuration uses a trusted certificate and never disables validation.
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/https.pw.ts',
  timeout: 120_000,
  workers: 1,
  retries: 0,
  use: {
    baseURL: `https://localhost:${process.env.CPDTS_HTTPS_PORT ?? '18443'}`,
    browserName: 'chromium',
    channel: 'msedge',
    headless: true,
    ignoreHTTPSErrors: true,
  },
  reporter: 'list',
})
