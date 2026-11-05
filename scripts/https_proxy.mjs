/**
 * Local HTTPS reverse proxy used only by the guarded browser-test runner.
 *
 * It mirrors the production nginx contract in deploy/nginx/cpdts.conf:
 *   - one HTTPS origin serves the built SPA and the private API;
 *   - /api and /actuator/health are proxied; API failures never fall back to
 *     index.html;
 *   - unknown non-asset paths fall back to index.html for SPA deep links;
 *   - client-supplied Forwarded / X-Forwarded-* / X-Real-IP headers are removed
 *     and replaced with values set by this proxy, plus a boundary token;
 *   - the listener is loopback-only.
 *
 * This script is not a production server and has no dependencies outside Node.
 */
import { createServer } from 'node:https'
import { createReadStream, existsSync, statSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import http from 'node:http'
import path from 'node:path'

const HSTS = 'max-age=31536000'
const FORWARDED_HEADERS = new Set([
  'forwarded', 'x-forwarded-for', 'x-forwarded-host', 'x-forwarded-port',
  'x-forwarded-prefix', 'x-forwarded-proto', 'x-forwarded-server',
  'x-forwarded-ssl', 'x-real-ip', 'x-cpdts-proxy-token',
])
const HOP_BY_HOP = new Set([
  'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'transfer-encoding', 'upgrade',
])
const ASSET_PATH = /(?:^\/assets\/|\.(?:js|css|map|png|jpe?g|gif|svg|ico|webp|woff2?|ttf|eot|txt|json|webmanifest)$)/i
const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
}

const port = integer('CPDTS_HTTPS_PORT', 18443)
const upstreamPort = integer('CPDTS_UPSTREAM_PORT', 18082)
const upstreamHost = process.env.CPDTS_UPSTREAM_HOST ?? '127.0.0.1'
const dist = path.resolve(process.env.CPDTS_DIST ?? 'frontend/dist')
const certPath = process.env.CPDTS_TLS_CERT
const keyPath = process.env.CPDTS_TLS_KEY
const token = process.env.CPDTS_PROXY_TOKEN

if (!certPath || !keyPath || !existsSync(certPath) || !existsSync(keyPath)) {
  throw new Error('CPDTS_TLS_CERT and CPDTS_TLS_KEY must point to readable local certificate files')
}
if (!token) {
  throw new Error('CPDTS_PROXY_TOKEN is required')
}
if (!existsSync(path.join(dist, 'index.html'))) {
  throw new Error(`Built frontend not found at ${dist}; run npm run build first`)
}

function integer(name, fallback) {
  const raw = process.env[name]
  if (raw === undefined || raw === '') return fallback
  const value = Number(raw)
  if (!Number.isInteger(value) || value < 1 || value > 65535) {
    throw new Error(`${name} must be an integer port between 1 and 65535`)
  }
  return value
}

function securityHeaders(extra = {}) {
  return {
    'Strict-Transport-Security': HSTS,
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    ...extra,
  }
}

function sendJson(response, status, body) {
  response.writeHead(status, securityHeaders({ 'Content-Type': 'application/json; charset=utf-8' }))
  response.end(JSON.stringify(body))
}

function sendText(response, status, message) {
  response.writeHead(status, securityHeaders({ 'Content-Type': 'text/plain; charset=utf-8' }))
  response.end(message)
}

function fileFor(pathname) {
  let decoded
  try {
    decoded = decodeURIComponent(pathname)
  } catch {
    return null
  }
  if (decoded.includes('\0')) return null
  const full = path.resolve(dist, `.${path.sep}${decoded.replace(/^\/+/, '')}`)
  if (full !== dist && !full.startsWith(dist + path.sep)) return null
  return full
}

function sendFile(request, response, file) {
  const name = path.basename(file)
  const isIndex = name === 'index.html'
  const cache = isIndex ? 'no-store'
    : file.startsWith(path.join(dist, 'assets') + path.sep) ? 'public, max-age=31536000, immutable'
      : 'no-cache'
  response.writeHead(200, securityHeaders({
    'Content-Type': CONTENT_TYPES[path.extname(file).toLowerCase()] ?? 'application/octet-stream',
    'Content-Length': statSync(file).size,
    'Cache-Control': cache,
  }))
  if (request.method === 'HEAD') {
    response.end()
    return
  }
  createReadStream(file).pipe(response)
}

function serveStatic(request, response, pathname) {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    sendText(response, 405, 'Method not allowed')
    return
  }

  const file = fileFor(pathname)
  if (file && existsSync(file) && statSync(file).isFile()) {
    sendFile(request, response, file)
    return
  }
  if (file && existsSync(file) && statSync(file).isDirectory()) {
    const index = path.join(file, 'index.html')
    if (existsSync(index)) {
      sendFile(request, response, index)
      return
    }
  }

  // Missing build assets are real 404s; other paths are SPA deep links.
  if (ASSET_PATH.test(pathname)) {
    sendText(response, 404, 'Not found')
    return
  }
  sendFile(request, response, path.join(dist, 'index.html'))
}

function proxy(request, response) {
  const headers = {}
  for (const [name, value] of Object.entries(request.headers)) {
    const lower = name.toLowerCase()
    if (!HOP_BY_HOP.has(lower) && !FORWARDED_HEADERS.has(lower)) {
      headers[name] = value
    }
  }
  // Overwrite the forwarding boundary; never forward client-supplied values.
  headers.host = `${upstreamHost}:${upstreamPort}`
  headers['x-forwarded-for'] = request.socket.remoteAddress ?? '127.0.0.1'
  headers['x-forwarded-proto'] = 'https'
  headers['x-forwarded-host'] = request.headers.host ?? `localhost:${port}`
  headers['x-forwarded-port'] = String(port)
  headers['x-cpdts-proxy-token'] = token

  const upstream = http.request({
    host: upstreamHost,
    port: upstreamPort,
    method: request.method,
    path: request.url,
    headers,
    timeout: 30_000,
  }, upstreamResponse => {
    const outgoing = { ...upstreamResponse.headers }
    delete outgoing.connection
    delete outgoing['keep-alive']
    response.writeHead(upstreamResponse.statusCode ?? 502, securityHeaders(outgoing))
    upstreamResponse.pipe(response)
  })

  upstream.on('timeout', () => upstream.destroy(new Error('upstream timeout')))
  upstream.on('error', () => {
    if (!response.headersSent) sendJson(response, 502, { error: 'bad_gateway' })
    else response.destroy()
  })
  request.pipe(upstream)
}

const server = createServer({
  cert: await readFile(certPath),
  key: await readFile(keyPath),
  minVersion: 'TLSv1.2',
}, (request, response) => {
  const pathname = new URL(request.url ?? '/', `https://localhost:${port}`).pathname
  if (pathname === '/api' || pathname.startsWith('/api/') || pathname === '/actuator/health') {
    proxy(request, response)
    return
  }
  if (pathname === '/actuator' || pathname.startsWith('/actuator/')) {
    sendJson(response, 404, { error: 'not_found' })
    return
  }
  serveStatic(request, response, pathname)
})

server.listen(port, '127.0.0.1', () => {
  console.log(`HTTPS test proxy listening on https://127.0.0.1:${port} -> http://${upstreamHost}:${upstreamPort}`)
})

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => process.exit(0)))
}
