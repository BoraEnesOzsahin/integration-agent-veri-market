import pool from './pool'
import { SchemaModel } from '../anoncreds/anoncred-model'

export interface SchemaRow extends SchemaModel {
  createdAt: string
  updatedAt: string
}

const map = (r: any): SchemaRow => ({
  id: r.id,
  issuerId: r.issuer_id,
  name: r.name,
  version: r.version,
  attrNames: r.attr_names,
  createdAt: r.created_at,
  updatedAt: r.updated_at,
})

export async function saveSchema(schema: SchemaModel): Promise<void> {
  await pool.query(
    `INSERT INTO schemas (id, issuer_id, name, version, attr_names)
     VALUES ($1, $2, $3, $4, $5)
     ON CONFLICT (id) DO NOTHING`,
    [schema.id, schema.issuerId, schema.name, schema.version, schema.attrNames],
  )
}

export async function getAllSchemas(): Promise<SchemaRow[]> {
  const { rows } = await pool.query(`SELECT * FROM schemas ORDER BY created_at DESC`)
  return rows.map(map)
}

export async function getSchemaById(id: string): Promise<SchemaRow | null> {
  const { rows } = await pool.query(`SELECT * FROM schemas WHERE id=$1`, [id])
  return rows[0] ? map(rows[0]) : null
}
