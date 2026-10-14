//! Compact, zero-copy structural index ("tape") over a JSON document.
//!
//! # Why this exists
//!
//! The Java mapper used to build a full Gson tree of every YouTube response,
//! deep-copy every sub-tree a path returned, serialise that copy to text and
//! parse it again, at every nesting level of the model. Measured on the largest
//! recorded response (the subscriptions grid, 1.5 MB minified) that is about
//! 100 MB of allocations on the Java heap for a single response; even a 430 KB
//! "watch next" response costs 74 MB. On a 1 GB device that means a burst of
//! garbage collections and heap growth every time a screen loads.
//!
//! Here the document stays exactly as received (one `Vec<u8>`), and a parallel
//! *tape* records only the structure:
//!
//! * one 8-byte [`Entry`] per value **and per object key**,
//! * containers carry an "end" index, so skipping a whole sub-tree is O(1),
//! * strings and numbers are `(offset, length)` slices into the original bytes,
//!   nothing is decoded until a caller asks for that specific value.
//!
//! For minified JSON the tape is roughly 0.4-0.5x the size of the text and is
//! built in a single pass (about 1 ms per megabyte on a desktop CPU). The same
//! response then needs ~2.4 MB of native memory at peak and ~6 MB of Java
//! allocations, nearly all of which are the model objects themselves. Run
//! `cargo run --release --example probe` and `verify/` to reproduce.
//!
//! # Strictness
//!
//! The parser is deliberately strict (RFC 8259, plus tolerance for raw control
//! characters inside strings, which Gson also tolerates). Anything unusual -
//! comments, single quotes, NaN, a BOM, trailing garbage, invalid UTF-8, absurd
//! nesting - is rejected with a [`ParseError`]. The Java side reacts to *any*
//! native error by re-running the request on the legacy engine, so strictness
//! can never change behaviour, only which engine produced it.

use std::borrow::Cow;

/// Maximum container nesting accepted. Real YouTube payloads are < 100 deep.
pub const MAX_DEPTH: usize = 400;

const KIND_SHIFT: u32 = 29;
const START_MASK: u32 = (1 << KIND_SHIFT) - 1;

/// Largest document we index (start offsets must fit in 29 bits): 512 MiB - 1.
pub const MAX_DOC_LEN: usize = START_MASK as usize;

const T_NULL: u32 = 0;
const T_FALSE: u32 = 1;
const T_TRUE: u32 = 2;
const T_NUM: u32 = 3;
/// String without escapes: the content can be sliced out of the input as-is.
const T_STR: u32 = 4;
/// String containing at least one `\` escape.
const T_STR_ESC: u32 = 5;
const T_ARR: u32 = 6;
const T_OBJ: u32 = 7;

/// JSON value kind. The numeric values are part of the JNI contract.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[repr(u8)]
pub enum Kind {
    Null = 0,
    False = 1,
    True = 2,
    Number = 3,
    String = 4,
    Array = 5,
    Object = 6,
}

/// Reference to a value: either a tape index, or (high bit set) a *virtual
/// array* created by an indefinite path evaluation such as `$.items[*]`.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
pub struct NodeRef(pub u32);

pub const VIRTUAL_BIT: u32 = 1 << 31;

impl NodeRef {
    #[inline]
    pub fn is_virtual(self) -> bool {
        self.0 & VIRTUAL_BIT != 0
    }

    #[inline]
    pub fn index(self) -> usize {
        (self.0 & !VIRTUAL_BIT) as usize
    }
}

/// How the legacy engine would represent a value *if it is a number*. The Java
/// side needs this to build bit-identical `JsonPrimitive`s; for every other
/// kind of value it is irrelevant.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
#[repr(u8)]
pub enum Repr {
    /// Exactly as Gson parsed it: `LazilyParsedNumber(original text)`.
    #[default]
    Raw = 0,
    /// Read through an object property. Jayway's `GsonJsonProvider.getMapValue`
    /// unwraps such numbers to `Integer` / `Long` / `Double` / `BigDecimal`.
    Unwrapped = 1,
    /// An unwrapped number that has since been used *inside a context*. The old
    /// mapper serialised every returned value and parsed it again before
    /// reading paths from it, which turns `Integer(1500)` (from the text
    /// `1.5e3`) into `LazilyParsedNumber("1500")`.
    Reparsed = 2,
}

