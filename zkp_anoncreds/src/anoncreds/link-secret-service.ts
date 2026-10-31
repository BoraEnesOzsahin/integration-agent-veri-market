import { randomUUID } from 'crypto'
import { LinkSecret } from '@hyperledger/anoncreds-nodejs'
import { saveLinkSecret } from '../db/link-secret.repo'

export interface CreateLinkSecretInput {
  label: string
}

export interface CreateLinkSecretResult {
  id: string
  label: string
}

export class LinkSecretService {
  async create(input: CreateLinkSecretInput): Promise<CreateLinkSecretResult> {
    if (!input.label?.trim()) throw new Error('label is required.')

    const id = randomUUID()
    const value = LinkSecret.create()

    await saveLinkSecret({ id, label: input.label, value })

    return { id, label: input.label }
  }
}
