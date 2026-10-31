-- ZKP AnonCreds Demo — tablo oluşturma scripti
-- Çalıştır: psql -d zkp_demo -f schema.sql
-- veya pgAdmin / DBeaver'da içeriği yapıştır

CREATE TABLE IF NOT EXISTS schemas (
  id          TEXT PRIMARY KEY,
  issuer_id   TEXT        NOT NULL,
  name        TEXT        NOT NULL,
  version     TEXT        NOT NULL,
  attr_names  TEXT[]      NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
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
  created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS wallets (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  label      TEXT NOT NULL UNIQUE,
  did        TEXT NOT NULL UNIQUE,
  verkey     TEXT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS link_secrets (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  label      TEXT NOT NULL UNIQUE,
  value      TEXT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS credential_offers (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  cred_def_id TEXT NOT NULL REFERENCES credential_definitions(id),
  offer       JSONB NOT NULL,
  created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS credential_requests (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  offer_id       UUID NOT NULL REFERENCES credential_offers(id),
  link_secret_id UUID NOT NULL REFERENCES link_secrets(id),
  cred_def_id    TEXT NOT NULL,
  request        JSONB NOT NULL,
  metadata       JSONB NOT NULL,
  created_at     TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS issued_credentials (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  credential_request_id UUID NOT NULL REFERENCES credential_requests(id),
  cred_def_id           TEXT NOT NULL,
  schema_id             TEXT NOT NULL,
  credential            JSONB NOT NULL,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS held_credentials (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  issued_credential_id UUID NOT NULL REFERENCES issued_credentials(id),
  link_secret_id       UUID NOT NULL REFERENCES link_secrets(id),
  cred_def_id          TEXT NOT NULL,
  schema_id            TEXT NOT NULL,
  credential           JSONB NOT NULL,
  created_at           TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS presentation_requests (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  cred_def_id           TEXT NOT NULL,
  requested_attributes  JSONB NOT NULL,
  requested_predicates  JSONB NOT NULL DEFAULT '{}',
  nonce                 TEXT NOT NULL,
  name                  TEXT NOT NULL,
  version               TEXT NOT NULL DEFAULT '1.0',
  created_at            TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS presentations (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  presentation_request_id UUID NOT NULL REFERENCES presentation_requests(id),
  held_credential_id      UUID NOT NULL REFERENCES held_credentials(id),
  presentation            JSONB NOT NULL,
  verified                BOOLEAN,
  created_at              TIMESTAMPTZ DEFAULT NOW()
);
