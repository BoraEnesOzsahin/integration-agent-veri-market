import { Pool } from 'pg'
import config from '../config/config'

const pool = new Pool({
  connectionString: config.databaseUrl,
})

pool.on('error', (err) => {
  console.error('[DB] Unexpected pool error:', err.message)
})

export default pool
