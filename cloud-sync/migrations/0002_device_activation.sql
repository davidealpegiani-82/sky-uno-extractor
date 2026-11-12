ALTER TABLE devices ADD COLUMN display_name TEXT;
ALTER TABLE devices ADD COLUMN activated_at INTEGER;
ALTER TABLE devices ADD COLUMN revoked_at INTEGER;
ALTER TABLE devices ADD COLUMN lease_expires_at INTEGER;
CREATE INDEX IF NOT EXISTS idx_devices_install_id ON devices(install_id);

CREATE TABLE IF NOT EXISTS activation_codes (
  code_hash TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed_at INTEGER,
  consumed_install_id TEXT
);
CREATE INDEX IF NOT EXISTS idx_activation_codes_expiry ON activation_codes(expires_at, consumed_at);
