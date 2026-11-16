#[allow(dead_code)]
mod keystore;

#[allow(unused_imports)]
use serde::Deserialize;
use std::sync::Mutex;
#[allow(unused_imports)]
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::window::Color;
#[allow(unused_imports)]
use tauri::{AppHandle, Manager, RunEvent, Theme, Url, WindowEvent};
use tauri_plugin_shell::process::CommandChild;
#[allow(unused_imports)]
use tauri_plugin_shell::process::CommandEvent;
#[allow(unused_imports)]
use tauri_plugin_shell::ShellExt;

// Signed in-app updates run only in release builds (see `start_update_phase`).
#[cfg(not(debug_assertions))]
use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(not(debug_assertions))]
use std::sync::Arc;
#[cfg(not(debug_assertions))]
use std::time::Instant;
#[cfg(not(debug_assertions))]
use tauri::webview::PageLoadEvent;
#[cfg(not(debug_assertions))]
use tauri_plugin_updater::UpdaterExt;

const SIDECAR_NAME: &str = "binaries/open-sime-server";
const PRODUCTION_HOST: &str = "127.0.0.1";
const PRODUCTION_PORT: u16 = 17321;
const STARTUP_TIMEOUT_SECS: u64 = 15;

// The update check must never hold startup hostage: after this deadline the
// sidecar starts and the app opens normally, whatever the endpoint is doing.
#[cfg(not(debug_assertions))]
const UPDATE_CHECK_TIMEOUT_SECS: u64 = 5;
// A download that stops making progress for this long is abandoned and startup
// continues. Installing is skipped once this fires, so a late download can
// never swap the binary under a running app.
#[cfg(not(debug_assertions))]
const UPDATE_STALL_TIMEOUT_SECS: u64 = 60;
#[cfg(not(debug_assertions))]
const UPDATE_PROGRESS_INTERVAL_MS: u64 = 100;
// Safety net for the update phase: if the loader page never reports a finished
// load, startup must still happen.
#[cfg(not(debug_assertions))]
const STARTUP_FALLBACK_SECS: u64 = 10;

#[derive(Default)]
struct SidecarState {
  started: bool,
  child: Option<CommandChild>,
  keystore_tx: Option<std::sync::mpsc::Sender<keystore::KeystoreJob>>,
}

#[allow(dead_code)]
#[derive(Deserialize)]
struct ReadyPayload {
  url: String,
  host: String,
  port: u16,
  #[serde(rename = "instanceId")]
  instance_id: Option<String>,
}

#[allow(dead_code)]
#[derive(Deserialize)]
struct ErrorPayload {
  code: Option<String>,
  message: Option<String>,
}

// Native window/webview background matching `dist-tauri/loader.css` (`--bg`), so the window
// never flashes white before the first page paints. Falls back to light when the theme is unknown.
fn startup_background_rgb(theme: Option<Theme>) -> (u8, u8, u8) {
  match theme {
    Some(Theme::Dark) => (0x10, 0x14, 0x1a),
    _ => (0xf8, 0xf9, 0xff),
  }
}

fn show_main_window_without_flash(app: &AppHandle) {
  if let Some(window) = app.get_webview_window("main") {
    let (r, g, b) = startup_background_rgb(window.theme().ok());
    let _ = window.set_background_color(Some(Color(r, g, b, 255)));
    let _ = window.show();
    let _ = window.set_focus();
  }
}

fn shutdown_sidecar(app: &AppHandle) {
  if let Some(state) = app.try_state::<Mutex<SidecarState>>() {
    if let Ok(mut guard) = state.lock() {
      if let Some(child) = guard.child.take() {
        let _ = child.kill();
      }
      guard.keystore_tx.take();
      guard.started = false;
    }
  }
}

pub fn build_show_startup_error_script(code: &str, detail: &str) -> String {
  let json_code = serde_json::to_string(code).unwrap_or_else(|_| "\"ERROR\"".to_string());
  let json_detail = serde_json::to_string(detail).unwrap_or_else(|_| "\"An unexpected error occurred\"".to_string());
  // Prevent closing </script> tags if executed in webview / HTML context
  let safe_code = json_code.replace("</", "<\\/");
  let safe_detail = json_detail.replace("</", "<\\/");
  format!(
    "if (window.__showSimeError) {{ window.__showSimeError({}, {}); }}",
    safe_code, safe_detail
  )
}

