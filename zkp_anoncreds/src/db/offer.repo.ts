import pool from './pool'

export interface OfferRow {
  id: string
  credDefId: string
  offer: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

const map = (r: any): OfferRow => ({
  id: r.id,
  credDefId: r.cred_def_id,
  offer: r.offer,
  createdAt: r.created_at,
  updatedAt: r.updated_at,
})

export async function saveOffer(row: Omit<OfferRow, 'createdAt' | 'updatedAt'>): Promise<void> {
  await pool.query(
    `INSERT INTO credential_offers (id, cred_def_id, offer) VALUES ($1,$2,$3)`,
    [row.id, row.credDefId, JSON.stringify(row.offer)]
  )
}

export async function getOfferById(id: string): Promise<OfferRow | null> {
  const { rows } = await pool.query(`SELECT * FROM credential_offers WHERE id=$1`, [id])
  return rows[0] ? map(rows[0]) : null
}
