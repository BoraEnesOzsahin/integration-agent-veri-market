import pool from './pool'

export interface PresentationRequestRow {
  id: string
  credDefId: string
  requestedAttributes: Record<string, { name: string }>
  requestedPredicates: Record<string, unknown>
  nonce: string
  name: string
  version: string
}

export async function savePresentationRequest(row: PresentationRequestRow): Promise<void> {
  await pool.query(
    `INSERT INTO presentation_requests (id, cred_def_id, requested_attributes, requested_predicates, nonce, name, version) VALUES ($1,$2,$3,$4,$5,$6,$7)`,
    [
      row.id,
      row.credDefId,
      JSON.stringify(row.requestedAttributes),
      JSON.stringify(row.requestedPredicates),
      row.nonce,
      row.name,
      row.version,
    ]
  )
}

export async function getAllPresentationRequests(): Promise<PresentationRequestRow[]> {
  const { rows } = await pool.query(`SELECT * FROM presentation_requests ORDER BY created_at DESC`)
  return rows.map((r) => ({ id: r.id, credDefId: r.cred_def_id, requestedAttributes: r.requested_attributes, requestedPredicates: r.requested_predicates, nonce: r.nonce, name: r.name, version: r.version }))
}

export async function getPresentationRequestById(id: string): Promise<PresentationRequestRow | null> {
  const { rows } = await pool.query(`SELECT * FROM presentation_requests WHERE id=$1`, [id])
  if (!rows[0]) return null
  return {
    id: rows[0].id,
    credDefId: rows[0].cred_def_id,
    requestedAttributes: rows[0].requested_attributes,
    requestedPredicates: rows[0].requested_predicates,
    nonce: rows[0].nonce,
    name: rows[0].name,
    version: rows[0].version,
  }
}