// JSON-escapes a value for inline script use. Mirrors
// `build_show_startup_error_script`: `</` is broken up so the HTML parser can
// never see a closing script tag.
fn escape_js_json(value: &str) -> String {
  serde_json::to_string(value)
    .unwrap_or_else(|_| "\"\"".to_string())
    .replace("</", "<\\/")
}

/// Builds a script that calls one method of `window.__simeUpdate` on the loader
/// page. Only fixed method names are allowed and the arguments are numbers, so
/// no user-controlled text can reach the script.
pub fn build_update_script(method: &str, received: u64, total: Option<u64>) -> String {
  const ALLOWED: [&str; 5] = ["checking", "progress", "installing", "hide", "fail"];
  if !ALLOWED.contains(&method) {
    return "if (window.__simeUpdate) { window.__simeUpdate.fail(); }".to_string();
  }
  format!(
    "if (window.__simeUpdate) {{ window.__simeUpdate[{}]({}, {}); }}",
    escape_js_json(method),
    received,
    total.unwrap_or(0)
  )
}

fn show_startup_error(app: &AppHandle, code: &str, detail: &str) {
  shutdown_sidecar(app);
  if let Some(window) = app.get_webview_window("main") {
    let script = build_show_startup_error_script(code, detail);
    let _ = window.eval(&script);
    if let Ok(mut current_url) = window.url() {
      current_url
        .query_pairs_mut()
        .clear()
        .append_pair("error", "1")
        .append_pair("code", code)
        .append_pair("detail", detail);
      let _ = window.navigate(current_url);
    }
    let _ = window.show();
    let _ = window.set_focus();
  }
}

#[cfg(not(debug_assertions))]
const KEYSTORE_WATCHDOG_SECS: u64 = 8;

// Writes one control line to the sidecar's private stdin pipe. Never logged: it may carry the key.
#[cfg(not(debug_assertions))]
fn send_sidecar_control(app: &AppHandle, line: &str) {
  if let Some(state) = app.try_state::<Mutex<SidecarState>>() {
    if let Ok(mut guard) = state.lock() {
      if let Some(child) = guard.child.as_mut() {
        let _ = child.write(line.as_bytes());
      }
    }
  }
}

// Starts a dedicated, serialized coordinator for OS credential store operations off the main thread.
// Ensures that startup provisioning, rotation, and revocation execute in strict FIFO order on a single
// worker thread, preventing race conditions between startup provisioning and Forget This Device rotation.
#[cfg(not(debug_assertions))]
fn start_keystore_coordinator(app: &AppHandle) -> std::sync::mpsc::Sender<keystore::KeystoreJob> {
  let (tx, rx) = std::sync::mpsc::channel::<keystore::KeystoreJob>();
  let worker_app = app.clone();
  let provision_resolved = std::sync::Arc::new(Mutex::new(false));

  let watchdog_app = app.clone();
  let watchdog_resolved = provision_resolved.clone();
  std::thread::spawn(move || {
    std::thread::sleep(Duration::from_secs(KEYSTORE_WATCHDOG_SECS));
    if let Ok(mut done) = watchdog_resolved.lock() {
      if !*done {
        *done = true;
        send_sidecar_control(
          &watchdog_app,
          &keystore::format_provision_unavailable_line(1, keystore::KeystoreError::Unavailable),
        );
      }
    }
  });

  std::thread::spawn(move || {
    let mut coordinator = keystore::KeystoreCoordinator::new(keystore::OsBackend);
    while let Ok(job) = rx.recv() {
      let is_provision = matches!(job, keystore::KeystoreJob::Provision { .. });
      if let Some(line) = coordinator.handle_job(job, &keystore::os_random_fill) {
        if is_provision {
          if let Ok(mut done) = provision_resolved.lock() {
            *done = true;
          }
        }
        send_sidecar_control(&worker_app, &line);
      }
    }
  });

  tx
}

fn make_instance_nonce() -> String {
  let nanos = SystemTime::now()
    .duration_since(UNIX_EPOCH)
    .map(|d| d.as_nanos())
    .unwrap_or(0);
  format!("sime-{}-{}", std::process::id(), nanos)
}

