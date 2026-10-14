//! Value handles packed into an `i64`, so that the common "evaluate a path and
//! look at what came back" round trip is a single JNI call returning a
//! primitive.
//!
//! ```text
//! bit  0..=31  node reference (tape index, or virtual-array id with bit 31 set)
//! bit  32      COPY      - value came out of a path evaluation (see `eval` docs)
//! bit  33      UNWRAPPED - number representation, see `doc::Repr`
//! bit  34..=36 kind      - `doc::Kind`
//! bit  37      REPARSED  - number representation, see `doc::Repr`
//! ```
//!
//! Negative values are status codes: [`NOT_FOUND`], [`UNSUPPORTED`].

use std::borrow::Cow;

use crate::doc::{Doc, Item, Kind, NodeRef, Repr};
use crate::eval::{self, Outcome};
use crate::path::Path;

/// Jayway would have thrown `PathNotFoundException`.
pub const NOT_FOUND: i64 = -1;
/// The native engine cannot answer this; replay the request on the Java engine.
pub const UNSUPPORTED: i64 = -2;

pub const COPY_BIT: i64 = 1 << 32;
pub const UNWRAPPED_BIT: i64 = 1 << 33;
pub const KIND_SHIFT: u32 = 34;
pub const REPARSED_BIT: i64 = 1 << 37;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Handle {
    pub item: Item,
    pub copy: bool,
}

#[inline]
pub fn encode(doc: &Doc, item: Item, copy: bool) -> i64 {
    (item.node.0 as i64)
        | if copy || item.node.is_virtual() {
            COPY_BIT
        } else {
            0
        }
        | match item.repr {
            Repr::Raw => 0,
            Repr::Unwrapped => UNWRAPPED_BIT,
            Repr::Reparsed => REPARSED_BIT,
        }
        | ((doc.kind(item.node) as i64) << KIND_SHIFT)
}

#[inline]
pub fn decode(doc: &Doc, h: i64) -> Option<Handle> {
    if h < 0 {
        return None;
    }
    let node = NodeRef((h & 0xFFFF_FFFF) as u32);
    if !doc.is_valid(node) {
        return None;
    }
    let repr = if h & REPARSED_BIT != 0 {
        Repr::Reparsed
    } else if h & UNWRAPPED_BIT != 0 {
        Repr::Unwrapped
    } else {
        Repr::Raw
    };
    Some(Handle {
        item: Item { node, repr },
        copy: h & COPY_BIT != 0 || node.is_virtual(),
    })
}

#[inline]
pub fn kind_of(h: i64) -> Option<Kind> {
    if h < 0 {
        return None;
    }
    Some(match (h >> KIND_SHIFT) & 7 {
        0 => Kind::Null,
        1 => Kind::False,
        2 => Kind::True,
        3 => Kind::Number,
        4 => Kind::String,
        5 => Kind::Array,
        6 => Kind::Object,
        _ => return None,
    })
}

/// Handle of the document root (not a copy).
pub fn root(doc: &Doc) -> i64 {
    let r = doc.root();
    if doc.is_valid(r) {
        encode(doc, Item::raw(r), false)
    } else {
        UNSUPPORTED
    }
}

/// Evaluate `path` relative to `ctx`.
pub fn eval(doc: &mut Doc, ctx: i64, path: &Path) -> i64 {
    let Some(c) = decode(doc, ctx) else {
        return UNSUPPORTED;
    };
    match eval::eval(doc, path, c.item, c.copy) {
        Err(eval::Unsupported) => UNSUPPORTED,
        Ok(Outcome::NotFound) => NOT_FOUND,
        // whatever a path returns is a copy in the legacy engine
        Ok(Outcome::Single(item)) => encode(doc, item, true),
        Ok(Outcome::Multi(v)) => encode(doc, Item::raw(v), true),
    }
}

/// Elements of an array value exactly as they are stored in it (the mapper
/// consumes list items directly). `None` if `h` is not an array.
pub fn elements(doc: &Doc, h: i64) -> Option<Vec<i64>> {
    let c = decode(doc, h)?;
    if !doc.is_array(c.item.node) {
        return None;
    }
    let mut out = Vec::new();
    let _ = doc.for_each_element::<()>(c.item.node, |item| {
        out.push(encode(doc, item, c.copy));
        Ok(())
    });
    Some(out)
}

