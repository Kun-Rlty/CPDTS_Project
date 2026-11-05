import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Isolated browser-test proxy; never points to the ordinary development server.
export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', port: 15173, strictPort: true,
    proxy: { '/api': 'http://127.0.0.1:18081' } },
})
