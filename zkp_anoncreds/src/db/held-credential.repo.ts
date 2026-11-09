import pool from './pool'

export interface HeldCredentialRow {
  id: string
  issuedCredentialId: string
  linkSecretId: string
  credDefId: string
  schemaId: string
  createdAt: string
  updatedAt: string
}

const map = (r: any): HeldCredentialRow => ({
  id: r.id,
  issuedCredentialId: r.issued_credential_id,
  linkSecretId: r.link_secret_id,
  credDefId: r.cred_def_id,
  schemaId: r.schema_id,
  createdAt: r.created_at,
  updatedAt: r.updated_at,
})

export async function saveHeldCredential(row: Omit<HeldCredentialRow, 'createdAt' | 'updatedAt'>): Promise<void> {
  await pool.query(
    `INSERT INTO held_credentials (id, issued_credential_id, link_secret_id, cred_def_id, schema_id)
     VALUES ($1,$2,$3,$4,$5)`,
    [row.id, row.issuedCredentialId, row.linkSecretId, row.credDefId, row.schemaId]
  )
}

