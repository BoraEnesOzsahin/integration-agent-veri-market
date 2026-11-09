import express from 'express'

import { runMigrations } from './src/db/migrate'
import verifyRoutes from './src/routes/verify.routes'
import institutionRoutes from './src/routes/institution.routes'

const app = express()
app.use(express.json({ limit: '2mb' }))

// Ajan kurum belgesini ve kanıtları, koordinatör kanıt doğrulamasını kullanır; başka uç yoktur
app.get('/api/health', (_req, res) => { res.json({ ok: true }) })
app.use('/api/verify', verifyRoutes)
app.use('/api/institution', institutionRoutes)

// Kurum makinesinde yalnızca ajan erişir: HOST=127.0.0.1
const host = process.env.HOST || '0.0.0.0'

async function start() {
  await runMigrations()
  app.listen(3000, host, () => {
    console.log(`[Server] Listening on ${host}:3000`)
  })
}

start().catch((err) => {
  console.error('[Server] Failed to start:', err.message)
  process.exit(1)
})