impl Repr {
    /// Effect of `toString()` + `parse()` on the representation.
    #[inline]
    pub fn reparsed(self) -> Repr {
        match self {
            Repr::Raw => Repr::Raw,
            _ => Repr::Reparsed,
        }
    }
}

/// A value together with its number representation (element of a virtual
/// array, result of an evaluation, or an evaluation context).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Item {
    pub node: NodeRef,
    pub repr: Repr,
}

impl Item {
    #[inline]
    pub fn raw(node: NodeRef) -> Item {
        Item {
            node,
            repr: Repr::Raw,
        }
    }
}

#[derive(Clone, Copy)]
struct Entry {
    /// `kind << 29 | start offset`
    a: u32,
    /// strings / numbers: byte length; containers: tape index just past the
    /// last descendant (== index of the next sibling); literals: unused.
    b: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ParseError {
    Empty,
    TooLarge,
    InvalidUtf8,
    /// Byte offset of the first offending byte.
    Syntax(usize),
    TooDeep,
}

impl ParseError {
    /// Stable numeric code for the JNI boundary (0 is reserved for "ok").
    pub fn code(&self) -> i32 {
        match self {
            ParseError::Empty => 1,
            ParseError::TooLarge => 2,
            ParseError::InvalidUtf8 => 3,
            ParseError::Syntax(_) => 4,
            ParseError::TooDeep => 5,
        }
    }
}

/// A string that cannot be represented as a Rust `String` (an unpaired UTF-16
/// surrogate written as `\uD800`). Java strings can hold those, Rust's cannot;
/// the caller falls back to the legacy engine rather than altering the text.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LoneSurrogate;

pub struct Doc {
    data: Vec<u8>,
    tape: Vec<Entry>,
    virt: Vec<Vec<Item>>,
    root: NodeRef,
}

impl Doc {
    pub fn with_capacity(cap: usize) -> Doc {
        Doc {
            data: Vec::with_capacity(cap),
            tape: Vec::new(),
            virt: Vec::new(),
            root: NodeRef(0),
        }
    }

    /// Convenience for tests and tools: copy `bytes` and index them.
    pub fn parse(bytes: &[u8]) -> Result<Doc, ParseError> {
        let mut d = Doc::with_capacity(bytes.len());
        d.append(bytes)?;
        d.index(false)?;
        Ok(d)
    }

    pub fn append(&mut self, chunk: &[u8]) -> Result<(), ParseError> {
        if self.data.len().saturating_add(chunk.len()) > MAX_DOC_LEN {
            return Err(ParseError::TooLarge);
        }
        self.make_room(chunk.len());
        self.data.extend_from_slice(chunk);
        Ok(())
    }

    /// The total size is not known up front (chunked / compressed transfer).
    /// Grow by 50% rather than by doubling: on a memory-starved device the
    /// slack of the last doubling is a megabyte or more that is never used.
    #[inline]
    fn make_room(&mut self, additional: usize) {
        if self.data.capacity() - self.data.len() < additional {
            self.data.reserve_exact(additional.max(self.data.len() / 2));
        }
    }

    /// Grow the input by `len` bytes and let `fill` write them (used by the JNI
    /// layer to copy straight out of a Java `byte[]`). If `fill` returns false
    /// the document is left unchanged.
    pub fn append_with(
        &mut self,
        len: usize,
        fill: impl FnOnce(&mut [u8]) -> bool,
    ) -> Result<bool, ParseError> {
        let old = self.data.len();
        if old.saturating_add(len) > MAX_DOC_LEN {
            return Err(ParseError::TooLarge);
        }
        self.make_room(len);
        self.data.resize(old + len, 0);
        if fill(&mut self.data[old..]) {
            Ok(true)
        } else {
            self.data.truncate(old);
            Ok(false)
        }
    }

    /// True if `n` refers to an existing tape entry or virtual array of *this*
    /// document. Handles arrive from Java as plain integers, so they are checked
    /// instead of trusted.
    #[inline]
    pub fn is_valid(&self, n: NodeRef) -> bool {
        if n.is_virtual() {
            n.index() < self.virt.len()
        } else {
            n.index() < self.tape.len()
        }
    }

