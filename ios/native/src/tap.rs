pub const DEFAULT_LINUX_INTERFACE_NAME: &str = "radminlan0";
#[cfg(target_os = "linux")]
impl Tap {
    pub fn from_descriptor_named(_: std::fs::File, _: &str) -> Result<Self> { bail!("No direct TAP") }
    pub fn create_lan_with_helper_cancellable_named(_: Ipv4Addr, _: &std::path::Path, _: &std::sync::atomic::AtomicBool, _: &str) -> Result<Self> {
        bail!("No direct TAP")
    }
}
use anyhow::{bail, Result};
use std::net::Ipv4Addr;
pub const NAME: &str = "radminvpn0";
pub struct Tap;
impl Tap {
    pub fn create(_: Ipv4Addr, _: &[Ipv4Addr]) -> Result<Self> {
        bail!("The VPN data plane is implemented and tested on Linux only")
    }
    pub fn create_with_helper(_: Ipv4Addr, _: &[Ipv4Addr], _: &std::path::Path) -> Result<Self> {
        bail!("The VPN data plane is implemented and tested on Linux only")
    }
    pub fn create_lan_with_helper(_: Ipv4Addr, _: &std::path::Path) -> Result<Self> {
        bail!("The VPN data plane is implemented and tested on Linux only")
    }
    pub fn ready(&self, _: i32) -> Result<bool> {
        bail!("VPN interface unavailable on this platform")
    }
    pub(crate) fn poll_fd(&self) -> Option<i32> {
        None
    }
    pub fn receive(&mut self) -> Result<Vec<u8>> {
        bail!("VPN interface unavailable on this platform")
    }
    pub fn send(&mut self, _: &[u8]) -> Result<()> {
        bail!("VPN interface unavailable on this platform")
    }
}
pub fn helper(_: Ipv4Addr, _: u32, _: &[Ipv4Addr]) -> Result<()> {
    bail!("The TAP helper is Linux only")
}
pub fn helper_with_lan(_: Ipv4Addr, _: u32, _: &[Ipv4Addr], _: bool) -> Result<()> {
    bail!("The TAP helper is Linux only")
}
