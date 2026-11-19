//! Per-session link statistics, updated as packets arrive and rolled once a second by the
//! watchdog. One-way latency goes into a 512-bucket, 50 µs histogram once the phone has reported a
//! clock offset; jitter is the Welford standard deviation of STATE inter-arrival times; loss comes
//! from gaps in the shared phone to host seq. Port of LinkStats.cs.

use crate::codec::LinkInfo;
use crate::wire;

pub const BUCKET_US: i64 = 50;
pub const BUCKETS: usize = 512;

pub struct LinkStats {
    hist: [u32; BUCKETS],
    hist_count: u32,
    have_offset: bool,
    clock_offset_us: i64,

    have_seq: bool,
    last_seq: u32,
    rx_sealed: u32,
    rx_states: u32,
    lost: u32,

    last_arrival_us: Option<i64>,
    n: u64,
    mean: f64,
    m2: f64,

    pub rx_per_sec: u16,
    pub loss_permille: u16,
    pub lat_p50_us: u32,
    pub lat_p99_us: u32,
    pub jitter_us: u32,
    pub total_lost: i64,
    pub total_states: i64,
    pub last_link_info: Option<LinkInfo>,
}

impl Default for LinkStats {
    fn default() -> Self {
        LinkStats {
            hist: [0; BUCKETS],
            hist_count: 0,
            have_offset: false,
            clock_offset_us: 0,
            have_seq: false,
            last_seq: 0,
            rx_sealed: 0,
            rx_states: 0,
            lost: 0,
            last_arrival_us: None,
            n: 0,
            mean: 0.0,
            m2: 0.0,
            rx_per_sec: 0,
            loss_permille: 0,
            lat_p50_us: wire::STATUS_UNKNOWN,
            lat_p99_us: wire::STATUS_UNKNOWN,
            jitter_us: 0,
            total_lost: 0,
            total_states: 0,
            last_link_info: None,
        }
    }
}

impl LinkStats {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn last_seq(&self) -> u32 {
        self.last_seq
    }

    pub fn reset(&mut self) {
        *self = Self::default();
    }

    /// Every accepted sealed phone to host packet (seq is shared by all types).
    pub fn on_sealed(&mut self, seq: u32) {
        if self.have_seq && seq as u64 > self.last_seq as u64 + 1 {
            let gap = seq as u64 - self.last_seq as u64 - 1;
            self.lost = self.lost.saturating_add(gap.min(u32::MAX as u64 / 4) as u32);
            self.total_lost = self.total_lost.saturating_add(gap as i64);
        }
        self.have_seq = true;
        self.last_seq = seq;
        self.rx_sealed = self.rx_sealed.saturating_add(1);
    }

    /// Records one accepted STATE. Returns its one-way latency in µs, or None before the first
    /// LINK_INFO.
    pub fn on_state(&mut self, phone_time_us: u32, host_recv_us: i64) -> Option<i64> {
        self.rx_states = self.rx_states.saturating_add(1);
        self.total_states = self.total_states.saturating_add(1);
        if let Some(last) = self.last_arrival_us {
            let x = host_recv_us.wrapping_sub(last) as f64;
            self.n += 1;
            let d = x - self.mean;
            self.mean += d / self.n as f64;
            self.m2 += d * (x - self.mean);
        }
        self.last_arrival_us = Some(host_recv_us);
        if !self.have_offset {
            return None;
        }
        let lat = latency_us(phone_time_us, host_recv_us, self.clock_offset_us);
        let b = (lat / BUCKET_US).clamp(0, BUCKETS as i64 - 1) as usize;
        self.hist[b] += 1;
        self.hist_count += 1;
        Some(lat)
    }

    pub fn on_link_info(&mut self, info: &LinkInfo) {
        self.clock_offset_us = info.clock_offset_us;
        self.have_offset = true;
        self.last_link_info = Some(*info);
    }

    /// Closes the current one-second window.
    pub fn roll(&mut self) {
        self.rx_per_sec = self.rx_states.min(u16::MAX as u32) as u16;
        let total = self.rx_sealed as u64 + self.lost as u64;
        self.loss_permille = if total == 0 { 0 } else { (self.lost as u64 * 1000 / total).min(1000) as u16 };
        if self.hist_count > 0 {
            self.lat_p50_us = self.percentile(0.50);
            self.lat_p99_us = self.percentile(0.99);
        } else {
            self.lat_p50_us = wire::STATUS_UNKNOWN;
            self.lat_p99_us = wire::STATUS_UNKNOWN;
        }
        // `as u32` saturates at the ends and turns a NaN into 0.
        self.jitter_us = if self.n > 1 { (self.m2 / (self.n - 1) as f64).sqrt() as u32 } else { 0 };
        self.hist = [0; BUCKETS];
        self.hist_count = 0;
        self.rx_sealed = 0;
        self.rx_states = 0;
        self.lost = 0;
        self.n = 0;
        self.mean = 0.0;
        self.m2 = 0.0;
    }

    fn percentile(&self, p: f64) -> u32 {
        let rank = (p * self.hist_count as f64).ceil() as u64;
        let mut acc = 0u64;
        for (i, &h) in self.hist.iter().enumerate() {
            acc += h as u64;
            if acc >= rank {
                return (i as i64 * BUCKET_US + BUCKET_US / 2) as u32;
            }
        }
        (BUCKETS as i64 * BUCKET_US) as u32
    }
}

/// latency = host_recv_us - (unwrap(phone_time_us) + offset), where the 32-bit phone time is
/// unwrapped to the value nearest host_recv_us - offset.
pub fn latency_us(phone_time_us: u32, host_recv_us: i64, clock_offset_us: i64) -> i64 {
    let expected_phone = host_recv_us.wrapping_sub(clock_offset_us);
    let diff = phone_time_us.wrapping_sub(expected_phone as u32) as i32;
    let phone_us = expected_phone.wrapping_add(diff as i64);
    host_recv_us.wrapping_sub(phone_us.wrapping_add(clock_offset_us))
}
