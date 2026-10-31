import { CredentialDefinition, JsonObject, Schema } from '@hyperledger/anoncreds-nodejs'
import {
  CredentialDefinitionModel,
  CredentialDefinitionPrivateModel,
  KeyCorrectnessProofModel,
} from './anoncred-model'
import { getSchemaById } from '../db/schema.repo'

export interface CreateCredentialDefinitionInput {
  schemaId: string
  issuerId: string
  tag: string
  signatureType?: string
  supportRevocation?: boolean
}

export interface CreateCredentialDefinitionResult {
  credentialDefinition: CredentialDefinition
  credentialDefinitionPrivate: unknown
  keyCorrectnessProof: unknown
  credentialDefinitionJson: CredentialDefinitionModel
  credentialDefinitionPrivateJson: CredentialDefinitionPrivateModel
  keyCorrectnessProofJson: KeyCorrectnessProofModel | Record<string, unknown>
}

export class CredentialDefinitionService {
  async createCredentialDefinition(input: CreateCredentialDefinitionInput): Promise<CreateCredentialDefinitionResult> {
    this.validateInput(input)

    const schema = await getSchemaById(input.schemaId);

    if (!schema) {
      throw new Error(`Schema not found: ${input.schemaId}`);
    }

    const result = CredentialDefinition.create({
      schemaId: input.schemaId,
      schema: Schema.fromJson(schema as unknown as JsonObject),
      issuerId: input.issuerId,
      tag: input.tag,
      signatureType: input.signatureType ?? 'CL',
      supportRevocation: input.supportRevocation ?? false,
    })

    const credDefRaw = result.credentialDefinition.toJson() as Record<string, unknown>
    const credDefPrivateRaw =
      (result.credentialDefinitionPrivate as { toJson?: () => unknown }).toJson?.() ?? {}
    const keyProofRaw =
      (result.keyCorrectnessProof as { toJson?: () => unknown }).toJson?.() ?? {}

    const credentialDefinitionJson: CredentialDefinitionModel = {
      id: `${input.issuerId}/cred-def/${input.tag}`,
      schemaId: String(credDefRaw.schemaId),
      issuerId: String(credDefRaw.issuerId),
      tag: String(credDefRaw.tag),
      type: String(credDefRaw.type),
      value: this.asRecord(credDefRaw.value),
    }

    const credentialDefinitionPrivateJson: CredentialDefinitionPrivateModel = {
      value: this.asRecord((credDefPrivateRaw as Record<string, unknown>).value),
    }

    return {
      credentialDefinition: result.credentialDefinition,
      credentialDefinitionPrivate: result.credentialDefinitionPrivate,
      keyCorrectnessProof: result.keyCorrectnessProof,
      credentialDefinitionJson,
      credentialDefinitionPrivateJson,
      keyCorrectnessProofJson: this.mapKeyCorrectnessProof(keyProofRaw),
    }
  }

  private validateInput(input: CreateCredentialDefinitionInput): void {
    if (!input.schemaId?.trim()) throw new Error('schemaId is required.')
    if (!input.issuerId?.trim()) throw new Error('issuerId is required.')
    if (!input.tag?.trim()) throw new Error('tag is required.')
  }

  private asRecord(value: unknown): Record<string, unknown> {
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      return value as Record<string, unknown>
    }
    return {}
  }

  private mapKeyCorrectnessProof(raw: unknown): KeyCorrectnessProofModel | Record<string, unknown> {
    const obj = this.asRecord(raw)
    if (!obj.c || !obj.xzCap || !Array.isArray(obj.xrCap)) return obj
    return {
      c: String(obj.c),
      xzCap: String(obj.xzCap),
      xrCap: (obj.xrCap as unknown[]).map((item) => {
        if (Array.isArray(item) && item.length >= 2) return [String(item[0]), String(item[1])]
        return ['', '']
      }),
    }
  }
}
