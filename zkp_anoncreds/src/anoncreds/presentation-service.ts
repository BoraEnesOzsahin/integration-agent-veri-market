import { randomUUID } from 'crypto'
import { Credential, Presentation, PresentationRequest, Schema } from '@hyperledger/anoncreds-nodejs'
import type { JsonObject } from '@hyperledger/anoncreds-shared'
import { getCredentialDefinitionById } from '../db/cred-def.repo'
import { getSchemaById } from '../db/schema.repo'
import { getLinkSecretById } from '../db/link-secret.repo'
import { getPresentationRequestById } from '../db/presentation-request.repo'
import { savePresentation } from '../db/presentation.repo'

export interface CreatePresentationInput {
  presentationRequestId: string
  heldCredentialId: string
  // Holder'ın local wallet'ından gelen credential verisi
  credentialJson: Record<string, unknown>
  linkSecretId: string
  credDefId: string
  schemaId: string
}

export interface CreatePresentationResult {
  id: string
}

export class PresentationService {
  async createPresentation(input: CreatePresentationInput): Promise<CreatePresentationResult> {
    if (!input.presentationRequestId?.trim()) throw new Error('presentationRequestId is required.')
    if (!input.heldCredentialId?.trim()) throw new Error('heldCredentialId is required.')
    if (!input.credentialJson) throw new Error('credentialJson is required.')

    const presReqRow = await getPresentationRequestById(input.presentationRequestId)
    if (!presReqRow) throw new Error(`Presentation request not found: ${input.presentationRequestId}`)

    const linkSecretRow = await getLinkSecretById(input.linkSecretId)
    if (!linkSecretRow) throw new Error(`Link secret not found: ${input.linkSecretId}`)

    const credDefRow = await getCredentialDefinitionById(input.credDefId)
    if (!credDefRow) throw new Error(`Credential definition not found: ${input.credDefId}`)

    const schemaRow = await getSchemaById(input.schemaId)
    if (!schemaRow) throw new Error(`Schema not found: ${input.schemaId}`)

    const presReqJson = {
      name: presReqRow.name,
      version: presReqRow.version,
      nonce: presReqRow.nonce,
      requested_attributes: presReqRow.requestedAttributes,
      requested_predicates: presReqRow.requestedPredicates,
    }

    const presentationRequest = PresentationRequest.fromJson(presReqJson as JsonObject)
    const credential = Credential.fromJson(input.credentialJson as JsonObject)
    const schema = Schema.fromJson(schemaRow as unknown as JsonObject)
    const credDef = credDefRow.credentialDefinition as unknown as JsonObject

    const credentialsProve = Object.keys(presReqRow.requestedAttributes).map((referent) => ({
      entryIndex: 0,
      referent,
      isPredicate: false,
      reveal: true,
    }))

    const predicatesProve = Object.keys(presReqRow.requestedPredicates).map((referent) => ({
      entryIndex: 0,
      referent,
      isPredicate: true,
      reveal: false,
    }))

    const presentation = Presentation.create({
      presentationRequest,
      credentials: [{ credential }],
      credentialsProve: [...credentialsProve, ...predicatesProve],
      selfAttest: {},
      linkSecret: linkSecretRow.value,
      schemas: { [schemaRow.id]: schema },
      credentialDefinitions: { [input.credDefId]: credDef },
    })

    const id = randomUUID()
    // Presentation JSON'ı DB'ye kaydediyoruz — bu ZK proof içerir, ham değer yok
    const presentationJson = presentation.toJson() as Record<string, unknown>

    await savePresentation({
      id,
      presentationRequestId: input.presentationRequestId,
      heldCredentialId: input.heldCredentialId,
      presentation: presentationJson,
    })

    return { id }
  }
}