#[cfg(all(target_os = "linux", not(debug_assertions)))]
fn resolve_linux_sidecar(app: &AppHandle) -> Option<tauri_plugin_shell::process::Command> {
  // 1. If running inside an AppImage, locate the untampered binary in usr/share
  if let Ok(appdir) = std::env::var("APPDIR") {
    let p = std::path::Path::new(&appdir).join("usr/share/open-sime/open-sime-server");
    if p.is_file() {
      return Some(app.shell().command(p));
    }
  }
  // 2. Relative to current executable: <exe_dir>/../share/open-sime/open-sime-server
  if let Ok(exe_path) = std::env::current_exe() {
    if let Some(parent) = exe_path.parent() {
      let p = parent.join("../share/open-sime/open-sime-server");
      if p.is_file() {
        return Some(app.shell().command(p));
      }
    }
  }
  None
}

#[cfg(not(debug_assertions))]
fn spawn_production_sidecar(app: &AppHandle) {
  {
    let state = app.state::<Mutex<SidecarState>>();
    let mut guard = match state.lock() {
      Ok(g) => g,
      Err(_) => return,
    };
    if guard.started {
      return;
    }
    guard.started = true;
  }

  let app_data_dir = match app.path().app_data_dir() {
    Ok(dir) => dir,
    Err(err) => {
      show_startup_error(
        app,
        "APP_DATA_DIR_ERROR",
        &format!("Nu s-a putut determina directorul de date al aplicației: {}", err),
      );
      return;
    }
  };

  if let Err(err) = std::fs::create_dir_all(&app_data_dir) {
    show_startup_error(
      app,
      "APP_DATA_DIR_CREATE_ERROR",
      &format!("Nu s-a putut crea directorul de date al aplicației: {}", err),
    );
    return;
  }

  let instance_id = make_instance_nonce();
  let port_str = PRODUCTION_PORT.to_string();

  #[cfg(target_os = "linux")]
  let sidecar_cmd_result = resolve_linux_sidecar(app)
    .map(Ok)
    .unwrap_or_else(|| {
      app.shell()
        .sidecar("open-sime-server")
        .or_else(|_| app.shell().sidecar(SIDECAR_NAME))
    });

  #[cfg(not(target_os = "linux"))]
  let sidecar_cmd_result = app
    .shell()
    .sidecar("open-sime-server")
    .or_else(|_| app.shell().sidecar(SIDECAR_NAME));

  let sidecar_cmd = match sidecar_cmd_result {
    Ok(cmd) => cmd
      .args([
          "--production",
          "--watch-stdin",
          "--key-from-stdin",
          "--host",
          PRODUCTION_HOST,
          "--port",
          &port_str,
        ])
        .env("SIME_DESKTOP_PRODUCTION", "1")
        .env("SIME_WATCH_STDIN", "1")
        .env("SIME_KEY_FROM_STDIN", "1")
        .env("SIME_WEB_HOST", PRODUCTION_HOST)
        .env("SIME_WEB_PORT", &port_str)
        .env("SIME_DATA_DIR", app_data_dir.to_string_lossy().as_ref())
        .env("SIME_INSTANCE_ID", &instance_id),
    Err(err) => {
      show_startup_error(
        app,
        "SIDECAR_NOT_FOUND",
        &format!("Serviciul local Open SIME nu a fost găsit în pachet: {}", err),
      );
      return;
    }
  };

  let (mut rx, child) = match sidecar_cmd.spawn() {
    Ok(pair) => pair,
    Err(err) => {
      show_startup_error(
        app,
        "SIDECAR_SPAWN_FAILED",
        &format!("Serviciul local Open SIME nu a putut fi pornit: {}", err),
      );
      return;
    }
  };

  let keystore_tx = start_keystore_coordinator(app);

  if let Some(state) = app.try_state::<Mutex<SidecarState>>() {
    match state.lock() {
      Ok(mut guard) => {
        guard.child = Some(child);
        guard.keystore_tx = Some(keystore_tx.clone());
      }
      Err(_) => {}
    }
  }

  let handle = app.clone();
  let expected_instance = instance_id.clone();

  // Queue initial provisioning of Remember Me key (seq: 1)
  let _ = keystore_tx.send(keystore::KeystoreJob::Provision { seq: 1 });

  tauri::async_runtime::spawn(async move {
    let timeout_handle = handle.clone();
    let is_settled = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let timeout_settled = is_settled.clone();

    std::thread::spawn(move || {
      std::thread::sleep(Duration::from_secs(STARTUP_TIMEOUT_SECS));
      if !timeout_settled.load(std::sync::atomic::Ordering::SeqCst) {
        show_startup_error(
          &timeout_handle,
          "SIDECAR_STARTUP_TIMEOUT",
          &format!(
            "Serviciul local Open SIME nu a răspuns în {} secunde.",
            STARTUP_TIMEOUT_SECS
          ),
        );
      }
    });

    let mut last_error_code = String::from("SIDECAR_EXITED_EARLY");
    let mut last_error_detail = String::from(
      "Serviciul local Open SIME s-a oprit înainte de finalizarea inițializării.",
    );
    let mut next_rotate_seq = 2u64;

    while let Some(event) = rx.recv().await {
      match event {
        CommandEvent::Stdout(bytes) => {
          let text = String::from_utf8_lossy(&bytes);
          for line in text.lines() {
            let trimmed = line.trim();
            if let Some(req_id) = keystore::parse_rotate_request(trimmed) {
              let seq = next_rotate_seq;
              next_rotate_seq += 1;
              if let Some(state) = handle.try_state::<Mutex<SidecarState>>() {
                if let Ok(guard) = state.lock() {
                  if let Some(ref tx) = guard.keystore_tx {
                    let _ = tx.send(keystore::KeystoreJob::Rotate { req_id, seq });
                  }
                }
              }
              continue;
            }
            if let Some(json_str) = trimmed.strip_prefix("SIME_SIDECAR_READY ") {
              if !is_settled.swap(true, std::sync::atomic::Ordering::SeqCst) {
                match serde_json::from_str::<ReadyPayload>(json_str) {
                  Ok(payload) => {
                    if payload.host != PRODUCTION_HOST || payload.port != PRODUCTION_PORT {
                      show_startup_error(
                        &handle,
                        "SIDECAR_MISMATCH_ENDPOINT",
                        &format!(
                          "Adresa raportată de serviciul local ({}:{}) nu corespunde cu {}:{}.",
                          payload.host, payload.port, PRODUCTION_HOST, PRODUCTION_PORT
                        ),
                      );
                    } else if payload.instance_id.as_deref() != Some(expected_instance.as_str()) {
                      show_startup_error(
                        &handle,
                        "SIDECAR_INSTANCE_MISMATCH",
                        "Identificatorul sesiunii serviciului local nu corespunde instanței lansate.",
                      );
                    } else if let (Some(window), Ok(target_url)) =
                      (handle.get_webview_window("main"), Url::parse(&payload.url))
                    {
                      let _ = window.navigate(target_url);
                      let _ = window.show();
                      let _ = window.set_focus();
                    }
                  }
                  Err(err) => {
                    show_startup_error(
                      &handle,
                      "SIDECAR_MALFORMED_READY",
                      &format!("Răspuns de inițializare invalid de la serviciul local: {}", err),
                    );
                  }
                }
              }
            }
          }
        }
        CommandEvent::Stderr(bytes) => {
          let text = String::from_utf8_lossy(&bytes);
          for line in text.lines() {
            let trimmed = line.trim();
            if let Some(json_str) = trimmed.strip_prefix("SIME_SIDECAR_ERROR ") {
              if let Ok(err_payload) = serde_json::from_str::<ErrorPayload>(json_str) {
                if let Some(code) = err_payload.code {
                  last_error_code = code;
                }
                if let Some(msg) = err_payload.message {
                  last_error_detail = msg;
                }
              }
            } else if !trimmed.is_empty() {
              eprintln!("[open-sime-sidecar] {}", trimmed);
            }
          }
        }
        CommandEvent::Error(err) => {
          if !is_settled.swap(true, std::sync::atomic::Ordering::SeqCst) {
            show_startup_error(
              &handle,
              "SIDECAR_STREAM_ERROR",
              &format!("Eroare la comunicarea cu serviciul local: {}", err),
            );
          }
        }
        CommandEvent::Terminated(status) => {
          if !is_settled.swap(true, std::sync::atomic::Ordering::SeqCst) {
            let code_label = format!(
              "{} (exit={:?})",
              last_error_code, status.code
            );
            show_startup_error(&handle, &code_label, &last_error_detail);
          }
          break;
        }
        _ => {}
      }
    }
  });
}

