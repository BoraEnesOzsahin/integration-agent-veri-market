import pool from './pool'

export interface CredentialRequestRow {
  id: string
  offerId: string
  linkSecretId: string
  credDefId: string
  request: Record<string, unknown>
  metadata: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

const map = (r: any): CredentialRequestRow => ({
  id: r.id,
  offerId: r.offer_id,
  linkSecretId: r.link_secret_id,
  credDefId: r.cred_def_id,
  request: r.request,
  metadata: r.metadata,
  createdAt: r.created_at,
  updatedAt: r.updated_at,
})

export async function saveCredentialRequest(row: Omit<CredentialRequestRow, 'createdAt' | 'updatedAt'>): Promise<void> {
  await pool.query(
    `INSERT INTO credential_requests (id, offer_id, link_secret_id, cred_def_id, request, metadata) VALUES ($1,$2,$3,$4,$5,$6)`,
    [row.id, row.offerId, row.linkSecretId, row.credDefId, JSON.stringify(row.request), JSON.stringify(row.metadata)]
  )
}

export async function getCredentialRequestById(id: string): Promise<CredentialRequestRow | null> {
  const { rows } = await pool.query(`SELECT * FROM credential_requests WHERE id=$1`, [id])
  return rows[0] ? map(rows[0]) : null
}

export async function getCredentialRequestByIssuedCredentialId(
  issuedCredentialId: string
): Promise<CredentialRequestRow | null> {
  const { rows } = await pool.query(
    `SELECT cr.* FROM credential_requests cr
     JOIN issued_credentials ic ON ic.credential_request_id = cr.id
     WHERE ic.id = $1`,
    [issuedCredentialId]
  )
  return rows[0] ? map(rows[0]) : null
}
