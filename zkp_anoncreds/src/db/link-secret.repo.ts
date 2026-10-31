import pool from './pool'

export interface LinkSecretRow {
  id: string
  label: string
  value: string
  createdAt: string
  updatedAt: string
}

const map = (r: any): LinkSecretRow => ({
  id: r.id,
  label: r.label,
  value: r.value,
  createdAt: r.created_at,
  updatedAt: r.updated_at,
})

export async function saveLinkSecret(row: Omit<LinkSecretRow, 'createdAt' | 'updatedAt'>): Promise<void> {
  await pool.query(
    `INSERT INTO link_secrets (id, label, value) VALUES ($1,$2,$3)`,
    [row.id, row.label, row.value]
  )
}

export async function getLinkSecretById(id: string): Promise<LinkSecretRow | null> {
  const { rows } = await pool.query(`SELECT * FROM link_secrets WHERE id=$1`, [id])
  return rows[0] ? map(rows[0]) : null
}
