import { randomUUID } from 'crypto'
import { Nonce } from '@hyperledger/anoncreds-shared'
import { getCredentialDefinitionById } from '../db/cred-def.repo'
import { getSchemaById } from '../db/schema.repo'
import { savePresentationRequest } from '../db/presentation-request.repo'

export interface CreatePresentationRequestInput {
  credDefId: string
  revealedAttributes?: string[]
  predicates?: Array<{ name: string; predicateType: '>=' | '<=' | '>' | '<'; predicateValue: number }>
  name?: string
  /** Doğrulayıcının (koordinatör) belirlediği onluk tabanda nonce. Verilmezse üretilir. Replay'e karşı bağlama için kullanılır. */
  nonce?: string
}

export interface CreatePresentationRequestResult {
  id: string
  credDefId: string
  name: string
  requestedAttributes: Record<string, { name: string }>
  requestedPredicates: Record<string, unknown>
  nonce: string
}

export class PresentationRequestService {
  async createRequest(input: CreatePresentationRequestInput): Promise<CreatePresentationRequestResult> {
    if (!input.credDefId?.trim()) throw new Error('credDefId is required.')

    const credDefRow = await getCredentialDefinitionById(input.credDefId)
    if (!credDefRow) throw new Error(`Credential definition not found: ${input.credDefId}`)

    const schemaRow = await getSchemaById(credDefRow.credentialDefinition.schemaId)
    if (!schemaRow) throw new Error(`Schema not found: ${credDefRow.credentialDefinition.schemaId}`)

    // Predicate attribute names — these must NOT appear in requestedAttributes
    const predicateAttrNames = new Set((input.predicates ?? []).map(p => p.name))

    for (const pred of input.predicates ?? []) {
      if (!schemaRow.attrNames.includes(pred.name)) {
        throw new Error(`Predicate attribute '${pred.name}' not found in schema.`)
      }
    }

    // Default reveal = all attrs except predicate attrs. Explicit list is also filtered.
    const baseReveal = input.revealedAttributes ?? schemaRow.attrNames
    const toReveal = baseReveal.filter(a => !predicateAttrNames.has(a))

    for (const attr of toReveal) {
      if (!schemaRow.attrNames.includes(attr)) {
        throw new Error(`Attribute '${attr}' not found in schema. Available: ${schemaRow.attrNames.join(', ')}`)
      }
    }

    // Need at least one revealed attr OR at least one predicate
    if (toReveal.length === 0 && predicateAttrNames.size === 0) {
      throw new Error('At least one attribute or predicate must be requested.')
    }

    const requestedAttributes: Record<string, { name: string }> = {}
    for (const attr of toReveal) {
      requestedAttributes[`attr_${attr}`] = { name: attr }
    }

    const requestedPredicates: Record<string, unknown> = {}
    for (const pred of input.predicates ?? []) {
      requestedPredicates[`pred_${pred.name}`] = {
        name: pred.name,
        p_type: pred.predicateType,
        p_value: pred.predicateValue,
      }
    }

    if (input.nonce !== undefined && !/^\d{1,24}$/.test(input.nonce)) {
      throw new Error('nonce must be a decimal string of at most 24 digits.')
    }
    const nonce = input.nonce ?? Nonce.generate()
    const name = input.name ?? (input.revealedAttributes ? 'Selective Disclosure' : 'Full Disclosure')
    const id = randomUUID()

    await savePresentationRequest({
      id,
      credDefId: input.credDefId,
      requestedAttributes,
      requestedPredicates,
      nonce,
      name,
      version: '1.0',
    })

    return { id, credDefId: input.credDefId, name, requestedAttributes, requestedPredicates, nonce }
  }
}
