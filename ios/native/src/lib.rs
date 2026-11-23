#![cfg_attr(not(any(target_os = "ios", target_os = "macos", target_os = "linux")), allow(dead_code))]
#![allow(dead_code)]

mod config;
mod crypto;
mod diagnostics;
mod early_log;
mod file_io;
mod incoming;
mod network;
mod output;
mod peer;
mod protocol;
mod resource_limits;
mod runtime;
mod scheduling;
mod session;
mod tap;
mod tunnel;
mod udp;
mod wake;

use std::{
    ffi::{c_char, CStr}, fs::{self, OpenOptions}, io::Write, net::Ipv4Addr,
    os::unix::fs::{DirBuilderExt, OpenOptionsExt}, path::{Path, PathBuf},
    sync::{atomic::{AtomicBool, AtomicU64, Ordering}, mpsc::{self, SyncSender}, Arc, Mutex},
    thread::{self, JoinHandle},
};
use anyhow::{ensure, Context, Result};
use serde::Deserialize;
use serde_json::{json, Value};
use zeroize::Zeroizing;
use network::{NetworkPassword, NetworkRequest};
use protocol::Identity;
use runtime::{Command, Options, Update};
use session::Session;

const QUEUE: usize = 256;
const MTU: usize = 1500;

pub(crate) struct PacketIo {
    input: SyncSender<Vec<u8>>,
    outgoing: Mutex<mpsc::Receiver<Vec<u8>>>,
    output: SyncSender<Vec<u8>>,
    incoming: Mutex<mpsc::Receiver<Vec<u8>>>,
}

impl PacketIo {
    fn new() -> Self {
        let (input, outgoing) = mpsc::sync_channel(QUEUE);
        let (output, incoming) = mpsc::sync_channel(QUEUE);
        Self { input, outgoing: Mutex::new(outgoing), output, incoming: Mutex::new(incoming) }
    }

    pub(crate) fn take_outgoing(&self, bound: usize) -> Vec<Vec<u8>> {
        self.outgoing.lock().unwrap().try_iter().take(bound).collect()
    }

    pub(crate) fn deliver(&self, frame: &[u8], local: Ipv4Addr) -> bool {
        if frame.len() < 34 || frame[12..14] != [8, 0] || frame[..6] != tunnel::mac(local) {
            return false;
        }
        let length = u16::from_be_bytes([frame[16], frame[17]]) as usize;
        let Some(packet) = frame.get(14..14 + length) else { return false; };
        if !ipv4(packet) || packet[16..20] != local.octets() { return false; }
        self.output.try_send(packet.to_vec()).is_ok()
    }
}

fn ipv4(packet: &[u8]) -> bool {
    if !(20..=MTU).contains(&packet.len()) || packet[0] >> 4 != 4 { return false; }
    let length = (packet[0] as usize & 15) * 4;
    if !(20..=packet.len()).contains(&length)
        || u16::from_be_bytes([packet[2], packet[3]]) as usize != packet.len() {
        return false;
    }
    let mut sum = packet[..length].chunks_exact(2)
        .map(|pair| u16::from_be_bytes([pair[0], pair[1]]) as u32).sum::<u32>();
    while sum > 0xffff { sum = (sum & 0xffff) + (sum >> 16); }
    sum == 0xffff
}

pub(crate) fn packet_target(packet: &[u8], local: Ipv4Addr) -> Option<Ipv4Addr> {
    if !ipv4(packet) || packet[12..16] != local.octets() || packet[16] != 26 { return None; }
    let target = Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]);
    (target != local).then_some(target)
}

struct State {
    status: Value,
    operation: Option<Value>,
}

pub struct Core {
    io: Arc<PacketIo>,
    commands: SyncSender<Command>,
    stop: Arc<AtomicBool>,
    state: Arc<Mutex<State>>,
    ticket: AtomicU64,
    worker: Option<JoinHandle<()>>,
}

fn profile_identity(profile: &Path, stop: Arc<AtomicBool>) -> Result<Identity> {
    fs::DirBuilder::new().recursive(true).mode(0o700).create(profile)?;
    let path = profile.join("identity.json");
    if path.is_file() { return Identity::load(&path); }
    let pending = profile.join("registration.pending");
    ensure!(!pending.exists(), "Registration outcome unknown. Preserve the profile before recovery.");
    let identity = Session::provision_controlled(config::SERVER_MODULUS, "RadminLAN iOS",
        config::DEFAULT_BOOTSTRAP_HOST, &output::ReportDirectory::disabled(), &mut || {
            let marker = OpenOptions::new().write(true).create(true).mode(0o600).open(&pending)?;
            marker.sync_all()?;
            Ok(())
        }, Some(stop), &mut |_| {})?;
    let temporary = profile.join("identity.new");
    let mut file = OpenOptions::new().write(true).create_new(true).mode(0o600).open(&temporary)?;
    let bytes = Zeroizing::new(serde_json::to_vec(&identity)?);
    file.write_all(&bytes)?;
    file.sync_all()?;
    fs::rename(temporary, path)?;
    fs::remove_file(pending)?;
    Ok(identity)
}

