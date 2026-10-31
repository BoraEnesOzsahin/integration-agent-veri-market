import pool from './pool'

export async function runMigrations(): Promise<void> {
  const client = await pool.connect()
  try {
    // Auto-update trigger function
    await client.query(`
      CREATE OR REPLACE FUNCTION set_updated_at()
      RETURNS TRIGGER AS $$
      BEGIN
        NEW.updated_at = NOW();
        RETURN NEW;
      END;
      $$ LANGUAGE plpgsql;
    `)

    // Tables
    await client.query(`
      CREATE TABLE IF NOT EXISTS schemas (
        id          TEXT PRIMARY KEY,
        issuer_id   TEXT        NOT NULL,
        name        TEXT        NOT NULL,
        version     TEXT        NOT NULL,
        attr_names  TEXT[]      NOT NULL,
        created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      CREATE TABLE IF NOT EXISTS credential_definitions (
        id                    TEXT PRIMARY KEY,
        schema_id             TEXT    NOT NULL REFERENCES schemas(id),
        issuer_id             TEXT    NOT NULL,
        tag                   TEXT    NOT NULL,
        type                  TEXT    NOT NULL,
        value                 JSONB   NOT NULL,
        private               JSONB   NOT NULL,
        key_correctness_proof JSONB   NOT NULL,
        created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      CREATE TABLE IF NOT EXISTS wallets (
        id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        label      TEXT NOT NULL UNIQUE,
        did        TEXT NOT NULL UNIQUE,
        verkey     TEXT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      CREATE TABLE IF NOT EXISTS link_secrets (
        id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        label      TEXT NOT NULL UNIQUE,
        value      TEXT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      CREATE TABLE IF NOT EXISTS credential_offers (
        id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        cred_def_id TEXT NOT NULL REFERENCES credential_definitions(id),
        offer       JSONB NOT NULL,
        created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      CREATE TABLE IF NOT EXISTS credential_requests (
        id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        offer_id       UUID NOT NULL REFERENCES credential_offers(id),
        link_secret_id UUID NOT NULL REFERENCES link_secrets(id),
        cred_def_id    TEXT NOT NULL,
        request        JSONB NOT NULL,
        metadata       JSONB NOT NULL,
        created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      CREATE TABLE IF NOT EXISTS issued_credentials (
        id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        credential_request_id UUID NOT NULL REFERENCES credential_requests(id),
        cred_def_id           TEXT NOT NULL,
        schema_id             TEXT NOT NULL,
        credential            JSONB NOT NULL,
        created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      CREATE TABLE IF NOT EXISTS held_credentials (
        id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        issued_credential_id UUID NOT NULL REFERENCES issued_credentials(id),
        link_secret_id       UUID NOT NULL REFERENCES link_secrets(id),
        cred_def_id          TEXT NOT NULL,
        schema_id            TEXT NOT NULL,
        -- credential verisi holder'ın local wallet'ında tutulur, DB'de saklanmaz
        created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      CREATE TABLE IF NOT EXISTS presentation_requests (
        id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        cred_def_id           TEXT NOT NULL,
        requested_attributes  JSONB NOT NULL,
        requested_predicates  JSONB NOT NULL DEFAULT '{}',
        nonce                 TEXT NOT NULL,
        name                  TEXT NOT NULL,
        version               TEXT NOT NULL DEFAULT '1.0',
        created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      CREATE TABLE IF NOT EXISTS presentations (
        id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        presentation_request_id UUID NOT NULL REFERENCES presentation_requests(id),
        held_credential_id      UUID NOT NULL REFERENCES held_credentials(id),
        presentation            JSONB NOT NULL,
        verified                BOOLEAN,
        created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );
    `)

    // held_credentials: eski sürümde credential kolonu varsa kaldır (ZKP privacy fix)
    await client.query(`
      ALTER TABLE held_credentials DROP COLUMN IF EXISTS credential;
    `)

    // Add updated_at to existing tables that may be missing it
    const tables = [
      'schemas', 'credential_definitions', 'wallets', 'link_secrets',
      'credential_offers', 'credential_requests', 'issued_credentials',
      'held_credentials', 'presentation_requests', 'presentations',
    ]
    for (const table of tables) {
      await client.query(`
        ALTER TABLE ${table} ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
      `)
    }

    // Create updated_at triggers (DROP + CREATE to be idempotent)
    for (const table of tables) {
      await client.query(`
        DROP TRIGGER IF EXISTS trg_${table}_updated_at ON ${table};
        CREATE TRIGGER trg_${table}_updated_at
          BEFORE UPDATE ON ${table}
          FOR EACH ROW EXECUTE FUNCTION set_updated_at();
      `)
    }

    console.log('[DB] Tables ready.')
  } finally {
    client.release()
  }
}

export async function clearAllData(): Promise<void> {
  const client = await pool.connect()
  try {
    await client.query(`
      TRUNCATE TABLE
        presentations,
        presentation_requests,
        held_credentials,
        issued_credentials,
        credential_requests,
        credential_offers,
        link_secrets,
        credential_definitions,
        wallets,
        schemas
      RESTART IDENTITY CASCADE;
    `)
    console.log('[DB] Tüm veriler temizlendi.')
  } finally {
    client.release()
  }
}
