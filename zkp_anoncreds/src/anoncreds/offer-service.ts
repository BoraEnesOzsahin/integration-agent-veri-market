import { randomUUID } from 'crypto'
import { CredentialOffer } from '@hyperledger/anoncreds-nodejs'
import type { JsonObject } from '@hyperledger/anoncreds-shared'
import { getCredentialDefinitionById } from '../db/cred-def.repo'
import { saveOffer } from '../db/offer.repo'

export interface CreateOfferInput {
  credDefId: string
}

export interface CreateOfferResult {
  id: string
  credDefId: string
  offer: Record<string, unknown>
}

export class OfferService {
  async createOffer(input: CreateOfferInput): Promise<CreateOfferResult> {
    if (!input.credDefId?.trim()) throw new Error('credDefId is required.')

    const credDefRow = await getCredentialDefinitionById(input.credDefId)
    if (!credDefRow) throw new Error(`Credential definition not found: ${input.credDefId}`)

    const offer = CredentialOffer.create({
      schemaId: credDefRow.credentialDefinition.schemaId,
      credentialDefinitionId: input.credDefId,
      keyCorrectnessProof: credDefRow.keyCorrectnessProof as JsonObject,
    })

    const id = randomUUID()
    const offerJson = offer.toJson() as Record<string, unknown>

    await saveOffer({ id, credDefId: input.credDefId, offer: offerJson })

    return { id, credDefId: input.credDefId, offer: offerJson }
  }
}