/// `[key0, value0, key1, value1, ...]` of an object value (diagnostics / tests).
/// In copy mode, members whose value is null are omitted.
pub fn members(doc: &Doc, h: i64) -> Option<Vec<i64>> {
    let c = decode(doc, h)?;
    if !doc.is_object(c.item.node) {
        return None;
    }
    let mut out = Vec::new();
    let _ = doc.for_each_member::<()>(c.item.node, |k, v| {
        if !(c.copy && doc.kind(v) == Kind::Null) {
            // nested content is never unwrapped, only the leaf of a path is
            out.push(encode(doc, Item::raw(k), c.copy));
            out.push(encode(doc, Item::raw(v), c.copy));
        }
        Ok(())
    });
    Some(out)
}

/// Decoded string value, or the raw text of a number.
/// `None` for other kinds and for strings Rust cannot represent (lone surrogates).
pub fn text(doc: &Doc, h: i64) -> Option<Cow<'_, str>> {
    let c = decode(doc, h)?;
    match doc.kind(c.item.node) {
        Kind::String => doc.string_value(c.item.node).ok(),
        Kind::Number => Some(Cow::Borrowed(doc.number_text(c.item.node))),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(s: &str) -> Path {
        Path::compile(s).unwrap()
    }

    #[test]
    fn round_trip() {
        let mut d = Doc::parse(br#"{"a":{"n":5,"s":"x","l":[1,"two",null,{"k":true}]}}"#).unwrap();
        let r = root(&d);
        assert_eq!(kind_of(r), Some(Kind::Object));
        assert_eq!(r & COPY_BIT, 0);

        let n = eval(&mut d, r, &p("$.a.n"));
        assert_eq!(kind_of(n), Some(Kind::Number));
        assert_ne!(n & COPY_BIT, 0);
        assert_ne!(n & UNWRAPPED_BIT, 0);
        assert_eq!(text(&d, n).unwrap(), "5");

        let s = eval(&mut d, r, &p("$.a.s"));
        assert_eq!(text(&d, s).unwrap(), "x");
        assert_eq!(eval(&mut d, r, &p("$.a.zzz")), NOT_FOUND);

        let l = eval(&mut d, r, &p("$.a.l[*]"));
        assert_eq!(kind_of(l), Some(Kind::Array));
        let els = elements(&d, l).unwrap();
        assert_eq!(
            els.iter().map(|&e| kind_of(e).unwrap()).collect::<Vec<_>>(),
            [Kind::Number, Kind::String, Kind::Null, Kind::Object]
        );
        assert_eq!(els[0] & (UNWRAPPED_BIT | REPARSED_BIT), 0); // array element => raw number
        assert_eq!(text(&d, els[1]).unwrap(), "two");
        assert!(text(&d, els[2]).is_none());
        assert!(elements(&d, n).is_none());

        // an element can be used as the context of the next evaluation
        assert_eq!(kind_of(eval(&mut d, els[3], &p("$.k"))), Some(Kind::True));

        let a = eval(&mut d, r, &p("$.a"));
        let m = members(&d, a).unwrap();
        assert_eq!(m.len(), 6);
        assert_eq!(text(&d, m[0]).unwrap(), "n");
    }

    #[test]
    fn number_representation_survives_the_encoding() {
        let mut d = Doc::parse(br#"{"o":{"q":1,"r":2}}"#).unwrap();
        let r = root(&d);
        let values = eval(&mut d, r, &p("$.o[*]"));
        // consumed directly as list items: still unwrapped
        let items = elements(&d, values).unwrap();
        assert!(items
            .iter()
            .all(|&i| i & UNWRAPPED_BIT != 0 && i & REPARSED_BIT == 0));
        // read out of the array used as a context: re-parsed
        let first = eval(&mut d, values, &p("$[0]"));
        assert_ne!(first & REPARSED_BIT, 0);
        assert_eq!(first & UNWRAPPED_BIT, 0);
        assert_eq!(decode(&d, first).unwrap().item.repr, Repr::Reparsed);
    }

    #[test]
    fn garbage_handles_are_rejected_not_dereferenced() {
        let mut d = Doc::parse(b"[1]").unwrap();
        for bad in [-1, -2, i64::MIN, 0xFFFF, (1 << 31) | 5, i64::MAX] {
            assert_eq!(eval(&mut d, bad, &p("$[0]")), UNSUPPORTED, "{bad}");
            assert!(elements(&d, bad).is_none());
            assert!(text(&d, bad).is_none());
        }
    }
}
