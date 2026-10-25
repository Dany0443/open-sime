#[allow(unused_imports)]
use serde::Deserialize;
use std::sync::Mutex;
#[allow(unused_imports)]
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Manager, RunEvent, Url, WindowEvent};
use tauri_plugin_shell::process::CommandChild;
#[allow(unused_imports)]
use tauri_plugin_shell::process::CommandEvent;
#[allow(unused_imports)]
use tauri_plugin_shell::ShellExt;

const SIDECAR_NAME: &str = "binaries/open-sime-server";
const PRODUCTION_HOST: &str = "127.0.0.1";
const PRODUCTION_PORT: u16 = 17321;
const STARTUP_TIMEOUT_SECS: u64 = 15;

#[derive(Default)]
struct SidecarState {
  started: bool,
  child: Option<CommandChild>,
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

fn shutdown_sidecar(app: &AppHandle) {
  if let Some(state) = app.try_state::<Mutex<SidecarState>>() {
    if let Ok(mut guard) = state.lock() {
      if let Some(child) = guard.child.take() {
        let _ = child.kill();
      }
      guard.started = false;
    }
  }
}

fn show_startup_error(app: &AppHandle, code: &str, detail: &str) {
  shutdown_sidecar(app);
  if let Some(window) = app.get_webview_window("main") {
    let escaped_code = code.replace('\\', "\\\\").replace('\'', "\\'");
    let escaped_detail = detail.replace('\\', "\\\\").replace('\'', "\\'");
    let _ = window.eval(&format!(
      "if (window.__showSimeError) {{ window.__showSimeError('{}', '{}'); }}",
      escaped_code, escaped_detail
    ));
    let _ = window.show();
    let _ = window.set_focus();
  }
}

fn make_instance_nonce() -> String {
  let nanos = SystemTime::now()
    .duration_since(UNIX_EPOCH)
    .map(|d| d.as_nanos())
    .unwrap_or(0);
  format!("sime-{}-{}", std::process::id(), nanos)
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

  let sidecar_cmd = match app
    .shell()
    .sidecar("open-sime-server")
    .or_else(|_| app.shell().sidecar(SIDECAR_NAME))
  {
    Ok(cmd) => cmd
      .args([
          "--production",
          "--watch-stdin",
          "--host",
          PRODUCTION_HOST,
          "--port",
          &port_str,
        ])
        .env("SIME_DESKTOP_PRODUCTION", "1")
        .env("SIME_WATCH_STDIN", "1")
        .env("SIME_WEB_HOST", PRODUCTION_HOST)
        .env("SIME_WEB_PORT", &port_str)
        .env("SIME_DATA_DIR", app_data_dir.to_string_lossy().as_ref())
        .env("SIME_INSTANCE_ID", &instance_id)
    }
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

  if let Some(state) = app.try_state::<Mutex<SidecarState>>() {
    match state.lock() {
      Ok(mut guard) => {
        guard.child = Some(child);
      }
      Err(_) => {}
    }
  }

  let handle = app.clone();
  let expected_instance = instance_id.clone();

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

    while let Some(event) = rx.recv().await {
      match event {
        CommandEvent::Stdout(bytes) => {
          let text = String::from_utf8_lossy(&bytes);
          for line in text.lines() {
            let trimmed = line.trim();
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
  let app = tauri::Builder::default()
    .plugin(tauri_plugin_shell::init())
    .manage(Mutex::new(SidecarState::default()))
    .setup(|app| {
      #[cfg(debug_assertions)]
      {
        let _ = (
          SIDECAR_NAME,
          PRODUCTION_HOST,
          PRODUCTION_PORT,
          STARTUP_TIMEOUT_SECS,
          show_startup_error as fn(&AppHandle, &str, &str),
          make_instance_nonce as fn() -> String,
        );
        if let Some(window) = app.get_webview_window("main") {
          let _ = window.show();
          let _ = window.set_focus();
        }
      }

      #[cfg(not(debug_assertions))]
      {
        spawn_production_sidecar(app.handle());
      }

      Ok(())
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
