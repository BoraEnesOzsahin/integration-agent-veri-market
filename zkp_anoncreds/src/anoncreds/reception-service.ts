import { randomUUID } from 'crypto'
import { Credential, CredentialRequestMetadata } from '@hyperledger/anoncreds-nodejs'
import type { JsonObject } from '@hyperledger/anoncreds-shared'
import { getCredentialDefinitionById } from '../db/cred-def.repo'
import { getIssuedCredentialById } from '../db/issued-credential.repo'
import { getLinkSecretById } from '../db/link-secret.repo'
import { getCredentialRequestByIssuedCredentialId } from '../db/credential-request.repo'
import { saveHeldCredential } from '../db/held-credential.repo'
import { getSchemaById } from '../db/schema.repo'

export interface ReceiveCredentialInput {
  issuedCredentialId: string
  linkSecretId: string
}

export interface ReceiveCredentialResult {
  id: string
  credDefId: string
  schemaId: string
  // Processed credential JSON — holder'ın local wallet'ında saklanır, DB'ye yazılmaz
  credentialJson: Record<string, unknown>
  attributes: Record<string, string>
}

export class ReceptionService {
  async receive(input: ReceiveCredentialInput): Promise<ReceiveCredentialResult> {
    if (!input.issuedCredentialId?.trim()) throw new Error('issuedCredentialId is required.')
    if (!input.linkSecretId?.trim()) throw new Error('linkSecretId is required.')

    const issuedRow = await getIssuedCredentialById(input.issuedCredentialId)
    if (!issuedRow) throw new Error(`Issued credential not found: ${input.issuedCredentialId}`)

    const linkSecretRow = await getLinkSecretById(input.linkSecretId)
    if (!linkSecretRow) throw new Error(`Link secret not found: ${input.linkSecretId}`)

    const credDefRow = await getCredentialDefinitionById(issuedRow.credDefId)
    if (!credDefRow) throw new Error(`Credential definition not found: ${issuedRow.credDefId}`)

    const requestRow = await getCredentialRequestByIssuedCredentialId(input.issuedCredentialId)
    if (!requestRow) throw new Error('Could not find credential request for this issued credential.')

    await getSchemaById(issuedRow.schemaId) // validate schema exists

    const issued = Credential.fromJson(issuedRow.credential as JsonObject)
    const metadata = CredentialRequestMetadata.fromJson(requestRow.metadata as JsonObject)

    const processed = issued.process({
      credentialRequestMetadata: metadata,
      linkSecret: linkSecretRow.value,
      credentialDefinition: credDefRow.credentialDefinition as unknown as JsonObject,
    })

    // Merge to preserve schema_id/cred_def_id fields that Rust process() may drop
    const credentialJson = { ...issuedRow.credential, ...(processed.toJson() as Record<string, unknown>) }

    const id = randomUUID()

    // DB'ye sadece metadata kaydedilir — ham credential verisi burada YOK
    await saveHeldCredential({
      id,
      issuedCredentialId: input.issuedCredentialId,
      linkSecretId: input.linkSecretId,
      credDefId: issuedRow.credDefId,
      schemaId: issuedRow.schemaId,
    })

    const valuesRaw = (credentialJson.values ?? (issuedRow.credential as Record<string, unknown>).values) as
      | Record<string, { raw: string }>
      | undefined
    const attributes: Record<string, string> = {}
    if (valuesRaw) {
      for (const [k, v] of Object.entries(valuesRaw)) {
        attributes[k] = v.raw
      }
    }

    // credentialJson holder'ın local wallet'ına kaydedilmek üzere döndürülür
    return { id, credDefId: issuedRow.credDefId, schemaId: issuedRow.schemaId, credentialJson, attributes }
  }
}
