import pool from './pool'

export interface WalletRow {
  id: string
  label: string
  did: string
  verkey: string
  createdAt: string
  updatedAt: string
}

const map = (r: any): WalletRow => ({
  id: r.id,
  label: r.label,
  did: r.did,
  verkey: r.verkey,
  createdAt: r.created_at,
  updatedAt: r.updated_at,
})

export async function saveWallet(wallet: Omit<WalletRow, 'createdAt' | 'updatedAt'>): Promise<void> {
  await pool.query(
    `INSERT INTO wallets (id, label, did, verkey) VALUES ($1,$2,$3,$4)`,
    [wallet.id, wallet.label, wallet.did, wallet.verkey]
  )
}

export async function getWalletById(id: string): Promise<WalletRow | null> {
  const { rows } = await pool.query(`SELECT * FROM wallets WHERE id=$1`, [id])
  return rows[0] ? map(rows[0]) : null
}

export async function getAllWallets(): Promise<WalletRow[]> {
  const { rows } = await pool.query(`SELECT * FROM wallets ORDER BY created_at DESC`)
  return rows.map(map)
}

export async function getWalletByDid(did: string): Promise<WalletRow | null> {
  const { rows } = await pool.query(`SELECT * FROM wallets WHERE did=$1`, [did])
  return rows[0] ? map(rows[0]) : null
}
