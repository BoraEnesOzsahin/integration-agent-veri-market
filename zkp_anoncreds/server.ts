import path from 'path'
import express from 'express'
import swaggerUi from 'swagger-ui-express'
import swaggerJsdoc from 'swagger-jsdoc'
import type { OpenAPIV3 } from 'openapi-types'

import { runMigrations } from './src/db/migrate'
import walletRoutes from './src/routes/wallet.routes'
import schemaRoutes from './src/routes/schema.routes'
import credDefRoutes from './src/routes/cred-def.routes'
import holderRoutes from './src/routes/holder.routes'
import issueRoutes from './src/routes/issue.routes'
import verifyRoutes from './src/routes/verify.routes'
import testRoutes from './src/routes/test.routes'

const app = express()
app.use(express.json())

const swaggerOptions = {
  definition: {
    openapi: '3.0.0',
    info: {
      title: 'ZKP AnonCreds API',
      version: '2.0.0',
      description: 'Full AnonCreds credential lifecycle: wallet → schema → cred-def → offer → request → issue → receive → present → verify',
    },
    servers: [{ url: 'http://localhost:3000' }],
    tags: [
      { name: 'Wallet', description: 'Step 1 — Issuer wallet & DID' },
      { name: 'Schema', description: 'Step 2 — Schema management' },
      { name: 'CredDef', description: 'Step 3 — Credential Definition management' },
      { name: 'Holder', description: 'Steps 4, 6, 8, 10 — Holder operations' },
      { name: 'Issue', description: 'Steps 5, 7 — Issuer operations' },
      { name: 'Verify', description: 'Steps 9, 11 — Verifier operations' },
      { name: 'Test', description: 'Step 12 — End-to-end test scenarios' },
    ],
  },
  apis: [
    path.join(process.cwd(), 'src/routes/*.ts'),
    path.join(process.cwd(), 'dist/src/routes/*.js'),
  ],
}

const swaggerDocs = swaggerJsdoc(swaggerOptions) as OpenAPIV3.Document

app.use('/api-docs', swaggerUi.serve, swaggerUi.setup(swaggerDocs))
app.use('/api/wallet', walletRoutes)
app.use('/api/schema', schemaRoutes)
app.use('/api/cred-def', credDefRoutes)
app.use('/api/holder', holderRoutes)
app.use('/api/issue', issueRoutes)
app.use('/api/verify', verifyRoutes)
app.use('/api/test', testRoutes)

async function start() {
  await runMigrations()
  app.listen(3000, () => {
    console.log('[Server] Listening on http://localhost:3000')
    console.log('[Server] Swagger UI: http://localhost:3000/api-docs')
  })
}

start().catch((err) => {
  console.error('[Server] Failed to start:', err.message)
  process.exit(1)
})
