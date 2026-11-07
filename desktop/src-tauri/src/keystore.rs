// OS-backed storage for the Remember Me encryption key.
//
// The 32-byte AES-256-GCM key never touches the filesystem. It lives in the platform credential
// store (Windows Credential Manager, macOS Keychain, Linux Secret Service) and is handed to the
// Node sidecar over its private stdin pipe. If the store is unavailable the sidecar is told so and
// Remember Me is disabled; there is deliberately no file or in-memory fallback.

use std::fmt;

pub const KEYSTORE_SERVICE: &str = "md.sime.opensime";
pub const KEYSTORE_ACCOUNT: &str = "remember-key-v1";
pub const KEY_LEN: usize = 32;

pub const CONTROL_KEY_PREFIX: &str = "SIME_KEY ";
pub const CONTROL_UNAVAILABLE_PREFIX: &str = "SIME_KEY_UNAVAILABLE ";
pub const CONTROL_ROTATE_REQUEST: &str = "SIME_KEY_ROTATE";
pub const CONTROL_ROTATED_PREFIX: &str = "SIME_KEY_ROTATED ";
pub const CONTROL_ROTATE_ERROR_PREFIX: &str = "SIME_KEY_ROTATE_ERROR ";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeystoreError {
  /// No usable credential store (no Secret Service on Linux, platform failure, unsupported OS).
  Unavailable,
  /// A store exists but refused access (locked, user denied).
  AccessDenied,
  /// A stored entry exists but is not a valid 32-byte key.
  Corrupt,
  /// Any other failure, including failing to obtain secure random bytes.
  Failed,
}

impl KeystoreError {
  pub fn code(self) -> &'static str {
    match self {
      KeystoreError::Unavailable => "KEYSTORE_UNAVAILABLE",
      KeystoreError::AccessDenied => "KEYSTORE_ACCESS_DENIED",
      KeystoreError::Corrupt => "KEYSTORE_CORRUPT",
      KeystoreError::Failed => "KEYSTORE_FAILED",
    }
  }
}

impl fmt::Display for KeystoreError {
  fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
    f.write_str(self.code())
  }
}

pub trait SecretBackend {
  fn get(&self) -> Result<Option<Vec<u8>>, KeystoreError>;
  fn set(&self, secret: &[u8]) -> Result<(), KeystoreError>;
  fn delete(&self) -> Result<(), KeystoreError>;
}

/// Fills the buffer with cryptographically secure random bytes; false on failure.
pub type RandomFill = dyn Fn(&mut [u8]) -> bool;

pub fn os_random_fill(buf: &mut [u8]) -> bool {
  getrandom::fill(buf).is_ok()
}

fn generate_key(rng: &RandomFill) -> Result<[u8; KEY_LEN], KeystoreError> {
  let mut key = [0u8; KEY_LEN];
  if !rng(&mut key) || key.iter().all(|b| *b == 0) {
    return Err(KeystoreError::Failed);
  }
  Ok(key)
}

/// Returns the stored key, creating and storing a new random one when none exists.
/// A malformed stored entry is reported as `Corrupt` and is never returned or silently reused.
pub fn load_or_create_key(
  backend: &dyn SecretBackend,
  rng: &RandomFill,
) -> Result<[u8; KEY_LEN], KeystoreError> {
  match backend.get()? {
    Some(bytes) => {
      let key: [u8; KEY_LEN] = bytes.as_slice().try_into().map_err(|_| KeystoreError::Corrupt)?;
      Ok(key)
    }
    None => {
      let key = generate_key(rng)?;
      backend.set(&key)?;
      Ok(key)
    }
  }
}

/// Startup policy: a corrupt entry cannot decrypt anything, so it is replaced by a fresh key.
/// Remember Me tokens encrypted under the old entry then fail authentication and are discarded.
pub fn provision_key(
  backend: &dyn SecretBackend,
  rng: &RandomFill,
) -> Result<[u8; KEY_LEN], KeystoreError> {
  match load_or_create_key(backend, rng) {
    Err(KeystoreError::Corrupt) => rotate_key(backend, rng),
    other => other,
  }
}

/// Replaces the stored key with a fresh random one. The previous key is destroyed.
pub fn rotate_key(
  backend: &dyn SecretBackend,
  rng: &RandomFill,
) -> Result<[u8; KEY_LEN], KeystoreError> {
  let key = generate_key(rng)?;
  if let Err(err) = backend.set(&key) {
    // Never leave the old key behind as the only copy if we could not store the new one.
    let _ = backend.delete();
    return Err(err);
  }
  Ok(key)
}

