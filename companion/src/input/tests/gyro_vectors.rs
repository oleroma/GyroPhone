//! The gyro math must give exactly what the phone's GyroMath.kt gives: every case of the shared
//! gyro-math-v1.json replayed step by step, mouse and wheel counts equal and the game pad axes
//! equal to the bit (the same checks as the companion's GyroMathVectorTests).

mod common;
use common::*;

use gp_input::gyro::*;
use gp_input::state_frame::StateFrame;
use gp_input::wire;
use serde_json::Value;

/// A float as the Kotlin side wrote it: its exact bits ("nameBits") when there, else the number.
fn f(e: &Value, name: &str) -> f32 {
    match e.get(format!("{name}Bits")) {
        Some(bits) => f32::from_bits(bits.as_i64().unwrap() as i32 as u32),
        None => e[name].as_f64().unwrap() as f32,
    }
}

fn floats(e: &Value, name: &str) -> Vec<f32> {
    match e.get(format!("{name}Bits")) {
        Some(bits) => bits.as_array().unwrap().iter().map(|v| f32::from_bits(v.as_i64().unwrap() as i32 as u32)).collect(),
        None => e[name].as_array().unwrap().iter().map(|v| v.as_f64().unwrap() as f32).collect(),
    }
}

fn bools(e: &Value, name: &str) -> Vec<bool> {
    e[name].as_array().unwrap().iter().map(|v| v.as_bool().unwrap()).collect()
}

fn config(c: &Value) -> GyroConfig {
    let mut cfg = GyroConfig::default();
    cfg.sensitivity.copy_from_slice(&floats(c, "sensitivity"));
    cfg.inertia.copy_from_slice(&bools(c, "inertia"));
    cfg.glide_ms.copy_from_slice(&floats(c, "glideMs"));
    cfg.linear.copy_from_slice(&bools(c, "linear"));
    for r in c["routes"].as_array().unwrap() {
        assert!(cfg.add_route(Route { axis: r["axis"].as_u64().unwrap() as u8, target: r["target"].as_u64().unwrap() as u8, scale: f(r, "scale") }));
    }
    if let Some(curves) = c.get("curvesBits") {
        let axis_speed = bools(c, "axisSpeed");
        let smooth = if c.get("smooth").is_some() { bools(c, "smooth") } else { vec![false; 3] };
        for (a, curve) in curves.as_array().unwrap().iter().enumerate() {
            let flat: Vec<f32> = curve.as_array().unwrap().iter().map(|v| f32::from_bits(v.as_i64().unwrap() as i32 as u32)).collect();
            cfg.set_curve(a, &flat, axis_speed[a], smooth[a]);
        }
    }
    cfg
}