    /// Build the tape over the appended bytes.
    ///
    /// With `skip_first_line` the first line (terminated by `\n`, `\r` or
    /// `\r\n`, exactly like `BufferedReader.readLine()`) is ignored. YouTube's
    /// XSSI-protected endpoints prefix the JSON with `)]}'`.
    pub fn index(&mut self, skip_first_line: bool) -> Result<(), ParseError> {
        let mut start = 0usize;
        if skip_first_line {
            start = match memchr::memchr2(b'\n', b'\r', &self.data) {
                Some(i) => {
                    if self.data[i] == b'\r' && self.data.get(i + 1) == Some(&b'\n') {
                        i + 2
                    } else {
                        i + 1
                    }
                }
                None => self.data.len(),
            };
        }

        if self.data.len() > MAX_DOC_LEN {
            return Err(ParseError::TooLarge);
        }
        if std::str::from_utf8(&self.data[start..]).is_err() {
            return Err(ParseError::InvalidUtf8);
        }

        // Everything has arrived: give back the growth slack of the input buffer.
        self.data.shrink_to_fit();

        let mut p = Parser {
            d: &self.data,
            pos: start,
            // Token density varies 10x between pretty-printed and minified JSON,
            // so a guess either wastes megabytes or forces the tape to be
            // reallocated (and copied) several times. Reserve an upper bound.
            tape: Vec::with_capacity(max_entries(&self.data[start..])),
        };
        p.run()?;
        let mut tape = p.tape;
        tape.shrink_to_fit();
        self.tape = tape;
        self.virt.clear();
        self.root = NodeRef(0);
        Ok(())
    }

    #[inline]
    pub fn root(&self) -> NodeRef {
        self.root
    }

    /// The raw input bytes, including any skipped first line.
    #[inline]
    pub fn data(&self) -> &[u8] {
        &self.data
    }

    /// Bytes currently held on the native heap (input + tape + virtual arrays).
    pub fn heap_bytes(&self) -> usize {
        self.data.capacity()
            + self.tape.capacity() * std::mem::size_of::<Entry>()
            + self
                .virt
                .iter()
                .map(|v| {
                    v.capacity() * std::mem::size_of::<Item>() + std::mem::size_of::<Vec<Item>>()
                })
                .sum::<usize>()
    }

    pub fn tape_len(&self) -> usize {
        self.tape.len()
    }

    // ------------------------------------------------------------------ nodes

    #[inline]
    fn entry(&self, n: NodeRef) -> Entry {
        self.tape[n.index()]
    }

    #[inline]
    pub fn kind(&self, n: NodeRef) -> Kind {
        if n.is_virtual() {
            return Kind::Array;
        }
        match self.entry(n).a >> KIND_SHIFT {
            T_NULL => Kind::Null,
            T_FALSE => Kind::False,
            T_TRUE => Kind::True,
            T_NUM => Kind::Number,
            T_STR | T_STR_ESC => Kind::String,
            T_ARR => Kind::Array,
            _ => Kind::Object,
        }
    }

    #[inline]
    pub fn is_array(&self, n: NodeRef) -> bool {
        self.kind(n) == Kind::Array
    }

    #[inline]
    pub fn is_object(&self, n: NodeRef) -> bool {
        self.kind(n) == Kind::Object
    }

    /// Tape index of the entry after `i` and all of its descendants.
    #[inline]
    fn skip(&self, i: u32) -> u32 {
        let e = self.tape[i as usize];
        match e.a >> KIND_SHIFT {
            T_ARR | T_OBJ => e.b,
            _ => i + 1,
        }
    }

    #[inline]
    fn slice(&self, e: Entry) -> &[u8] {
        let start = (e.a & START_MASK) as usize;
        &self.data[start..start + e.b as usize]
    }

    /// Raw text of a number node (empty for any other kind).
    pub fn number_text(&self, n: NodeRef) -> &str {
        if n.is_virtual() {
            return "";
        }
        let e = self.entry(n);
        if e.a >> KIND_SHIFT != T_NUM {
            return "";
        }
        // The whole input was validated as UTF-8 and number text is pure ASCII.
        std::str::from_utf8(self.slice(e)).unwrap_or("")
    }

    /// Decoded value of a string node.
    ///
    /// Strings without escapes borrow directly from the input.
    pub fn string_value(&self, n: NodeRef) -> Result<Cow<'_, str>, LoneSurrogate> {
        if n.is_virtual() {
            return Ok(Cow::Borrowed(""));
        }
        let e = self.entry(n);
        match e.a >> KIND_SHIFT {
            T_STR => Ok(Cow::Borrowed(
                std::str::from_utf8(self.slice(e)).unwrap_or(""),
            )),
            T_STR_ESC => unescape(self.slice(e)).map(Cow::Owned),
            _ => Ok(Cow::Borrowed("")),
        }
    }