pub fn revoke_key(backend: &dyn SecretBackend) -> Result<(), KeystoreError> {
  backend.delete()
}

fn to_hex(bytes: &[u8]) -> String {
  const HEX: &[u8; 16] = b"0123456789abcdef";
  let mut out = String::with_capacity(bytes.len() * 2);
  for b in bytes {
    out.push(HEX[(b >> 4) as usize] as char);
    out.push(HEX[(b & 0x0f) as usize] as char);
  }
  out
}

pub fn format_key_line(key: &[u8; KEY_LEN]) -> String {
  format!("{}{}\n", CONTROL_KEY_PREFIX, to_hex(key))
}

pub fn format_unavailable_line(err: KeystoreError) -> String {
  format!("{}{}\n", CONTROL_UNAVAILABLE_PREFIX, err.code())
}

pub fn format_provision_line(seq: u64, key: &[u8; KEY_LEN]) -> String {
  format!("{} {} {}\n", CONTROL_KEY_PREFIX.trim_end(), seq, to_hex(key))
}

pub fn format_provision_unavailable_line(seq: u64, err: KeystoreError) -> String {
  format!("{} {} {}\n", CONTROL_UNAVAILABLE_PREFIX.trim_end(), seq, err.code())
}

pub fn format_rotated_line(req_id: &str, key: &[u8; KEY_LEN]) -> String {
  format!("{} {} {}\n", CONTROL_ROTATED_PREFIX.trim_end(), req_id, to_hex(key))
}

pub fn format_rotate_error_line(req_id: &str, err: KeystoreError) -> String {
  format!("{} {} {}\n", CONTROL_ROTATE_ERROR_PREFIX.trim_end(), req_id, err.code())
}

pub fn parse_rotate_request(line: &str) -> Option<String> {
  let trimmed = line.trim();
  if trimmed == CONTROL_ROTATE_REQUEST {
    return Some("0".to_string());
  }
  if let Some(rest) = trimmed.strip_prefix(CONTROL_ROTATE_REQUEST) {
    if rest.starts_with(' ') || rest.starts_with('\t') {
      let id = rest.trim();
      if !id.is_empty() && id.chars().all(|c| c.is_alphanumeric() || c == '_' || c == '-') {
        return Some(id.to_string());
      }
    }
  }
  None
}

