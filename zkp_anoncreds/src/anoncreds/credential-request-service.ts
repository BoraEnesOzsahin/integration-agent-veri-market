import { randomUUID } from 'crypto'
import { CredentialOffer, CredentialRequest } from '@hyperledger/anoncreds-nodejs'
import type { JsonObject } from '@hyperledger/anoncreds-shared'
import { getCredentialDefinitionById } from '../db/cred-def.repo'
import { getOfferById } from '../db/offer.repo'
import { getLinkSecretById } from '../db/link-secret.repo'
import { saveCredentialRequest } from '../db/credential-request.repo'

export interface CreateCredentialRequestInput {
  offerId: string
  linkSecretId: string
}

export interface CreateCredentialRequestResult {
  id: string
  credDefId: string
  request: Record<string, unknown>
}

export class CredentialRequestService {
  async createRequest(input: CreateCredentialRequestInput): Promise<CreateCredentialRequestResult> {
    if (!input.offerId?.trim()) throw new Error('offerId is required.')
    if (!input.linkSecretId?.trim()) throw new Error('linkSecretId is required.')

    const offerRow = await getOfferById(input.offerId)
    if (!offerRow) throw new Error(`Credential offer not found: ${input.offerId}`)

    const linkSecretRow = await getLinkSecretById(input.linkSecretId)
    if (!linkSecretRow) throw new Error(`Link secret not found: ${input.linkSecretId}`)

    const credDefRow = await getCredentialDefinitionById(offerRow.credDefId)
    if (!credDefRow) throw new Error(`Credential definition not found: ${offerRow.credDefId}`)

    const offer = CredentialOffer.fromJson(offerRow.offer as JsonObject)

    const { credentialRequest, credentialRequestMetadata } = CredentialRequest.create({
      entropy: randomUUID(),
      credentialDefinition: credDefRow.credentialDefinition as unknown as JsonObject,
      credentialOffer: offer,
      linkSecret: linkSecretRow.value,
      linkSecretId: linkSecretRow.label,
    })

    const id = randomUUID()

    await saveCredentialRequest({
      id,
      offerId: input.offerId,
      linkSecretId: input.linkSecretId,
      credDefId: offerRow.credDefId,
      request: credentialRequest.toJson() as Record<string, unknown>,
      metadata: credentialRequestMetadata.toJson() as Record<string, unknown>,
    })

    return {
      id,
      credDefId: offerRow.credDefId,
      request: credentialRequest.toJson() as Record<string, unknown>,
    }
  }
}
