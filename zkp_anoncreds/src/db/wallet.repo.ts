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

