import { randomUUID } from 'crypto'
import { Credential, CredentialOffer, CredentialRequest } from '@hyperledger/anoncreds-nodejs'
import type { JsonObject } from '@hyperledger/anoncreds-shared'
import { getCredentialDefinitionById } from '../db/cred-def.repo'
import { getOfferById } from '../db/offer.repo'
import { getCredentialRequestById } from '../db/credential-request.repo'
import { saveIssuedCredential } from '../db/issued-credential.repo'
import { getSchemaById } from '../db/schema.repo'

export interface IssueCredentialInput {
  credentialRequestId: string
  attributeValues: Record<string, string>
}

export interface IssueCredentialResult {
  id: string
  credDefId: string
  schemaId: string
  credential: Record<string, unknown>
}

export class IssuanceService {
  async issue(input: IssueCredentialInput): Promise<IssueCredentialResult> {
    if (!input.credentialRequestId?.trim()) throw new Error('credentialRequestId is required.')
    if (!input.attributeValues || Object.keys(input.attributeValues).length === 0) {
      throw new Error('attributeValues is required.')
    }

    const requestRow = await getCredentialRequestById(input.credentialRequestId)
    if (!requestRow) throw new Error(`Credential request not found: ${input.credentialRequestId}`)

    const offerRow = await getOfferById(requestRow.offerId)
    if (!offerRow) throw new Error(`Credential offer not found: ${requestRow.offerId}`)

    const credDefRow = await getCredentialDefinitionById(requestRow.credDefId)
    if (!credDefRow) throw new Error(`Credential definition not found: ${requestRow.credDefId}`)

    const schemaRow = await getSchemaById(credDefRow.credentialDefinition.schemaId)
    if (!schemaRow) throw new Error(`Schema not found: ${credDefRow.credentialDefinition.schemaId}`)

    for (const attr of schemaRow.attrNames) {
      if (!(attr in input.attributeValues)) {
        throw new Error(`Missing attribute value for: ${attr}. Required: ${schemaRow.attrNames.join(', ')}`)
      }
    }

    const offer = CredentialOffer.fromJson(offerRow.offer as JsonObject)
    const request = CredentialRequest.fromJson(requestRow.request as JsonObject)

    const issued = Credential.create({
      credentialDefinition: credDefRow.credentialDefinition as unknown as JsonObject,
      credentialDefinitionPrivate: credDefRow.credentialDefinitionPrivate as unknown as JsonObject,
      credentialOffer: offer,
      credentialRequest: request,
      attributeRawValues: input.attributeValues,
    })

    const id = randomUUID()
    const credentialJson = issued.toJson() as Record<string, unknown>

    await saveIssuedCredential({
      id,
      credentialRequestId: input.credentialRequestId,
      credDefId: requestRow.credDefId,
      schemaId: credDefRow.credentialDefinition.schemaId,
      credential: credentialJson,
    })

    return {
      id,
      credDefId: requestRow.credDefId,
      schemaId: credDefRow.credentialDefinition.schemaId,
      credential: credentialJson,
    }
  }
}
