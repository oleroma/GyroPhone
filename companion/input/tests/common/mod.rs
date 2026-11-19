//! Shared by the test files: the golden vectors of core/src/test/resources/protocol/v1 and hex helpers.
#![allow(dead_code)]

use serde_json::Value;

pub fn vectors(name: &str) -> Value {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../core/src/test/resources/protocol/v1/");
    let text = std::fs::read_to_string(format!("{path}{name}")).expect("vector file");
    serde_json::from_str(&text).expect("vector json")
}

pub fn unhex(s: &str) -> Vec<u8> {
    assert!(s.len() % 2 == 0, "odd hex");
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).expect("hex")).collect()
}

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub fn bytes(v: &Value, prop: &str) -> Vec<u8> {
    unhex(v[prop].as_str().unwrap_or_else(|| panic!("no hex {prop}")))
}

pub fn arr<const N: usize>(v: &Value, prop: &str) -> [u8; N] {
    bytes(v, prop).try_into().unwrap_or_else(|_| panic!("{prop} is not {N} bytes"))
}

pub fn num(v: &Value, prop: &str) -> i64 {
    v[prop].as_i64().unwrap_or_else(|| panic!("no number {prop}"))
}

pub fn text<'a>(v: &'a Value, prop: &str) -> &'a str {
    v[prop].as_str().unwrap_or_else(|| panic!("no string {prop}"))
}