    // ------------------------------------------------------------- containers

    /// Number of elements (walks the children; O(n) for real arrays).
    pub fn array_len(&self, n: NodeRef) -> usize {
        if n.is_virtual() {
            return self.virt[n.index()].len();
        }
        let e = self.entry(n);
        let (mut i, end) = (n.0 + 1, e.b);
        let mut count = 0;
        while i < end {
            i = self.skip(i);
            count += 1;
        }
        count
    }

    /// The `idx`-th element of an array (O(idx) for real arrays).
    pub fn array_get(&self, n: NodeRef, idx: usize) -> Option<Item> {
        if n.is_virtual() {
            return self.virt[n.index()].get(idx).copied();
        }
        let e = self.entry(n);
        let (mut i, end) = (n.0 + 1, e.b);
        let mut k = 0;
        while i < end {
            if k == idx {
                return Some(Item::raw(NodeRef(i)));
            }
            i = self.skip(i);
            k += 1;
        }
        None
    }

    /// Visit every element of an array, in order.
    pub fn for_each_element<E>(
        &self,
        n: NodeRef,
        mut f: impl FnMut(Item) -> Result<(), E>,
    ) -> Result<(), E> {
        if n.is_virtual() {
            for it in &self.virt[n.index()] {
                f(*it)?;
            }
            return Ok(());
        }
        let e = self.entry(n);
        let (mut i, end) = (n.0 + 1, e.b);
        while i < end {
            f(Item::raw(NodeRef(i)))?;
            i = self.skip(i);
        }
        Ok(())
    }

    /// Visit every `(key, value)` pair of an object, in document order
    /// (duplicates included).
    pub fn for_each_member<E>(
        &self,
        n: NodeRef,
        mut f: impl FnMut(NodeRef, NodeRef) -> Result<(), E>,
    ) -> Result<(), E> {
        if n.is_virtual() {
            return Ok(());
        }
        let e = self.entry(n);
        let (mut i, end) = (n.0 + 1, e.b);
        while i < end {
            let v = i + 1;
            f(NodeRef(i), NodeRef(v))?;
            i = self.skip(v);
        }
        Ok(())
    }

    /// Value of `key`, with the semantics of `JsonObject.add` (last duplicate wins).
    pub fn object_get(&self, n: NodeRef, key: &str) -> Option<NodeRef> {
        if n.is_virtual() {
            return None;
        }
        let e = self.entry(n);
        let (mut i, end) = (n.0 + 1, e.b);
        let mut found = None;
        while i < end {
            let v = i + 1;
            if self.key_eq(i, key) {
                found = Some(NodeRef(v));
            }
            i = self.skip(v);
        }
        found
    }

    /// True if two members of the object share a key. Only called for the rare
    /// "iterate an object's values" case, where Gson's "replace in place"
    /// semantics for duplicates would otherwise have to be emulated.
    pub fn object_has_duplicate_keys(&self, n: NodeRef) -> bool {
        if n.is_virtual() {
            return false;
        }
        let mut keys: Vec<Cow<'_, str>> = Vec::new();
        let _ = self.for_each_member::<()>(n, |k, _| {
            match self.string_value(k) {
                Ok(s) => keys.push(s),
                // a key with a lone surrogate: be conservative
                Err(_) => keys.push(Cow::Borrowed("\u{0}<lone-surrogate>")),
            }
            Ok(())
        });
        keys.sort_unstable();
        keys.windows(2).any(|w| w[0] == w[1])
    }

    fn key_eq(&self, key_idx: u32, key: &str) -> bool {
        let e = self.tape[key_idx as usize];
        match e.a >> KIND_SHIFT {
            T_STR => self.slice(e) == key.as_bytes(),
            T_STR_ESC => match unescape(self.slice(e)) {
                Ok(s) => s == key,
                Err(_) => false,
            },
            _ => false,
        }
    }

    // ---------------------------------------------------------------- virtual

    /// Register the result of an indefinite path as a virtual array.
    pub fn new_virtual(&mut self, items: Vec<Item>) -> NodeRef {
        self.virt.push(items);
        NodeRef(VIRTUAL_BIT | (self.virt.len() - 1) as u32)
    }

