//! Fixed one-second windows: at most `per_ip` events per source IPv4 address and `global` in total
//! (ERROR replies, SPEC 1.3; new pairing attempts). Bounded memory: the per-address table is
//! cleared every window. Port of RateLimiter.cs.

use std::collections::HashMap;
use std::net::SocketAddr;

const WINDOW_US: i64 = 1_000_000;
const MAX_TRACKED_IPS: usize = 1024;

pub struct RateLimiter {
    per_ip: u32,
    global: u32,
    counts: HashMap<u32, u32>,
    window_start_us: Option<i64>,
    global_count: u32,
}

impl RateLimiter {
    pub fn new(per_ip: u32, global: u32) -> RateLimiter {
        RateLimiter { per_ip, global, counts: HashMap::with_capacity(MAX_TRACKED_IPS), window_start_us: None, global_count: 0 }
    }

    pub fn try_acquire_from(&mut self, from: SocketAddr, now_us: i64) -> bool {
        self.try_acquire(ipv4_key(from), now_us)
    }

    pub fn try_acquire(&mut self, ip_key: u32, now_us: i64) -> bool {
        // A new window also when the clock went backwards, so a limiter can never stay shut.
        let fresh = match self.window_start_us {
            None => true,
            Some(start) => now_us < start || now_us - start >= WINDOW_US,
        };
        if fresh {
            self.window_start_us = Some(now_us);
            self.global_count = 0;
            self.counts.clear();
        }
        if self.global_count >= self.global {
            return false;
        }
        let c = self.counts.get(&ip_key).copied().unwrap_or(0);
        if c >= self.per_ip {
            return false;
        }
        if c == 0 && self.counts.len() >= MAX_TRACKED_IPS {
            return false;
        }
        self.counts.insert(ip_key, c + 1);
        self.global_count += 1;
        true
    }
}

/// The address a sender is counted by: its IPv4 address; every IPv6 sender shares key 0 (the host
/// listens on IPv4 only).
pub fn ipv4_key(a: SocketAddr) -> u32 {
    match a {
        SocketAddr::V4(v4) => u32::from_le_bytes(v4.ip().octets()),
        SocketAddr::V6(_) => 0,
    }
}