#[cfg(not(debug_assertions))]
fn emit_update(app: &AppHandle, script: String) {
  if let Some(window) = app.get_webview_window("main") {
    let _ = window.eval(&script);
  }
}

#[cfg(not(debug_assertions))]
static UPDATE_PHASE_STARTED: AtomicBool = AtomicBool::new(false);

// Starts the one and only update attempt for this launch. Runs after the loader
// page finished loading and before the sidecar starts, so nothing holds the
// port or the install files while an update is applied. Every failure path ends
// in `spawn_production_sidecar`, so a broken updater cannot block startup.
#[cfg(not(debug_assertions))]
fn start_update_phase(app: &AppHandle) {
  // The page load hook also fires for the app itself once the sidecar is up;
  // only the first (loader) load may start an update attempt.
  if UPDATE_PHASE_STARTED.swap(true, Ordering::SeqCst) {
    return;
  }

  #[cfg(target_os = "linux")]
  if app.env().appimage.is_none() {
    // Only an AppImage can be replaced in place. Deb, rpm and unpacked builds
    // have no safe self-update path, so they keep starting normally.
    spawn_production_sidecar(app);
    return;
  }

  emit_update(app, build_update_script("checking", 0, None));

  let resolved = Arc::new(AtomicBool::new(false));

  // Check deadline: whatever happens upstream, the app opens after 5 seconds.
  {
    let app = app.clone();
    let resolved = resolved.clone();
    std::thread::spawn(move || {
      std::thread::sleep(Duration::from_secs(UPDATE_CHECK_TIMEOUT_SECS));
      if !resolved.swap(true, Ordering::SeqCst) {
        emit_update(&app, build_update_script("fail", 0, None));
        spawn_production_sidecar(&app);
      }
    });
  }

  let app = app.clone();
  std::thread::spawn(move || {
    let check = match app.updater() {
      Ok(updater) => tauri::async_runtime::block_on(updater.check()),
      Err(err) => Err(err),
    };
    match check {
      Ok(Some(update)) => {
        if resolved.swap(true, Ordering::SeqCst) {
          return; // The check deadline already started the app.
        }
        run_update(&app, update);
      }
      _ => {
        if !resolved.swap(true, Ordering::SeqCst) {
          emit_update(&app, build_update_script("fail", 0, None));
          spawn_production_sidecar(&app);
        }
      }
    }
  });
}

