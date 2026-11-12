PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  install_id TEXT NOT NULL,
  device_name TEXT NOT NULL,
  platform TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'revoked', 'expired')),
  pairing_code TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  approved_at INTEGER,
  last_seen_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_devices_pairing_code ON devices(pairing_code, status, expires_at);
CREATE INDEX IF NOT EXISTS idx_devices_token_hash ON devices(token_hash, status);

CREATE TABLE IF NOT EXISTS config (
  config_id INTEGER PRIMARY KEY CHECK (config_id = 1),
  version INTEGER NOT NULL,
  update_id TEXT NOT NULL,
  payload TEXT NOT NULL,
  checksum TEXT NOT NULL,
  published_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS pair_attempts (
  attempt_id INTEGER PRIMARY KEY AUTOINCREMENT,
  ip_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pair_attempts_ip_time ON pair_attempts(ip_hash, created_at);