    pub fn virtual_items(&self, n: NodeRef) -> &[Item] {
        if n.is_virtual() {
            &self.virt[n.index()]
        } else {
            &[]
        }
    }
}

/// Upper bound on the number of tape entries of a well-formed document.
///
/// * every string (key or value) contains at least two `"` bytes, so there are
///   at most `quotes / 2` of them;
/// * every other value is the root, or directly follows a `:`, `,` or `[`.
///
/// Bytes inside strings only make the bound looser. `memchr`'s counter is
/// vectorised, so this costs a small fraction of the parse itself.
fn max_entries(d: &[u8]) -> usize {
    let count = |b: u8| memchr::memchr_iter(b, d).count();
    count(b'"') / 2 + count(b':') + count(b',') + count(b'[') + 1
}

/// Decode the content of a JSON string that contains escapes.
fn unescape(s: &[u8]) -> Result<String, LoneSurrogate> {
    // Input was validated as UTF-8 and escapes are ASCII, so every run between
    // escapes is valid UTF-8 on char boundaries.
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < s.len() {
        match memchr::memchr(b'\\', &s[i..]) {
            None => {
                out.push_str(std::str::from_utf8(&s[i..]).unwrap_or(""));
                break;
            }
            Some(off) => {
                out.push_str(std::str::from_utf8(&s[i..i + off]).unwrap_or(""));
                i += off + 1;
                // the parser guarantees a well-formed escape here
                match s[i] {
                    b'"' => out.push('"'),
                    b'\\' => out.push('\\'),
                    b'/' => out.push('/'),
                    b'b' => out.push('\u{0008}'),
                    b'f' => out.push('\u{000C}'),
                    b'n' => out.push('\n'),
                    b'r' => out.push('\r'),
                    b't' => out.push('\t'),
                    b'u' => {
                        let hi = hex4(&s[i + 1..i + 5]);
                        i += 4;
                        match hi {
                            0xD800..=0xDBFF => {
                                // must be followed by \uDC00..\uDFFF
                                if i + 6 < s.len() && s[i + 1] == b'\\' && s[i + 2] == b'u' {
                                    let lo = hex4(&s[i + 3..i + 7]);
                                    if (0xDC00..=0xDFFF).contains(&lo) {
                                        let cp = 0x10000 + (((hi - 0xD800) << 10) | (lo - 0xDC00));
                                        out.push(char::from_u32(cp).ok_or(LoneSurrogate)?);
                                        i += 6;
                                    } else {
                                        return Err(LoneSurrogate);
                                    }
                                } else {
                                    return Err(LoneSurrogate);
                                }
                            }
                            0xDC00..=0xDFFF => return Err(LoneSurrogate),
                            cp => out.push(char::from_u32(cp).ok_or(LoneSurrogate)?),
                        }
                    }
                    _ => return Err(LoneSurrogate), // unreachable for validated input
                }
                i += 1;
            }
        }
    }
    Ok(out)
}

#[inline]
fn hex4(h: &[u8]) -> u32 {
    let mut v = 0u32;
    for &c in h {
        v = (v << 4)
            | match c {
                b'0'..=b'9' => (c - b'0') as u32,
                b'a'..=b'f' => (c - b'a' + 10) as u32,
                _ => (c - b'A' + 10) as u32,
            };
    }
    v
}

// ------------------------------------------------------------------- parsing

struct Parser<'a> {
    d: &'a [u8],
    pos: usize,
    tape: Vec<Entry>,
}

impl<'a> Parser<'a> {
    #[inline]
    fn skip_ws(&mut self) {
        while self.pos < self.d.len() {
            match self.d[self.pos] {
                b' ' | b'\n' | b'\r' | b'\t' => self.pos += 1,
                _ => break,
            }
        }
    }

    #[inline]
    fn peek(&self) -> Result<u8, ParseError> {
        self.d
            .get(self.pos)
            .copied()
            .ok_or(ParseError::Syntax(self.pos))
    }

    #[inline]
    fn push(&mut self, t: u32, start: usize, b: u32) -> u32 {
        let idx = self.tape.len() as u32;
        self.tape.push(Entry {
            a: (t << KIND_SHIFT) | start as u32,
            b,
        });
        idx
    }