// Downloads, verifies and installs the update. Signatures are checked by the
// plugin against the embedded public key, so a corrupted or wrongly signed
// package never installs and startup simply continues.
#[cfg(not(debug_assertions))]
fn run_update(app: &AppHandle, update: tauri_plugin_updater::Update) {
  let activity = Arc::new(Mutex::new(Instant::now()));
  let aborted = Arc::new(AtomicBool::new(false));

  {
    let app = app.clone();
    let activity = activity.clone();
    let aborted = aborted.clone();
    std::thread::spawn(move || loop {
      std::thread::sleep(Duration::from_secs(2));
      if aborted.load(Ordering::SeqCst) {
        return;
      }
      let idle = activity.lock().map(|last| last.elapsed()).unwrap_or_default();
      if idle >= Duration::from_secs(UPDATE_STALL_TIMEOUT_SECS)
        && !aborted.swap(true, Ordering::SeqCst)
      {
        emit_update(&app, build_update_script("fail", 0, None));
        spawn_production_sidecar(&app);
        return;
      }
    });
  }

  let mut downloaded: u64 = 0;
  let mut last_emit = Instant::now();
  let chunk_app = app.clone();
  let chunk_activity = activity.clone();
  let on_chunk = move |chunk_len: usize, total: Option<u64>| {
    downloaded = downloaded.saturating_add(chunk_len as u64);
    if let Ok(mut last) = chunk_activity.lock() {
      *last = Instant::now();
    }
    if last_emit.elapsed() >= Duration::from_millis(UPDATE_PROGRESS_INTERVAL_MS) {
      last_emit = Instant::now();
      emit_update(
        &chunk_app,
        build_update_script("progress", downloaded, total),
      );
    }
  };

  let finish_app = app.clone();
  let on_finish = move || {
    emit_update(&finish_app, build_update_script("installing", 0, None));
  };

  let result = tauri::async_runtime::block_on(update.download(on_chunk, on_finish));
  if aborted.load(Ordering::SeqCst) {
    return; // The stall deadline already started the app; drop the download.
  }

  match result {
    Ok(bytes) => {
      if aborted.swap(true, Ordering::SeqCst) {
        return;
      }
      match update.install(bytes) {
        Ok(()) => {
          // Windows: the plugin launches the NSIS installer and exits the app.
          // macOS and Linux: relaunch to run the freshly installed build.
          #[cfg(not(windows))]
          app.restart();
          #[cfg(windows)]
          aborted.store(true, Ordering::SeqCst);
        }
        Err(_) => {
          emit_update(app, build_update_script("fail", 0, None));
          spawn_production_sidecar(app);
        }
      }
    }
    Err(_) => {
      if !aborted.swap(true, Ordering::SeqCst) {
        emit_update(app, build_update_script("fail", 0, None));
        spawn_production_sidecar(app);
      }
    }
  }
}