#[test]
fn every_case_matches_the_kotlin_math() {
    let root = vectors("gyro-math-v1.json");
    let cases = root["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 23);
    let mut steps = 0;
    for k in cases {
        let name = text(k, "name");
        let cfg = config(&k["config"]);
        let tightening = f(k, "tightening");
        let smoothing = f(k, "smoothing");
        let mut math = GyroMath::default();
        for (i, s) in k["steps"].as_array().unwrap().iter().enumerate() {
            let o = math.step(f(s, "yaw"), f(s, "pitch"), f(s, "roll"), f(s, "dt"), &cfg, num(s, "axes") as u8, tightening, smoothing);
            let at = format!("{name} step {i}");
            assert_eq!(num(s, "dx") as i32, o.dx, "{at}: dx");
            assert_eq!(num(s, "dy") as i32, o.dy, "{at}: dy");
            assert_eq!(num(s, "wheel") as i32, o.wheel, "{at}: wheel");
            assert_eq!(num(s, "hwheel") as i32, o.hwheel, "{at}: hwheel");
            let pad = floats(s, "pad");
            assert_eq!(pad.len(), PAD_AXES);
            for a in 0..PAD_AXES {
                assert_eq!(pad[a].to_bits(), o.pad[a].to_bits(), "{at}: pad[{a}] {} vs Kotlin {}", o.pad[a], pad[a]);
            }
            assert_eq!(s["coasting"].as_bool().unwrap(), math.coasting(), "{at}: coasting");
            steps += 1;
        }
    }
    assert_eq!(steps, 757);
}

#[test]
fn every_space_case_matches_the_kotlin_math() {
    let root = vectors("gyro-math-v1.json");
    let spaces = root["spaces"].as_array().unwrap();
    assert_eq!(spaces.len(), 64);
    for (i, k) in spaces.iter().enumerate() {
        let (inp, up, want) = (floats(k, "in"), floats(k, "up"), floats(k, "out"));
        let o = in_space(inp[0], inp[1], inp[2], num(k, "space") as u8, up[0], up[1], up[2]);
        for a in 0..3 {
            assert_eq!(want[a].to_bits(), o[a].to_bits(), "space case {i} axis {a}: {} vs Kotlin {}", o[a], want[a]);
        }
    }
}

#[test]
fn tail_vector_decodes_and_encodes_the_same() {
    let root = vectors("gyro-math-v1.json");
    let tail = &root["tail"];
    let raw = bytes(tail, "hex");
    let b: Vec<u8> = if raw.len() == wire::STATE_GYRO_PLAIN { raw[wire::STATE_HIRES_PLAIN..].to_vec() } else { raw.clone() };
    assert_eq!(b.len(), wire::PC_GYRO_TAIL_SIZE);
    let mut t = PcGyroTail::default();
    t.decode(&b);
    let mut again = vec![0u8; wire::PC_GYRO_TAIL_SIZE];
    t.encode(&mut again);
    assert_eq!(hex(&b), hex(&again));
    if raw.len() == wire::STATE_GYRO_PLAIN {
        let mut fr = StateFrame::default();
        fr.decode(&raw);
        assert!(fr.has_pc_gyro && fr.hires);
        assert_eq!(fr.tail, t);
    }

    assert_eq!(num(tail, "flags") as u8, t.flags);
    assert_eq!(num(tail, "n") as usize, t.count);
    assert_eq!(num(tail, "seq") as u16, t.newest_seq);
    assert_eq!(num(tail, "timeUs") as u32, t.newest_time_us);
    let totals: Vec<i64> = tail["totals"].as_array().unwrap().iter().map(|v| v.as_i64().unwrap()).collect();
    assert_eq!(totals, t.totals);
    let samples = tail["samples"].as_array().unwrap();
    assert_eq!(samples.len(), t.count);
    for (i, s) in samples.iter().enumerate() {
        assert_eq!(num(s, "dtUs") as u16, t.dt_us[i]);
        assert_eq!([num(s, "yaw") as i16, num(s, "pitch") as i16, num(s, "roll") as i16], t.rate[i]);
        assert_eq!(num(s, "axes") as u8, t.axes[i]);
        let up: Vec<i16> = s["up"].as_array().unwrap().iter().map(|v| v.as_i64().unwrap() as i16).collect();
        assert_eq!(up, t.up[i]);
    }
    let c = &tail["config"];
    assert_eq!(floats(c, "sensitivity"), t.config.sensitivity);
    assert_eq!(floats(c, "glideMs"), t.config.glide_ms);
    let gb = num(c, "glideBits");
    for a in 0..3 {
        assert_eq!(gb & (1 << a) != 0, t.config.inertia[a]);
        assert_eq!(gb & (1 << (3 + a)) != 0, t.config.linear[a]);
    }
    assert_eq!(num(c, "routeCount") as usize, t.config.route_count);
    assert_eq!(f(c, "tightening"), t.tightening);
    assert_eq!(f(c, "smoothing"), t.smoothing);
    let routes: Vec<Route> = c["routes"].as_array().unwrap().iter().map(|r| Route { axis: num(r, "axis") as u8, target: num(r, "target") as u8, scale: f(r, "scale") }).collect();
    assert_eq!(routes, t.config.routes());
    let space: Vec<u8> = c["space"].as_array().unwrap().iter().map(|v| v.as_u64().unwrap() as u8).collect();
    assert_eq!(space, t.config.space);
    for (a, curve) in c["curves"].as_array().unwrap().iter().enumerate() {
        let q: Vec<i64> = curve["q"].as_array().unwrap().iter().map(|v| v.as_i64().unwrap()).collect();
        assert_eq!(q.len() / 2, t.config.curve_points[a]);
        assert_eq!(curve["axisSpeed"].as_bool().unwrap(), t.config.axis_speed[a]);
        assert_eq!(curve["smooth"].as_bool().unwrap(), t.config.smooth[a]);
        for j in 0..q.len() / 2 {
            assert_eq!(q[2 * j] as f32 / 10.0, t.config.curves[a][2 * j]);
            assert_eq!(q[2 * j + 1] as f32 / 1000.0, t.config.curves[a][2 * j + 1]);
        }
    }
}

#[test]
fn rounding_helpers_match_kotlin() {
    assert_eq!(round_to_int(0.5), 1);
    assert_eq!(round_to_int(-0.5), 0);
    assert_eq!(round_to_int(-0.50001), -1);
    assert_eq!(round_to_int(2.5), 3);
    assert_eq!(round_to_int(-2.5), -2);
    assert_eq!(round_to_int(f32::NAN), 0);
    assert_eq!(round_to_int(1e20), i32::MAX);
    assert_eq!(round_to_int(-1e20), i32::MIN);
    assert_eq!(to_int(-1.9), -1);
    assert_eq!(to_int(1.9), 1);
    assert_eq!(to_int(f32::NAN), 0);
    assert_eq!(to_int(f32::INFINITY), i32::MAX);
    assert_eq!(to_int(f32::NEG_INFINITY), i32::MIN);
}
