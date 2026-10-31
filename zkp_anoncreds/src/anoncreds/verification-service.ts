import { Presentation, PresentationRequest, Schema } from '@hyperledger/anoncreds-nodejs'
import type { JsonObject } from '@hyperledger/anoncreds-shared'
import { getCredentialDefinitionById } from '../db/cred-def.repo'
import { getSchemaById } from '../db/schema.repo'
import { getPresentationById, updatePresentationVerified } from '../db/presentation.repo'
import { getPresentationRequestById } from '../db/presentation-request.repo'

export interface VerifyPresentationInput {
  presentationId: string
}

export interface VerifyPresentationResult {
  presentationId: string
  verified: boolean
  disclosedAttributes: Record<string, string>
}

export interface VerifyExternalInput {
  presentation: Record<string, unknown>
  presentationRequest: {
    name: string
    version: string
    nonce: string
    requested_attributes: Record<string, unknown>
    requested_predicates: Record<string, unknown>
  }
  schema: Record<string, unknown> & { id: string }
  credentialDefinition: Record<string, unknown> & { id: string }
  /** Doğrulayıcının kendi ürettiği nonce; sunum isteğindeki nonce ile birebir eşleşmelidir. */
  expectedNonce?: string
}

export interface VerifyExternalResult {
  verified: boolean
  nonceMatch: boolean
  credDefId: string
  disclosedAttributes: Record<string, string>
  provedPredicates: Array<{ name: string; p_type: string; p_value: number }>
}

export class VerificationService {
  /**
   * Durumsuz (DB'siz) doğrulama: kanıt sahibinin kendi servisindeki 'verified' bayrağına GÜVENMEZ.
   * Doğrulayıcı kendi nonce'unu, şemasını ve cred-def'ini verir; kriptografik doğrulama yeniden yapılır.
   */
  verifyExternal(input: VerifyExternalInput): VerifyExternalResult {
    if (!input?.presentation || !input?.presentationRequest || !input?.schema || !input?.credentialDefinition) {
      throw new Error('presentation, presentationRequest, schema and credentialDefinition are required.')
    }
    const nonceMatch = input.expectedNonce === undefined || String(input.presentationRequest.nonce) === String(input.expectedNonce)
    if (!nonceMatch) {
      return { verified: false, nonceMatch: false, credDefId: input.credentialDefinition.id, disclosedAttributes: {}, provedPredicates: [] }
    }

    const presentationRequest = PresentationRequest.fromJson(input.presentationRequest as unknown as JsonObject)
    const presentation = Presentation.fromJson(input.presentation as unknown as JsonObject)
    const schema = Schema.fromJson(input.schema as unknown as JsonObject)
    const credDef = input.credentialDefinition as unknown as JsonObject

    let verified = false
    try {
      verified = presentation.verify({
        presentationRequest,
        schemas: { [input.schema.id]: schema },
        credentialDefinitions: { [input.credentialDefinition.id]: credDef },
      })
    } catch {
      verified = false
    }

    const disclosedAttributes: Record<string, string> = {}
    const requestedProof = (input.presentation as Record<string, any>).requested_proof as Record<string, any> | undefined
    for (const [referent, value] of Object.entries((requestedProof?.revealed_attrs ?? {}) as Record<string, { raw: string }>)) {
      disclosedAttributes[referent.replace(/^attr_/, '')] = value.raw
    }
    const provedPredicates = Object.values(input.presentationRequest.requested_predicates ?? {}) as Array<{ name: string; p_type: string; p_value: number }>

    return { verified, nonceMatch: true, credDefId: input.credentialDefinition.id, disclosedAttributes, provedPredicates }
  }

  async verify(input: VerifyPresentationInput): Promise<VerifyPresentationResult> {
    if (!input.presentationId?.trim()) throw new Error('presentationId is required.')

    const presRow = await getPresentationById(input.presentationId)
    if (!presRow) throw new Error(`Presentation not found: ${input.presentationId}`)

    const presReqRow = await getPresentationRequestById(presRow.presentationRequestId)
    if (!presReqRow) throw new Error(`Presentation request not found: ${presRow.presentationRequestId}`)

    const credDefRow = await getCredentialDefinitionById(presReqRow.credDefId)
    if (!credDefRow) throw new Error(`Credential definition not found: ${presReqRow.credDefId}`)

    const schemaRow = await getSchemaById(credDefRow.credentialDefinition.schemaId)
    if (!schemaRow) throw new Error(`Schema not found: ${credDefRow.credentialDefinition.schemaId}`)

    const presReqJson = {
      name: presReqRow.name,
      version: presReqRow.version,
      nonce: presReqRow.nonce,
      requested_attributes: presReqRow.requestedAttributes,
      requested_predicates: presReqRow.requestedPredicates,
    }

    const presentationRequest = PresentationRequest.fromJson(presReqJson as JsonObject)
    const presentation = Presentation.fromJson(presRow.presentation as JsonObject)
    const schema = Schema.fromJson(schemaRow as unknown as JsonObject)
    const credDef = credDefRow.credentialDefinition as unknown as JsonObject

    const verified = presentation.verify({
      presentationRequest,
      schemas: { [schemaRow.id]: schema },
      credentialDefinitions: { [presReqRow.credDefId]: credDef },
    })

    await updatePresentationVerified(input.presentationId, verified)

    const disclosedAttributes: Record<string, string> = {}
    const presJson = presRow.presentation as Record<string, unknown>
    const requestedProof = presJson.requested_proof as Record<string, unknown> | undefined
    const revealedAttrs = (requestedProof?.revealed_attrs ?? {}) as Record<string, { raw: string }>
    for (const [referent, value] of Object.entries(revealedAttrs)) {
      const attrName = referent.replace(/^attr_/, '')
      disclosedAttributes[attrName] = value.raw
    }

    return { presentationId: input.presentationId, verified, disclosedAttributes }
  }
}
