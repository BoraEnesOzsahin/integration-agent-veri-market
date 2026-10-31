import pool from './pool'

export interface IssuedCredentialRow {
  id: string
  credentialRequestId: string
  credDefId: string
  schemaId: string
  credential: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

const map = (r: any): IssuedCredentialRow => ({
  id: r.id,
  credentialRequestId: r.credential_request_id,
  credDefId: r.cred_def_id,
  schemaId: r.schema_id,
  credential: r.credential,
  createdAt: r.created_at,
  updatedAt: r.updated_at,
})

export async function saveIssuedCredential(row: Omit<IssuedCredentialRow, 'createdAt' | 'updatedAt'>): Promise<void> {
  await pool.query(
    `INSERT INTO issued_credentials (id, credential_request_id, cred_def_id, schema_id, credential) VALUES ($1,$2,$3,$4,$5)`,
    [row.id, row.credentialRequestId, row.credDefId, row.schemaId, JSON.stringify(row.credential)]
  )
}

export async function getAllIssuedCredentials(): Promise<IssuedCredentialRow[]> {
  const { rows } = await pool.query(`SELECT * FROM issued_credentials ORDER BY created_at DESC`)
  return rows.map(map)
}

export async function getIssuedCredentialById(id: string): Promise<IssuedCredentialRow | null> {
  const { rows } = await pool.query(`SELECT * FROM issued_credentials WHERE id=$1`, [id])
  return rows[0] ? map(rows[0]) : null
}
