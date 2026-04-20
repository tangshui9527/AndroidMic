use std::{io, net::IpAddr, time::Duration};

use futures::StreamExt;
use prost::Message;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
};
use tokio_util::codec::{Framed, LengthDelimitedCodec};

use crate::{
    config::ConnectionMode,
    streamer::{CHECK_1, CHECK_2, StreamerMsg, WriteError},
};

use super::{AudioPacketMessage, AudioStream, ConnectError, StreamerTrait};

const MAX_WAIT_TIME: Duration = Duration::from_millis(1500);

const DISCONNECT_LOOP_DETECTER_MAX: u32 = 1000;

pub struct TcpStreamer {
    ip: IpAddr,
    pub port: u16,
    pub state: TcpStreamerState,
    stream_config: AudioStream,
}

#[allow(clippy::large_enum_variant)]
pub enum TcpStreamerState {
    Listening {
        listener: TcpListener,
    },
    Streaming {
        framed: Framed<TcpStream, LengthDelimitedCodec>,
        remote_addr: std::net::SocketAddr,
        disconnect_loop_detecter: u32,
    },
}

pub async fn new(
    ip: IpAddr,
    port: u16,
    stream_config: AudioStream,
) -> Result<TcpStreamer, ConnectError> {
    let listener = TcpListener::bind((ip, port))
        .await
        .map_err(|e| ConnectError::CantBindPort(port, e))?;

    let addr = TcpListener::local_addr(&listener).map_err(ConnectError::NoLocalAddress)?;

    let streamer = TcpStreamer {
        ip,
        port: addr.port(),
        stream_config,
        state: TcpStreamerState::Listening { listener },
    };

    Ok(streamer)
}

impl StreamerTrait for TcpStreamer {
    fn reconfigure_stream(&mut self, stream_config: AudioStream) {
        self.stream_config = stream_config;
    }

    fn status(&self) -> StreamerMsg {
        match &self.state {
            TcpStreamerState::Listening { .. } => StreamerMsg::Listening {
                ip: Some(self.ip),
                port: Some(self.port),
            },
            TcpStreamerState::Streaming { remote_addr, .. } => StreamerMsg::Connected {
                ip: Some(remote_addr.ip()),
                port: Some(remote_addr.port()),
                mode: ConnectionMode::Tcp,
            },
        }
    }

    async fn next(&mut self) -> Result<Option<StreamerMsg>, ConnectError> {
        match &mut self.state {
            TcpStreamerState::Listening { listener } => {
                let addr =
                    TcpListener::local_addr(listener).map_err(ConnectError::NoLocalAddress)?;

                info!("TCP server listening on {}", addr);

                let (mut stream, addr) =
                    listener.accept().await.map_err(ConnectError::CantAccept)?;

                info!("Accepted TCP connection from {}", addr);

                let mut buf1 = [0u8; CHECK_1.len()];

                info!("Waiting for handshake CHECK_1 from {}...", addr);
                stream
                    .read_exact(&mut buf1)
                    .await
                    .map_err(|e| ConnectError::HandShakeFailed("reading", e))?;

                if buf1 != CHECK_1.as_bytes() {
                    let s = String::from_utf8_lossy(&buf1);
                    error!("Handshake failed: expected {}, got {}", CHECK_1, s);
                    return Err(ConnectError::HandShakeFailed2(format!(
                        "{} != {}",
                        CHECK_1, s
                    )));
                }

                info!("Handshake CHECK_1 received, sending CHECK_2 to {}...", addr);
                stream
                    .write_all(CHECK_2.as_bytes())
                    .await
                    .map_err(|e| ConnectError::HandShakeFailed("writing", e))?;

                info!("Connection accepted and handshake completed, remote address: {}", addr);

                self.state = TcpStreamerState::Streaming {
                    framed: Framed::new(stream, LengthDelimitedCodec::new()),
                    remote_addr: addr,
                    disconnect_loop_detecter: 0,
                };

                Ok(Some(StreamerMsg::Connected {
                    ip: Some(addr.ip()),
                    port: Some(addr.port()),
                    mode: ConnectionMode::Tcp,
                }))
            }
            TcpStreamerState::Streaming {
                framed,
                remote_addr: _,
                disconnect_loop_detecter: _,
            } => {
                match framed.next().await {
                    Some(Ok(frame)) => match AudioPacketMessage::decode(frame) {
                        Ok(packet) => {
                            let buffer_size = packet.buffer.len();
                            let sample_rate = packet.sample_rate;

                            match self.stream_config.process_audio_packet(packet) {
                                Ok(Some(buffer)) => {
                                    debug!("received {} bytes", buffer_size);
                                    Ok(Some(StreamerMsg::UpdateAudioWave {
                                        data: AudioPacketMessage::to_wave_data(
                                            &buffer,
                                            sample_rate,
                                        ),
                                    }))
                                }
                                _ => Ok(None),
                            }
                        }
                        Err(e) => Err(ConnectError::WriteError(WriteError::Deserializer(e))),
                    },

                    Some(Err(e)) => {
                        match e.kind() {
                            io::ErrorKind::TimedOut => Ok(None), // timeout use to check for input on stdin
                            io::ErrorKind::WouldBlock => Ok(None), // trigger on Linux when there is no stream input
                            _ => Err(WriteError::Io(e))?,
                        }
                    }
                    None => Err(ConnectError::Disconnected),
                }
            }
        }
    }
}
