import { randomUUID } from 'crypto'
import { saveWallet, WalletRow } from '../db/wallet.repo'

export interface CreateWalletInput {
  label: string
  issuerId?: string
}

export interface CreateWalletResult {
  id: string
  label: string
  did: string
  verkey: string
  createdAt: string
  updatedAt: string
}


export class WalletService {
  async createWallet(input: CreateWalletInput): Promise<CreateWalletResult> {
    if (!input.label?.trim()) {
      throw new Error('label is required.')
    }

    const id = randomUUID()
    const slug = input.label
      .toLowerCase()
      .replace(/\s+/g, '-')
      .replace(/[^a-z0-9-]/g, '')

    const did = input.issuerId ?? `did:example:${slug}`
    const verkey = Buffer.from(
      randomUUID().replace(/-/g, ''),
      'hex'
    ).toString('base64url')

    const createdAt = new Date().toISOString()
    const updatedAt = createdAt

    const row: WalletRow = {
      id,
      label: input.label,
      did,
      verkey,
      createdAt,
      updatedAt,
    }

    await saveWallet(row)

    return {
      id,
      label: input.label,
      did,
      verkey,
      createdAt,
      updatedAt,
    }
  }
}
