import pool from './pool'

export interface PresentationRow {
  id: string
  presentationRequestId: string
  heldCredentialId: string
  presentation: Record<string, unknown>
  verified: boolean | null
}

export async function savePresentation(row: Omit<PresentationRow, 'verified'>): Promise<void> {
  await pool.query(
    `INSERT INTO presentations (id, presentation_request_id, held_credential_id, presentation) VALUES ($1,$2,$3,$4)`,
    [row.id, row.presentationRequestId, row.heldCredentialId, JSON.stringify(row.presentation)]
  )
}

export async function updatePresentationVerified(id: string, verified: boolean): Promise<void> {
  await pool.query(`UPDATE presentations SET verified=$1 WHERE id=$2`, [verified, id])
}

export async function getAllPresentations(): Promise<PresentationRow[]> {
  const { rows } = await pool.query(`SELECT * FROM presentations ORDER BY created_at DESC`)
  return rows.map((r) => ({ id: r.id, presentationRequestId: r.presentation_request_id, heldCredentialId: r.held_credential_id, presentation: r.presentation, verified: r.verified }))
}

export async function getPresentationById(id: string): Promise<PresentationRow | null> {
  const { rows } = await pool.query(`SELECT * FROM presentations WHERE id=$1`, [id])
  if (!rows[0]) return null
  return {
    id: rows[0].id,
    presentationRequestId: rows[0].presentation_request_id,
    heldCredentialId: rows[0].held_credential_id,
    presentation: rows[0].presentation,
    verified: rows[0].verified,
  }
}
