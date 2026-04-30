use anyhow::Result;
use tokio::process::Command;

use crate::{
    config::ConnectionMode,
    streamer::{StreamerMsg, tcp_streamer},
};

use super::{
    AudioStream, ConnectError, StreamerTrait,
    tcp_streamer::{TcpStreamer, TcpStreamerState},
};

pub struct AdbStreamer {
    tcp_streamer: TcpStreamer,
}

const ANDROID_PACKAGES: &[&str] = &[
    "io.github.teamclouday.AndroidMic.debug",
    "io.github.teamclouday.AndroidMic.nightly",
    "io.github.teamclouday.AndroidMic",
];
const MAIN_ACTIVITY: &str = "io.github.teamclouday.androidMic.ui.MainActivity";
const FOREGROUND_SERVICE: &str = "io.github.teamclouday.androidMic.domain.service.ForegroundService";
const AUTO_CONNECT_ACTION: &str = "io.github.teamclouday.androidMic.AUTO_CONNECT";

async fn get_connected_devices() -> Result<Vec<String>, ConnectError> {
    let mut cmd = Command::new("adb");
    cmd.arg("devices");

    let output = exec_cmd(cmd).await?;
    let mut devices = Vec::new();

    // Skip the first line which is "List of devices attached"
    for line in output.lines().skip(1) {
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() >= 2 {
            devices.push(parts[0].to_string());
        }
    }

    Ok(devices)
}

async fn adb_connect(phone_ip: &str, adb_port: u16) -> Result<(), ConnectError> {
    let mut cmd = Command::new("adb");
    cmd.arg("connect").arg(format!("{phone_ip}:{adb_port}"));
    exec_cmd(cmd).await?;
    Ok(())
}

async fn remove_adb_reverse_proxy(device_id: &str, port: u16) -> Result<(), ConnectError> {
    let mut cmd = Command::new("adb");
    cmd.arg("-s")
        .arg(device_id)
        .arg("reverse")
        .arg("--remove")
        .arg(format!("tcp:{}", port));

    exec_cmd(cmd).await?;

    Ok(())
}

async fn launch_android_app(device_id: &str, port: u16) -> Result<(), ConnectError> {
    let mut last_error: Option<ConnectError> = None;

    for package in ANDROID_PACKAGES {
        let mut service_cmd = Command::new("adb");
        service_cmd
            .arg("-s")
            .arg(device_id)
            .arg("shell")
            .arg("am")
            .arg("start-foreground-service")
            .arg("-n")
            .arg(format!("{package}/{FOREGROUND_SERVICE}"))
            .arg("-a")
            .arg(AUTO_CONNECT_ACTION)
            .arg("--ez")
            .arg("auto_connect")
            .arg("true")
            .arg("--es")
            .arg("mode")
            .arg("ADB")
            .arg("--es")
            .arg("port")
            .arg(port.to_string());

        match exec_cmd(service_cmd).await {
            Ok(_) => {
                let mut activity_cmd = Command::new("adb");
                activity_cmd
                    .arg("-s")
                    .arg(device_id)
                    .arg("shell")
                    .arg("am")
                    .arg("start")
                    .arg("-n")
                    .arg(format!("{package}/{MAIN_ACTIVITY}"))
                    .arg("--ez")
                    .arg("auto_connect")
                    .arg("true")
                    .arg("--es")
                    .arg("mode")
                    .arg("ADB")
                    .arg("--es")
                    .arg("port")
                    .arg(port.to_string());
                let _ = exec_cmd(activity_cmd).await;
                return Ok(());
            }
            Err(error) => {
                last_error = Some(error);
            }
        }
    }

    Err(last_error.unwrap_or(ConnectError::NoAdbDevice))
}

async fn exec_cmd(mut cmd: Command) -> Result<String, ConnectError> {
    // https://learn.microsoft.com/en-us/windows/win32/procthread/process-creation-flags
    #[cfg(target_os = "windows")]
    cmd.creation_flags(0x08000000);

    let status = cmd.output().await.map_err(ConnectError::CommandFailed)?;

    if !status.status.success() {
        let stderr = String::from_utf8_lossy(&status.stderr).to_string();

        return Err(ConnectError::AdbStatusCommand {
            code: status.status.code(),
            stderr,
        });
    }
    let stdout = String::from_utf8_lossy(&status.stdout).trim().to_string();
    Ok(stdout)
}

pub async fn new(
    phone_ip: Option<&str>,
    adb_port: u16,
    port: u16,
    stream_config: AudioStream,
) -> Result<AdbStreamer, ConnectError> {
    let tcp_streamer = tcp_streamer::new("127.0.0.1".parse().unwrap(), port, stream_config).await?;

    if let Some(phone_ip) = phone_ip
        .map(str::trim)
        .filter(|phone_ip| !phone_ip.is_empty())
    {
        adb_connect(phone_ip, adb_port).await?;
    }

    let devices = get_connected_devices().await?;
    if devices.is_empty() {
        return Err(ConnectError::NoAdbDevice);
    }

    for device_id in &devices {
        if let Err(e) = remove_adb_reverse_proxy(device_id, tcp_streamer.port).await {
            if !e.to_string().contains("not found") {
                warn!("cannot remove adb proxy for device {device_id}: {e}");
            }
        }

        let mut cmd = Command::new("adb");
        cmd.arg("-s")
            .arg(device_id)
            .arg("reverse")
            .arg(format!("tcp:{}", tcp_streamer.port))
            .arg(format!("tcp:{}", tcp_streamer.port));
        exec_cmd(cmd).await?;

        launch_android_app(device_id, tcp_streamer.port).await?;
    }

    let streamer = AdbStreamer { tcp_streamer };
    Ok(streamer)
}

impl StreamerTrait for AdbStreamer {
    async fn next(&mut self) -> Result<Option<StreamerMsg>, ConnectError> {
        self.tcp_streamer.next().await
    }

    fn reconfigure_stream(&mut self, config: AudioStream) {
        self.tcp_streamer.reconfigure_stream(config)
    }

    fn status(&self) -> StreamerMsg {
        match &self.tcp_streamer.state {
            TcpStreamerState::Listening { .. } => StreamerMsg::Listening {
                ip: None,
                port: None,
            },
            TcpStreamerState::Streaming { .. } => StreamerMsg::Connected {
                ip: None,
                port: None,
                mode: ConnectionMode::Adb,
            },
        }
    }
}

impl Drop for AdbStreamer {
    fn drop(&mut self) {
        let port = self.tcp_streamer.port;
        tokio::spawn(async move {
            let devices: Vec<String> = get_connected_devices().await.unwrap_or_default();

            for device_id in devices {
                if let Err(e) = remove_adb_reverse_proxy(&device_id, port).await {
                    warn!("cannot remove adb proxy for device {device_id}: {e}");
                }
            }
        });
    }
}
