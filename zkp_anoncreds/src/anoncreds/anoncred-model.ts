import { Credential, Presentation, PresentationRequest, Schema, CredentialDefinition } from '@hyperledger/anoncreds-nodejs'

export class SchemaModel {
  id!: string
  name!: string
  version!: string
  issuerId!: string
  attrNames!: string[]
}

export interface CredentialDefinitionModel {
  id: string
  schemaId: string
  issuerId: string
  tag: string
  type: string
  value: Record<string, unknown>
}

export interface CredentialDefinitionPrivateModel {
  value: Record<string, unknown>
}

export interface KeyCorrectnessProofModel {
  c: string
  xzCap: string
  xrCap: Array<[string, string]>
}

export interface CreateProofInput {
  credential: Credential
  linkSecret: string
  schemaId: string
  schema: Schema
  credentialDefinitionId: string
  credentialDefinition: CredentialDefinition
  presentationRequest: PresentationRequest
}

export interface CreateProofResult {
  presentation: Presentation
  presentationJson: Record<string, unknown>
}
