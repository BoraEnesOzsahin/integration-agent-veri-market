import express from 'express'

import { runMigrations } from './src/db/migrate'
import walletRoutes from './src/routes/wallet.routes'
import schemaRoutes from './src/routes/schema.routes'
import credDefRoutes from './src/routes/cred-def.routes'
import holderRoutes from './src/routes/holder.routes'
import issueRoutes from './src/routes/issue.routes'
import verifyRoutes from './src/routes/verify.routes'
import institutionRoutes from './src/routes/institution.routes'

const app = express()
app.use(express.json({ limit: '2mb' }))

app.use('/api/wallet', walletRoutes)
app.use('/api/schema', schemaRoutes)
app.use('/api/cred-def', credDefRoutes)
app.use('/api/holder', holderRoutes)
app.use('/api/issue', issueRoutes)
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