pub fn is_rotate_request(line: &str) -> bool {
  parse_rotate_request(line).is_some()
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum KeystoreJob {
  Provision { seq: u64 },
  Rotate { req_id: String, seq: u64 },
}

pub struct KeystoreCoordinator<B: SecretBackend> {
  backend: B,
  highest_seq: u64,
  has_rotated: bool,
}

impl<B: SecretBackend> KeystoreCoordinator<B> {
  pub fn new(backend: B) -> Self {
    Self {
      backend,
      highest_seq: 0,
      has_rotated: false,
    }
  }

  pub fn has_rotated(&self) -> bool {
    self.has_rotated
  }

  pub fn highest_seq(&self) -> u64 {
    self.highest_seq
  }

  pub fn handle_job(
    &mut self,
    job: KeystoreJob,
    rng: &RandomFill,
  ) -> Option<String> {
    match job {
      KeystoreJob::Provision { seq } => {
        if seq <= self.highest_seq || self.has_rotated {
          return None;
        }
        self.highest_seq = seq;
        let result = provision_key(&self.backend, rng);
        if self.has_rotated {
          return None;
        }
        match result {
          Ok(key) => Some(format_provision_line(seq, &key)),
          Err(err) => Some(format_provision_unavailable_line(seq, err)),
        }
      }
      KeystoreJob::Rotate { req_id, seq } => {
        self.has_rotated = true;
        self.highest_seq = seq.max(self.highest_seq);
        let result = rotate_key(&self.backend, rng);
        match result {
          Ok(key) => Some(format_rotated_line(&req_id, &key)),
          Err(err) => Some(format_rotate_error_line(&req_id, err)),
        }
      }
    }
  }
}

#[cfg(any(windows, target_os = "macos", target_os = "linux"))]
pub struct OsBackend;

#[cfg(any(windows, target_os = "macos", target_os = "linux"))]
impl OsBackend {
  fn entry() -> Result<keyring::Entry, KeystoreError> {
    keyring::Entry::new(KEYSTORE_SERVICE, KEYSTORE_ACCOUNT).map_err(map_keyring_error)
  }
}

#[cfg(any(windows, target_os = "macos", target_os = "linux"))]
fn map_keyring_error(err: keyring::Error) -> KeystoreError {
  match err {
    keyring::Error::NoStorageAccess(_) => KeystoreError::AccessDenied,
    keyring::Error::PlatformFailure(_) => KeystoreError::Unavailable,
    keyring::Error::BadEncoding(_) => KeystoreError::Corrupt,
    _ => KeystoreError::Failed,
  }
}

#[cfg(any(windows, target_os = "macos", target_os = "linux"))]
impl SecretBackend for OsBackend {
  fn get(&self) -> Result<Option<Vec<u8>>, KeystoreError> {
    match Self::entry()?.get_secret() {
      Ok(bytes) => Ok(Some(bytes)),
      Err(keyring::Error::NoEntry) => Ok(None),
      Err(err) => Err(map_keyring_error(err)),
    }
  }

  fn set(&self, secret: &[u8]) -> Result<(), KeystoreError> {
    Self::entry()?.set_secret(secret).map_err(map_keyring_error)
  }

  fn delete(&self) -> Result<(), KeystoreError> {
    match Self::entry()?.delete_credential() {
      Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
      Err(err) => Err(map_keyring_error(err)),
    }
  }
}

#[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
pub struct OsBackend;

#[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
impl SecretBackend for OsBackend {
  fn get(&self) -> Result<Option<Vec<u8>>, KeystoreError> {
    Err(KeystoreError::Unavailable)
  }
  fn set(&self, _secret: &[u8]) -> Result<(), KeystoreError> {
    Err(KeystoreError::Unavailable)
  }
  fn delete(&self) -> Result<(), KeystoreError> {
    Err(KeystoreError::Unavailable)
  }
}

#[cfg(test)]
mod tests {
  use super::*;
  use std::cell::RefCell;

  #[derive(Default)]
  struct MemoryBackend {
    secret: RefCell<Option<Vec<u8>>>,
    fail_get: Option<KeystoreError>,
    fail_set: Option<KeystoreError>,
  }

  impl SecretBackend for MemoryBackend {
    fn get(&self) -> Result<Option<Vec<u8>>, KeystoreError> {
      if let Some(err) = self.fail_get {
        return Err(err);
      }
      Ok(self.secret.borrow().clone())
    }
    fn set(&self, secret: &[u8]) -> Result<(), KeystoreError> {
      if let Some(err) = self.fail_set {
        return Err(err);
      }
      *self.secret.borrow_mut() = Some(secret.to_vec());
      Ok(())
    }
    fn delete(&self) -> Result<(), KeystoreError> {
      *self.secret.borrow_mut() = None;
      Ok(())
    }
  }

  fn counting_rng(seed: u8) -> impl Fn(&mut [u8]) -> bool {
    move |buf: &mut [u8]| {
      for (i, b) in buf.iter_mut().enumerate() {
        *b = seed.wrapping_add(i as u8).max(1);
      }
      true
    }
  }

  #[test]
  fn creates_key_once_and_reuses_it() {
    let backend = MemoryBackend::default();
    let first = load_or_create_key(&backend, &counting_rng(1)).unwrap();
    let second = load_or_create_key(&backend, &counting_rng(99)).unwrap();
    assert_eq!(first, second, "an existing key must be reused, not regenerated");
    assert_eq!(backend.secret.borrow().as_deref(), Some(&first[..]));
  }

  #[test]
  fn unavailable_and_denied_stores_surface_distinct_errors_without_fallback() {
    let unavailable = MemoryBackend { fail_get: Some(KeystoreError::Unavailable), ..Default::default() };
    assert_eq!(provision_key(&unavailable, &counting_rng(1)), Err(KeystoreError::Unavailable));
    assert!(unavailable.secret.borrow().is_none(), "no key may be created elsewhere");

    let denied = MemoryBackend { fail_set: Some(KeystoreError::AccessDenied), ..Default::default() };
    assert_eq!(provision_key(&denied, &counting_rng(1)), Err(KeystoreError::AccessDenied));
  }

  #[test]
  fn corrupt_entry_is_reported_then_replaced_at_provisioning() {
    let backend = MemoryBackend::default();
    *backend.secret.borrow_mut() = Some(vec![7u8; 5]);
    assert_eq!(load_or_create_key(&backend, &counting_rng(1)), Err(KeystoreError::Corrupt));
    let key = provision_key(&backend, &counting_rng(3)).unwrap();
    assert_eq!(backend.secret.borrow().as_deref(), Some(&key[..]));
  }

  #[test]
  fn rotation_replaces_key_and_failed_rotation_destroys_the_old_one() {
    let backend = MemoryBackend::default();
    let old = load_or_create_key(&backend, &counting_rng(1)).unwrap();
    let new = rotate_key(&backend, &counting_rng(50)).unwrap();
    assert_ne!(old, new);
    assert_eq!(backend.secret.borrow().as_deref(), Some(&new[..]));

    let failing = MemoryBackend { fail_set: Some(KeystoreError::AccessDenied), ..Default::default() };
    *failing.secret.borrow_mut() = Some(old.to_vec());
    assert_eq!(rotate_key(&failing, &counting_rng(9)), Err(KeystoreError::AccessDenied));
    assert!(failing.secret.borrow().is_none(), "old key must not survive a failed rotation");
  }

  #[test]
  fn revocation_deletes_the_key() {
    let backend = MemoryBackend::default();
    load_or_create_key(&backend, &counting_rng(1)).unwrap();
    revoke_key(&backend).unwrap();
    assert!(backend.secret.borrow().is_none());
  }

  #[test]
  fn rejects_failed_or_all_zero_randomness() {
    let backend = MemoryBackend::default();
    assert_eq!(load_or_create_key(&backend, &|_: &mut [u8]| false), Err(KeystoreError::Failed));
    assert_eq!(load_or_create_key(&backend, &|_: &mut [u8]| true), Err(KeystoreError::Failed));
    assert!(backend.secret.borrow().is_none());
  }

  #[test]
  fn control_lines_match_the_sidecar_protocol() {
    let key = [0xabu8; KEY_LEN];
    let line = format_key_line(&key);
    assert_eq!(line, format!("SIME_KEY {}\n", "ab".repeat(32)));
    assert_eq!(format_provision_line(1, &key), format!("SIME_KEY 1 {}\n", "ab".repeat(32)));
    assert_eq!(format_unavailable_line(KeystoreError::AccessDenied), "SIME_KEY_UNAVAILABLE KEYSTORE_ACCESS_DENIED\n");
    assert_eq!(format_provision_unavailable_line(1, KeystoreError::AccessDenied), "SIME_KEY_UNAVAILABLE 1 KEYSTORE_ACCESS_DENIED\n");
    assert_eq!(format_rotated_line("rot_99", &key), format!("SIME_KEY_ROTATED rot_99 {}\n", "ab".repeat(32)));
    assert_eq!(format_rotate_error_line("rot_99", KeystoreError::Unavailable), "SIME_KEY_ROTATE_ERROR rot_99 KEYSTORE_UNAVAILABLE\n");

    assert_eq!(parse_rotate_request("SIME_KEY_ROTATE\r\n"), Some("0".to_string()));
    assert_eq!(parse_rotate_request("SIME_KEY_ROTATE rot_abc_123"), Some("rot_abc_123".to_string()));
    assert_eq!(parse_rotate_request("SIME_KEY_ROTATE invalid space"), None);
    assert!(is_rotate_request("SIME_KEY_ROTATE\r\n"));
    assert!(is_rotate_request("SIME_KEY_ROTATE rot_456"));
    assert!(!is_rotate_request("SIME_KEY_OTHER"));
  }

  #[test]
  fn coordinator_serializes_and_rejects_stale_provisioning() {
    let backend = MemoryBackend::default();
    let rng = counting_rng(10);
    let mut coordinator = KeystoreCoordinator::new(backend);

    // 1. Initial provision succeeds and emits SIME_KEY 1 <hex>
    let line1 = coordinator.handle_job(KeystoreJob::Provision { seq: 1 }, &rng).unwrap();
    assert!(line1.starts_with("SIME_KEY 1 "));

    // 2. Rotate succeeds and emits SIME_KEY_ROTATED rot_1 <hex>
    let line2 = coordinator.handle_job(KeystoreJob::Rotate { req_id: "rot_1".to_string(), seq: 2 }, &rng).unwrap();
    assert!(line2.starts_with("SIME_KEY_ROTATED rot_1 "));
    assert!(coordinator.has_rotated());

    // 3. Stale or late provision (seq: 1 or newer seq: 3) after rotation is dropped
    assert_eq!(coordinator.handle_job(KeystoreJob::Provision { seq: 1 }, &rng), None);
    assert_eq!(coordinator.handle_job(KeystoreJob::Provision { seq: 3 }, &rng), None);

    // 4. Subsequent rotation succeeds and increments sequence
    let line4 = coordinator.handle_job(KeystoreJob::Rotate { req_id: "rot_2".to_string(), seq: 4 }, &rng).unwrap();
    assert!(line4.starts_with("SIME_KEY_ROTATED rot_2 "));
  }
}