    fn run(&mut self) -> Result<(), ParseError> {
        // (tape index of the open container, is_object)
        let mut stack: Vec<(u32, bool)> = Vec::new();

        self.skip_ws();
        if self.pos >= self.d.len() {
            return Err(ParseError::Empty);
        }

        'value: loop {
            // ---------------------------------------------- parse one value
            self.skip_ws();
            match self.peek()? {
                b'{' => {
                    if stack.len() >= MAX_DEPTH {
                        return Err(ParseError::TooDeep);
                    }
                    let idx = self.push(T_OBJ, self.pos, 0);
                    self.pos += 1;
                    self.skip_ws();
                    if self.peek()? == b'}' {
                        self.pos += 1;
                        self.tape[idx as usize].b = self.tape.len() as u32;
                    } else {
                        stack.push((idx, true));
                        self.key_and_colon()?;
                        continue 'value;
                    }
                }
                b'[' => {
                    if stack.len() >= MAX_DEPTH {
                        return Err(ParseError::TooDeep);
                    }
                    let idx = self.push(T_ARR, self.pos, 0);
                    self.pos += 1;
                    self.skip_ws();
                    if self.peek()? == b']' {
                        self.pos += 1;
                        self.tape[idx as usize].b = self.tape.len() as u32;
                    } else {
                        stack.push((idx, false));
                        continue 'value;
                    }
                }
                b'"' => self.string()?,
                b'-' | b'0'..=b'9' => self.number()?,
                b't' => self.literal(b"true", T_TRUE)?,
                b'f' => self.literal(b"false", T_FALSE)?,
                b'n' => self.literal(b"null", T_NULL)?,
                _ => return Err(ParseError::Syntax(self.pos)),
            }

            // ------------------------- a value is complete: close / continue
            loop {
                let Some(&(idx, is_obj)) = stack.last() else {
                    self.skip_ws();
                    return if self.pos == self.d.len() {
                        Ok(())
                    } else {
                        Err(ParseError::Syntax(self.pos))
                    };
                };
                self.skip_ws();
                let c = self.peek()?;
                if is_obj {
                    match c {
                        b',' => {
                            self.pos += 1;
                            self.skip_ws();
                            self.key_and_colon()?;
                            continue 'value;
                        }
                        b'}' => {
                            self.pos += 1;
                            stack.pop();
                            self.tape[idx as usize].b = self.tape.len() as u32;
                        }
                        _ => return Err(ParseError::Syntax(self.pos)),
                    }
                } else {
                    match c {
                        b',' => {
                            self.pos += 1;
                            continue 'value;
                        }
                        b']' => {
                            self.pos += 1;
                            stack.pop();
                            self.tape[idx as usize].b = self.tape.len() as u32;
                        }
                        _ => return Err(ParseError::Syntax(self.pos)),
                    }
                }
            }
        }
    }

    /// `"key" :` (leading whitespace already skipped by the caller)
    #[inline]
    fn key_and_colon(&mut self) -> Result<(), ParseError> {
        if self.peek()? != b'"' {
            return Err(ParseError::Syntax(self.pos));
        }
        self.string()?;
        self.skip_ws();
        if self.peek()? != b':' {
            return Err(ParseError::Syntax(self.pos));
        }
        self.pos += 1;
        Ok(())
    }

    fn string(&mut self) -> Result<(), ParseError> {
        // self.pos is at the opening quote
        let start = self.pos + 1;
        let mut p = start;
        let mut esc = false;
        loop {
            let Some(off) = memchr::memchr2(b'"', b'\\', &self.d[p..]) else {
                return Err(ParseError::Syntax(self.d.len()));
            };
            let q = p + off;
            if self.d[q] == b'"' {
                self.push(
                    if esc { T_STR_ESC } else { T_STR },
                    start,
                    (q - start) as u32,
                );
                self.pos = q + 1;
                return Ok(());
            }
            esc = true;
            match self.d.get(q + 1) {
                Some(b'"' | b'\\' | b'/' | b'b' | b'f' | b'n' | b'r' | b't') => p = q + 2,
                Some(b'u') => {
                    if q + 6 > self.d.len()
                        || !self.d[q + 2..q + 6].iter().all(u8::is_ascii_hexdigit)
                    {
                        return Err(ParseError::Syntax(q));
                    }
                    p = q + 6;
                }
                _ => return Err(ParseError::Syntax(q)),
            }
        }
    }

    fn number(&mut self) -> Result<(), ParseError> {
        let d = self.d;
        let n = d.len();
        let start = self.pos;
        let mut p = start;
        if d[p] == b'-' {
            p += 1;
        }
        match d.get(p) {
            Some(b'0') => p += 1,
            Some(b'1'..=b'9') => {
                p += 1;
                while p < n && d[p].is_ascii_digit() {
                    p += 1;
                }
            }
            _ => return Err(ParseError::Syntax(p)),
        }
        if p < n && d[p] == b'.' {
            p += 1;
            let s = p;
            while p < n && d[p].is_ascii_digit() {
                p += 1;
            }
            if p == s {
                return Err(ParseError::Syntax(p));
            }
        }
        if p < n && (d[p] == b'e' || d[p] == b'E') {
            p += 1;
            if p < n && (d[p] == b'+' || d[p] == b'-') {
                p += 1;
            }
            let s = p;
            while p < n && d[p].is_ascii_digit() {
                p += 1;
            }
            if p == s {
                return Err(ParseError::Syntax(p));
            }
        }
        self.push(T_NUM, start, (p - start) as u32);
        self.pos = p;
        Ok(())
    }

    fn literal(&mut self, word: &[u8], t: u32) -> Result<(), ParseError> {
        if self.d[self.pos..].starts_with(word) {
            self.push(t, self.pos, 0);
            self.pos += word.len();
            Ok(())
        } else {
            Err(ParseError::Syntax(self.pos))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(s: &str) -> Doc {
        Doc::parse(s.as_bytes()).unwrap_or_else(|e| panic!("{s:?}: {e:?}"))
    }

    #[test]
    fn scalars_and_containers() {
        let d = p(r#" {"a":1,"b":[true,false,null,"x"],"c":{"d":-1.5e3}} "#);
        let r = d.root();
        assert_eq!(d.kind(r), Kind::Object);
        assert_eq!(d.number_text(d.object_get(r, "a").unwrap()), "1");
        let b = d.object_get(r, "b").unwrap();
        assert_eq!(d.array_len(b), 4);
        assert_eq!(d.kind(d.array_get(b, 0).unwrap().node), Kind::True);
        assert_eq!(d.kind(d.array_get(b, 1).unwrap().node), Kind::False);
        assert_eq!(d.kind(d.array_get(b, 2).unwrap().node), Kind::Null);
        assert_eq!(
            d.string_value(d.array_get(b, 3).unwrap().node).unwrap(),
            "x"
        );
        let c = d.object_get(r, "c").unwrap();
        assert_eq!(d.number_text(d.object_get(c, "d").unwrap()), "-1.5e3");
        assert!(d.object_get(r, "zzz").is_none());
        assert!(d.array_get(b, 4).is_none());
    }

    #[test]
    fn empty_containers_and_skipping() {
        let d = p(r#"{"e":{},"f":[],"g":[[],[{}],{"k":[1,2,3]}],"h":7}"#);
        let r = d.root();
        assert_eq!(d.array_len(d.object_get(r, "f").unwrap()), 0);
        assert_eq!(d.array_len(d.object_get(r, "g").unwrap()), 3);
        // the member after a deeply nested one must still be reachable
        assert_eq!(d.number_text(d.object_get(r, "h").unwrap()), "7");
    }

    #[test]
    fn duplicate_keys_last_wins() {
        let d = p(r#"{"a":1,"a":2,"b":0}"#);
        assert_eq!(d.number_text(d.object_get(d.root(), "a").unwrap()), "2");
        assert!(d.object_has_duplicate_keys(d.root()));
        let d = p(r#"{"a":1,"b":2}"#);
        assert!(!d.object_has_duplicate_keys(d.root()));
    }

    #[test]
    fn string_escapes() {
        let d = p(r#"{"s":"a\"b\\c\/d\n\t\u0041\u00e9\ud83d\ude00z","k\u0065y":1}"#);
        let s = d.object_get(d.root(), "s").unwrap();
        assert_eq!(d.string_value(s).unwrap(), "a\"b\\c/d\n\tAé😀z");
        // keys are compared after unescaping
        assert!(d.object_get(d.root(), "key").is_some());
    }

    #[test]
    fn lone_surrogates_are_reported_not_mangled() {
        let d = p(r#"["\ud800","\udc00","\ud800x","ok"]"#);
        let a = d.root();
        for i in 0..3 {
            assert_eq!(
                d.string_value(d.array_get(a, i).unwrap().node),
                Err(LoneSurrogate)
            );
        }
        assert_eq!(
            d.string_value(d.array_get(a, 3).unwrap().node).unwrap(),
            "ok"
        );
    }

    #[test]
    fn raw_utf8_and_control_chars_inside_strings() {
        let d = p("[\"héllo ✓ 😀\",\"tab\there\"]");
        assert_eq!(
            d.string_value(d.array_get(d.root(), 0).unwrap().node)
                .unwrap(),
            "héllo ✓ 😀"
        );
        assert_eq!(
            d.string_value(d.array_get(d.root(), 1).unwrap().node)
                .unwrap(),
            "tab\there"
        );
    }

    #[test]
    fn rejects_what_gson_lenient_mode_would_accept() {
        for bad in [
            "",
            "  ",
            "{",
            "[1,",
            "[1 2]",
            "{\"a\"}",
            "{\"a\":}",
            "{a:1}",
            "{'a':1}",
            "[1,]",
            "{\"a\":1,}",
            "01",
            "+1",
            "1.",
            ".5",
            "1e",
            "-",
            "NaN",
            "Infinity",
            "tru",
            "nul",
            "[1] x",
            "{} {}",
            "\"abc",
            "\"\\x\"",
            "\"\\u12G4\"",
            "// c\n1",
            "/* c */ 1",
            "\u{feff}{}",
        ] {
            assert!(Doc::parse(bad.as_bytes()).is_err(), "should reject {bad:?}");
        }
        assert!(Doc::parse(&[b'"', 0xff, b'"']).is_err(), "invalid UTF-8");
    }

    #[test]
    fn tape_reservation_is_an_upper_bound() {
        for json in [
            "1",
            "\"x\"",
            "[]",
            "{}",
            "[1,2,3]",
            "[[[[]]]]",
            r#"{"a":1,"b":[true,false,null,"x"],"c":{"d":-1.5e3}}"#,
            r#"{"q\"uote":"a\"b\"c","colon:comma,":"[:,]"}"#,
            " { \"a\" : [ 1 , { \"b\" : [ ] } ] } ",
        ] {
            let d = p(json);
            let bound = max_entries(json.as_bytes());
            assert!(
                d.tape_len() <= bound,
                "{json}: {} entries > bound {bound}",
                d.tape_len()
            );
        }
    }

    #[test]
    fn nothing_is_kept_beyond_input_and_tape() {
        // Fed in small chunks like the app does (so the buffer has to grow several times).
        let json = format!("[{}]", vec![r#"{"key":"value","n":12345}"#; 5000].join(","));
        let mut d = Doc::with_capacity(0);
        for chunk in json.as_bytes().chunks(1000) {
            d.append(chunk).unwrap();
        }
        d.index(false).unwrap();
        assert_eq!(
            d.heap_bytes(),
            json.len() + d.tape_len() * std::mem::size_of::<Entry>()
        );
    }

    #[test]
    fn depth_limit() {
        let deep = "[".repeat(MAX_DEPTH + 1) + &"]".repeat(MAX_DEPTH + 1);
        assert_eq!(Doc::parse(deep.as_bytes()).err(), Some(ParseError::TooDeep));
        let ok = "[".repeat(MAX_DEPTH) + &"]".repeat(MAX_DEPTH);
        assert!(Doc::parse(ok.as_bytes()).is_ok());
    }

    #[test]
    fn skip_first_line_like_buffered_reader() {
        for (input, ok) in [
            (")]}'\n{\"a\":1}", true),
            (")]}'\r\n{\"a\":1}", true),
            (")]}'\r{\"a\":1}", true),
            (")]}'\n\n{\"a\":1}", true),
            ("{\"a\":1}", false), // single line: everything is skipped -> empty
        ] {
            let mut d = Doc::with_capacity(0);
            d.append(input.as_bytes()).unwrap();
            assert_eq!(d.index(true).is_ok(), ok, "{input:?}");
            if ok {
                assert!(d.object_get(d.root(), "a").is_some());
            }
        }
    }

    #[test]
    fn virtual_arrays() {
        let mut d = p(r#"[10,20,30]"#);
        let a = d.root();
        let items: Vec<Item> = (0..3).map(|i| d.array_get(a, i).unwrap()).collect();
        let v = d.new_virtual(items);
        assert!(v.is_virtual());
        assert_eq!(d.kind(v), Kind::Array);
        assert_eq!(d.array_len(v), 3);
        assert_eq!(d.number_text(d.array_get(v, 2).unwrap().node), "30");
    }
}
