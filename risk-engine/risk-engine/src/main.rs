use risk_core::RiskEngine;
use risk_engine::app::{router, AppState};
use risk_engine::config::Settings;
use tokio::net::TcpListener;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // `risk-engine healthcheck` — used by Docker in an image without curl.
    if std::env::args().nth(1).as_deref() == Some("healthcheck") {
        std::process::exit(if healthcheck() { 0 } else { 1 });
    }

    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")))
        .init();

    let settings = Settings::from_env()?;
    let engine = RiskEngine::new(settings.risk.clone())?;
    tracing::info!(config = ?settings.risk, threads = settings.batch_threads, "risk engine configured");

    let app = router(AppState::new(engine, settings.batch_threads, settings.max_batch_size));
    let listener = TcpListener::bind(settings.addr).await?;
    tracing::info!(addr = %settings.addr, "risk engine listening");

    axum::serve(listener, app).with_graceful_shutdown(shutdown_signal()).await?;
    tracing::info!("risk engine stopped");
    Ok(())
}

fn healthcheck() -> bool {
    use std::io::{Read, Write};
    use std::time::Duration;

    let port = std::env::var("PORT").unwrap_or_else(|_| "8086".into());
    let Ok(mut stream) = std::net::TcpStream::connect(format!("127.0.0.1:{port}")) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    if stream.write_all(b"GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n").is_err() {
        return false;
    }
    let mut response = String::new();
    let _ = stream.read_to_string(&mut response);
    response.starts_with("HTTP/1.0 200") || response.starts_with("HTTP/1.1 200")
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c().await.expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install SIGTERM handler")
            .recv()
            .await;
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
