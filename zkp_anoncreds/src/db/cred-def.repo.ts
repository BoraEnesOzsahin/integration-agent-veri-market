import pool from './pool'
import {
  CredentialDefinitionModel,
  CredentialDefinitionPrivateModel,
  KeyCorrectnessProofModel,
} from '../anoncreds/anoncred-model'

export interface CredDefRow {
  credentialDefinition: CredentialDefinitionModel
  credentialDefinitionPrivate: CredentialDefinitionPrivateModel
  keyCorrectnessProof: KeyCorrectnessProofModel | Record<string, unknown>
  createdAt: string
  updatedAt: string
}

const map = (r: any): CredDefRow => ({
  credentialDefinition: {
    id: r.id,
    schemaId: r.schema_id,
    issuerId: r.issuer_id,
    tag: r.tag,
    type: r.type,
    value: r.value,
  },
  credentialDefinitionPrivate: r.private,
  keyCorrectnessProof: r.key_correctness_proof,
  createdAt: r.created_at,
  updatedAt: r.updated_at,
})

export async function saveCredentialDefinition(
  credDef: CredentialDefinitionModel,
  credDefPrivate: CredentialDefinitionPrivateModel,
  keyCorrectnessProof: KeyCorrectnessProofModel | Record<string, unknown>,
): Promise<void> {
  await pool.query(
    `INSERT INTO credential_definitions
       (id, schema_id, issuer_id, tag, type, value, private, key_correctness_proof)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
     ON CONFLICT (id) DO NOTHING`,
    [
      credDef.id,
      credDef.schemaId,
      credDef.issuerId,
      credDef.tag,
      credDef.type,
      JSON.stringify(credDef.value),
      JSON.stringify(credDefPrivate),
      JSON.stringify(keyCorrectnessProof),
    ],
  )
}

export async function getAllCredentialDefinitions(): Promise<CredDefRow[]> {
  const { rows } = await pool.query(`SELECT * FROM credential_definitions ORDER BY created_at DESC`)
  return rows.map(map)
}

export async function getCredentialDefinitionById(id: string): Promise<CredDefRow | null> {
  const { rows } = await pool.query(`SELECT * FROM credential_definitions WHERE id=$1`, [id])
  return rows[0] ? map(rows[0]) : null
}