impl Core {
    fn start(profile: PathBuf) -> Self {
        let io = Arc::new(PacketIo::new());
        let stop = Arc::new(AtomicBool::new(false));
        let state = Arc::new(Mutex::new(State { status: json!({"phase":"connecting"}), operation: None }));
        let (commands, receiver) = mpsc::sync_channel(8);
        let options = Options { disable_interface: true, packet_io: Some(io.clone()), ..Options::default() };
        let worker_state = state.clone();
        let worker_stop = stop.clone();
        let worker = thread::spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<()> {
                let identity = profile_identity(&profile, worker_stop.clone())?;
                runtime::run(identity, config::SERVER_MODULUS.to_vec(), options, receiver, worker_stop.clone(), |event| {
                    let mut view = worker_state.lock().unwrap();
                    match event {
                        Update::State(snapshot) => {
                            view.status = json!({"phase":"online", "vip":snapshot.vip, "networks":snapshot.networks,
                                "connectedPeers": snapshot.peers.values().filter(|p| p.status == runtime::PeerState::Connected).count()});
                        }
                        Update::CommandResult {id, error, message, ..} => {
                            view.operation = Some(json!({"id": id, "ok": !error, "message": message}));
                        }
                        _ => {}
                    }
                })
            }));
            let error = match result {
                Ok(Ok(())) if worker_stop.load(Ordering::Relaxed) => None,
                Ok(Ok(())) => Some("Radmin session ended".to_string()),
                Ok(Err(error)) => Some(error.to_string()),
                Err(_) => Some("Radmin core stopped after an internal error".to_string()),
            };
            if let Ok(mut view) = worker_state.lock() {
                view.status = match error {
                    Some(message) => json!({"phase":"failed", "message":message}),
                    None => json!({"phase":"stopped"}),
                };
            }
        });
        Core { io, commands, stop, state, ticket: AtomicU64::new(1), worker: Some(worker) }
    }
}

#[no_mangle]
pub unsafe extern "C" fn rlc_start(profile: *const c_char) -> *mut Core {
    if profile.is_null() { return std::ptr::null_mut(); }
    let Ok(path) = CStr::from_ptr(profile).to_str() else { return std::ptr::null_mut(); };
    if path.is_empty() || path.len() > 4096 { return std::ptr::null_mut(); }
    Box::into_raw(Box::new(Core::start(PathBuf::from(path))))
}

#[no_mangle]
pub unsafe extern "C" fn rlc_stop(handle: *mut Core) {
    if handle.is_null() { return; }
    let mut core = Box::from_raw(handle);
    core.stop.store(true, Ordering::Relaxed);
    if let Some(worker) = core.worker.take() { let _ = worker.join(); }
}

#[no_mangle]
pub unsafe extern "C" fn rlc_send(handle: *mut Core, bytes: *const u8, length: usize) -> bool {
    let Some(core) = handle.as_ref() else { return false; };
    if bytes.is_null() || !(20..=MTU).contains(&length) { return false; }
    let packet = std::slice::from_raw_parts(bytes, length);
    ipv4(packet) && core.io.input.try_send(packet.to_vec()).is_ok()
}

#[no_mangle]
pub unsafe extern "C" fn rlc_receive(handle: *mut Core, bytes: *mut u8, capacity: usize) -> usize {
    let Some(core) = handle.as_ref() else { return 0; };
    if bytes.is_null() || capacity < MTU { return 0; }
    let Ok(reader) = core.io.incoming.lock() else { return 0; };
    let Ok(packet) = reader.try_recv() else { return 0; };
    std::ptr::copy_nonoverlapping(packet.as_ptr(), bytes, packet.len());
    packet.len()
}

#[no_mangle]
pub unsafe extern "C" fn rlc_status(handle: *mut Core, bytes: *mut u8, capacity: usize) -> usize {
    let Some(core) = handle.as_ref() else { return 0; };
    let Ok(state) = core.state.lock() else { return 0; };
    let Ok(value) = serde_json::to_vec(&json!({"status":state.status,"operation":state.operation})) else { return 0; };
    if bytes.is_null() || capacity < value.len() { return value.len(); }
    std::ptr::copy_nonoverlapping(value.as_ptr(), bytes, value.len());
    value.len()
}

#[derive(Deserialize)]
struct Action { action: String, target: String, password: Option<String> }

fn parse_action(data: &[u8]) -> Result<NetworkRequest> {
    let action: Action = serde_json::from_slice(data).context("Invalid in-app request")?;
    let password = action.password.filter(|p| !p.is_empty()).map(NetworkPassword::new).transpose()?;
    match action.action.as_str() {
        "join" => Ok(NetworkRequest::Join { name: action.target, password }),
        "create" => Ok(NetworkRequest::Create { name: action.target, password: password.context("Password required")? }),
        "leave" => Ok(NetworkRequest::Leave { network: action.target }),
        "delete" => Ok(NetworkRequest::Delete { network: action.target }),
        _ => anyhow::bail!("Unknown in-app request"),
    }
}

#[no_mangle]
pub unsafe extern "C" fn rlc_manage(handle: *mut Core, data: *const u8, length: usize) -> u64 {
    let Some(core) = handle.as_ref() else { return 0; };
    if data.is_null() || length == 0 || length > 4096 { return 0; }
    let Ok(request) = parse_action(std::slice::from_raw_parts(data, length)) else { return 0; };
    let ticket = core.ticket.fetch_add(1, Ordering::Relaxed);
    let command = Command::Tagged { id: ticket, command: Box::new(Command::Network(request)) };
    if core.commands.try_send(command).is_ok() { ticket } else { 0 }
}
