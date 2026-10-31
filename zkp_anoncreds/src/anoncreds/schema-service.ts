import { Schema } from '@hyperledger/anoncreds-nodejs'
import { SchemaModel } from './anoncred-model'
import { saveSchema } from '../db/schema.repo'

export interface CreateSchemaInput {
  issuerId: string
  name: string
  version: string
  attributeNames: string[]
}

export class SchemaService {
  async createSchema(input: CreateSchemaInput): Promise<{ schema: Schema; schemaJson: SchemaModel} > {
    this.validateInput(input)

    const schema = Schema.create({
      issuerId: input.issuerId,
      name: input.name,
      version: input.version,
      attributeNames: input.attributeNames,
    })

    const rawJson = schema.toJson() as Record<string, unknown>

    const schemaJson: SchemaModel = {
      id: `${input.issuerId}/schema/${input.name}`,
      name: String(rawJson.name),
      version: String(rawJson.version),
      issuerId: String(rawJson.issuerId),
      attrNames: Array.isArray(rawJson.attrNames) ? rawJson.attrNames.map(String) : [],
    }

    await saveSchema(schemaJson)

    return { schema, schemaJson }
  }

  private validateInput(input: CreateSchemaInput): void {
    if (!input.issuerId?.trim()) throw new Error('issuerId is required.')
    if (!input.name?.trim()) throw new Error('Schema name is required.')
    if (!input.version?.trim()) throw new Error('Schema version is required.')
    if (!Array.isArray(input.attributeNames) || input.attributeNames.length === 0) {
      throw new Error('attributeNames must contain at least one field.')
    }
    const unique = new Set(input.attributeNames)
    if (unique.size !== input.attributeNames.length) {
      throw new Error('attributeNames must not contain duplicate field names.')
    }
  }
}
