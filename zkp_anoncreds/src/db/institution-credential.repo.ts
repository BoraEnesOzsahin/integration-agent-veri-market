import pool from './pool'

export interface InstitutionCredentialRow {
  key: string
  institutionId: string
  tableName: string
  did: string
  schemaId: string
  credDefId: string
  linkSecretId: string
  heldCredentialId: string
  credential: Record<string, unknown>
}

function map(r: Record<string, any>): InstitutionCredentialRow {
  return {
    key: r.key,
    institutionId: r.institution_id,
    tableName: r.table_name,
    did: r.did,
    schemaId: r.schema_id,
    credDefId: r.cred_def_id,
    linkSecretId: r.link_secret_id,
    heldCredentialId: r.held_credential_id,
    credential: r.credential,
  }
}

export async function getInstitutionCredentialByKey(key: string): Promise<InstitutionCredentialRow | null> {
  const { rows } = await pool.query(`SELECT * FROM institution_credentials WHERE key=$1`, [key])
  return rows[0] ? map(rows[0]) : null
}

/** En son yayınlanan belge geçerlidir. */
export async function getLatestInstitutionCredential(institutionId: string, tableName: string): Promise<InstitutionCredentialRow | null> {
  const { rows } = await pool.query(
    `SELECT * FROM institution_credentials WHERE institution_id=$1 AND table_name=$2 ORDER BY updated_at DESC LIMIT 1`,
    [institutionId, tableName],
  )
  return rows[0] ? map(rows[0]) : null
}

export async function getInstitutionDid(institutionId: string): Promise<string | null> {
  const { rows } = await pool.query(`SELECT did FROM institution_credentials WHERE institution_id=$1 LIMIT 1`, [institutionId])
  return rows[0]?.did ?? null
}

export async function saveInstitutionCredential(row: InstitutionCredentialRow): Promise<void> {
  await pool.query(
    `INSERT INTO institution_credentials (key, institution_id, table_name, did, schema_id, cred_def_id, link_secret_id, held_credential_id, credential)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
     ON CONFLICT (key) DO UPDATE SET did=$4, schema_id=$5, cred_def_id=$6, link_secret_id=$7, held_credential_id=$8, credential=$9, updated_at=NOW()`,
    [row.key, row.institutionId, row.tableName, row.did, row.schemaId, row.credDefId, row.linkSecretId, row.heldCredentialId, JSON.stringify(row.credential)],
  )
}

/** Kanıt çağırana verildikten sonra saklanmaz: sorgular veritabanında kayıt biriktirmesin. */
export async function deleteProofRecords(presentationId: string | null, presentationRequestId: string): Promise<void> {
  if (presentationId) {
    await pool.query(`DELETE FROM presentations WHERE id=$1`, [presentationId])
  }
  await pool.query(`DELETE FROM presentation_requests WHERE id=$1`, [presentationRequestId])
}

export interface PredicateCommitmentRow {
  value: string
  salt: string
  commitment: string
}

export async function savePredicateCommitment(institutionId: string, tableName: string, metric: string, row: PredicateCommitmentRow): Promise<void> {
  await pool.query(
    `INSERT INTO predicate_commitments (institution_id, table_name, metric, value, salt, commitment)
     VALUES ($1,$2,$3,$4,$5,$6)
     ON CONFLICT (institution_id, table_name, metric) DO UPDATE SET value=$4, salt=$5, commitment=$6, updated_at=NOW()`,
    [institutionId, tableName, metric, row.value, row.salt, row.commitment],
  )
}

export async function getPredicateCommitment(institutionId: string, tableName: string, metric: string): Promise<PredicateCommitmentRow | null> {
  const { rows } = await pool.query(
    `SELECT value, salt, commitment FROM predicate_commitments WHERE institution_id=$1 AND table_name=$2 AND metric=$3`,
    [institutionId, tableName, metric],
  )
  return rows[0] ?? null
}
