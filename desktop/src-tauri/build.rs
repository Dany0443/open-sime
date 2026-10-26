fn main() {
  println!("cargo:rerun-if-env-changed=OPEN_SIME_ALLOW_MISSING_SIDECAR");
  let target = std::env::var("TARGET").unwrap_or_default();
  let ext = if target.contains("windows") { ".exe" } else { "" };
  let sidecar_rel_path = format!("binaries/open-sime-server-{}{}", target, ext);
  println!("cargo:rerun-if-changed={}", sidecar_rel_path);
  let sidecar_path = std::path::Path::new(&sidecar_rel_path);

  if !sidecar_path.exists() {
    let is_release = std::env::var("PROFILE").as_deref() == Ok("release");
    let allow_missing = std::env::var("OPEN_SIME_ALLOW_MISSING_SIDECAR").is_ok();

    if is_release && !allow_missing {
      panic!(
        "Sidecar binary `{}` does not exist. Run `npm run sidecar:build` before packaging for release.",
        sidecar_path.display()
      );
    }

    println!(
      "cargo:warning=Sidecar binary `{}` not found. Omitting externalBin for syntax check / dev build.",
      sidecar_path.display()
    );

    let mut config: serde_json::Value = std::env::var("TAURI_CONFIG")
      .ok()
      .and_then(|val| serde_json::from_str(&val).ok())
      .unwrap_or_else(|| serde_json::json!({}));

    if let Some(bundle) = config.get_mut("bundle").and_then(|b| b.as_object_mut()) {
      bundle.insert("externalBin".to_string(), serde_json::json!([]));
    } else if let Some(root) = config.as_object_mut() {
      root.insert("bundle".to_string(), serde_json::json!({ "externalBin": [] }));
    }

    std::env::set_var("TAURI_CONFIG", config.to_string());
  }

  tauri_build::build()
}