#[cfg(windows)]
fn assign_to_app_job_object() {
  use std::ffi::c_void;

  #[repr(C)]
  #[derive(Default)]
  struct JobObjectBasicLimitInformation {
    per_process_user_time_limit: i64,
    per_job_user_time_limit: i64,
    limit_flags: u32,
    minimum_working_set_size: usize,
    maximum_working_set_size: usize,
    active_process_limit: u32,
    affinity: usize,
    priority_class: u32,
    scheduling_class: u32,
  }

  #[repr(C)]
  #[derive(Default)]
  struct IoCounters {
    read_operation_count: u64,
    write_operation_count: u64,
    other_operation_count: u64,
    read_transfer_count: u64,
    write_transfer_count: u64,
    other_transfer_count: u64,
  }

  #[repr(C)]
  #[derive(Default)]
  struct JobObjectExtendedLimitInformation {
    basic_limit_information: JobObjectBasicLimitInformation,
    io_info: IoCounters,
    process_memory_limit: usize,
    job_memory_limit: usize,
    peak_process_memory_used: usize,
    peak_job_memory_used: usize,
  }

  #[link(name = "kernel32")]
  extern "system" {
    fn CreateJobObjectW(lp_job_attributes: *const c_void, lp_name: *const u16) -> *mut c_void;
    fn SetInformationJobObject(
      h_job: *mut c_void,
      job_object_information_class: i32,
      lp_job_object_information: *const c_void,
      cb_job_object_information_length: u32,
    ) -> i32;
    fn AssignProcessToJobObject(h_job: *mut c_void, h_process: *mut c_void) -> i32;
    fn GetCurrentProcess() -> *mut c_void;
  }

  const JOB_OBJECT_EXTENDED_LIMIT_INFORMATION: i32 = 9;
  const JOB_OBJECT_LIMIT_BREAKAWAY_OK: u32 = 0x0000_0800;
  const JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE: u32 = 0x0000_2000;

  unsafe {
    let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
    if !job.is_null() {
      let mut info = JobObjectExtendedLimitInformation::default();
      info.basic_limit_information.limit_flags =
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_BREAKAWAY_OK;
      let _ = SetInformationJobObject(
        job,
        JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
        &info as *const _ as *const c_void,
        std::mem::size_of::<JobObjectExtendedLimitInformation>() as u32,
      );
      let _ = AssignProcessToJobObject(job, GetCurrentProcess());
    }
  }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
  #[cfg(windows)]
  assign_to_app_job_object();

  let app = tauri::Builder::default()
    .plugin(tauri_plugin_shell::init())
    .plugin(tauri_plugin_updater::Builder::new().build())
    .manage(Mutex::new(SidecarState::default()))
    .setup(|app| {
      // The window is created hidden (tauri.conf.json); paint its background before showing it.
      show_main_window_without_flash(app.handle());

      #[cfg(debug_assertions)]
      {
        let _ = (
          SIDECAR_NAME,
          PRODUCTION_HOST,
          PRODUCTION_PORT,
          STARTUP_TIMEOUT_SECS,
          show_startup_error as fn(&AppHandle, &str, &str),
          build_show_startup_error_script as fn(&str, &str) -> String,
          make_instance_nonce as fn() -> String,
        );
      }

      #[cfg(not(debug_assertions))]
      {
        // The sidecar now starts from the page load hook, after the update
        // attempt. If that hook never fires, start it anyway so a broken loader
        // page cannot leave the window empty forever.
        let handle = app.handle().clone();
        std::thread::spawn(move || {
          std::thread::sleep(Duration::from_secs(STARTUP_FALLBACK_SECS));
          if !UPDATE_PHASE_STARTED.load(Ordering::SeqCst) {
            spawn_production_sidecar(&handle);
          }
        });
      }

      Ok(())
    })
    .on_page_load(|webview, payload| {
      // The update phase starts only once the loader page is actually loaded,
      // so its progress bar exists before the first `eval` runs.
      #[cfg(not(debug_assertions))]
      if payload.event() == PageLoadEvent::Finished {
        start_update_phase(webview.app_handle());
      }
      #[cfg(debug_assertions)]
      let _ = (webview, payload);
    })
    .on_window_event(|window, event| {
      if window.label() == "main" {
        if let WindowEvent::Destroyed = event {
          shutdown_sidecar(window.app_handle());
        }
      }
    })
    .build(tauri::generate_context!())
    .expect("error while building Open SIME desktop application");

  app.run(|app_handle, event| {
    if let RunEvent::Exit | RunEvent::ExitRequested { .. } = event {
      shutdown_sidecar(app_handle);
    }
  });
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn test_startup_background_matches_loader_theme() {
    assert_eq!(startup_background_rgb(Some(Theme::Dark)), (0x10, 0x14, 0x1a));
    assert_eq!(startup_background_rgb(Some(Theme::Light)), (0xf8, 0xf9, 0xff));
    assert_eq!(startup_background_rgb(None), (0xf8, 0xf9, 0xff));
  }

  #[test]
  fn test_build_show_startup_error_script_escapes() {
    // 1. newline
    let script = build_show_startup_error_script("CODE\nLINE", "Detail\nline 2\r\nline 3");
    assert!(script.contains(r#""CODE\nLINE""#));
    assert!(script.contains(r#""Detail\nline 2\r\nline 3""#));

    // 2. single quote
    let script = build_show_startup_error_script("CODE'1", "It's a test 'detail'");
    assert!(script.contains(r#""CODE'1""#));
    assert!(script.contains(r#""It's a test 'detail'""#));

    // 3. double quote
    let script = build_show_startup_error_script(r#"CODE"QUOTED""#, r#"Detail with "quotes""#);
    assert!(script.contains(r#""CODE\"QUOTED\"""#));
    assert!(script.contains(r#""Detail with \"quotes\"""#));

    // 4. backslash
    let script = build_show_startup_error_script(r#"CODE\PATH"#, r#"C:\Windows\System32"#);
    assert!(script.contains(r#""CODE\\PATH""#));
    assert!(script.contains(r#""C:\\Windows\\System32""#));

    // 5. unicode (ă, î, ș, ț, â)
    let script = build_show_startup_error_script("EROARE_ȘCOALĂ", "Aplicația școlară: învățător, știință, țară, pâine.");
    assert!(script.contains(r#""EROARE_ȘCOALĂ""#));
    assert!(script.contains(r#""Aplicația școlară: învățător, știință, țară, pâine.""#));

    // 6. </script> injection prevention
    let script = build_show_startup_error_script("INJECT", "</script><script>alert('xss')</script>");
    assert!(!script.contains("</script>"));
    assert!(!script.to_lowercase().contains("</script>"));
  }

  #[test]
  fn test_build_update_script_calls_allowed_methods() {
    for method in ["checking", "progress", "installing", "hide", "fail"] {
      let script = build_update_script(method, 0, None);
      assert!(script.starts_with("if (window.__simeUpdate) {"), "{}", script);
      assert!(
        script.contains(&format!(r#"window.__simeUpdate["{}"](0, 0)"#, method)),
        "{}",
        script
      );
    }

    let script = build_update_script("progress", 1234, Some(5678));
    assert!(script.contains(r#"window.__simeUpdate["progress"](1234, 5678)"#));

    let script = build_update_script("progress", 42, None);
    assert!(script.contains(r#"window.__simeUpdate["progress"](42, 0)"#));
  }

  #[test]
  fn test_build_update_script_rejects_unknown_method() {
    let script = build_update_script(r#"fail"];alert(1);["x"#, 0, None);
    assert_eq!(script, "if (window.__simeUpdate) { window.__simeUpdate.fail(); }");
    assert!(!script.contains("alert"));
  }

  #[test]
  fn test_escape_js_json_breaks_closing_script_tag() {
    let escaped = escape_js_json("</script><script>alert('x')</script>");
    assert!(!escaped.contains("</"));
    assert!(!escaped.to_lowercase().contains("</script"));
    assert!(escaped.contains(r#"<\/script>"#));

    // Non-ASCII text stays readable instead of being \u-escaped.
    assert_eq!(escape_js_json("școală"), "\"școală\"");
  }
}
