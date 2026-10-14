//! The JsonPath dialect used by the app's `@JsonPath(...)` annotations.
//!
//! This is *not* a general JsonPath implementation. It accepts exactly the
//! forms that appear in the model classes (verified against all 400+
//! annotations, see `tests/`):
//!
//! | syntax            | meaning                         |
//! |-------------------|---------------------------------|
//! | `$`               | root                            |
//! | `.name`           | object property                 |
//! | `['name']`        | object property (quoted)        |
//! | `[3]`, `[-1]`     | array index                     |
//! | `[*]`, `.*`       | wildcard (array elements or object values) |
//! | `[1:]`, `[:2]`, `[1:3]` | array slice               |
//!
//! Everything else (filters, `..`, unions, functions, whitespace, odd property
//! names) yields [`Unsupported`]. The Java side then evaluates that request with
//! Jayway, so an unsupported expression costs memory, never correctness.
//!
//! The grammar mirrors Jayway 2.4.0's `PathCompiler`; the semantics live in
//! [`crate::eval`].

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Token {
    Prop(String),
    Index(i32),
    Wildcard,
    /// `[n:]`
    SliceFrom(i32),
    /// `[:n]`
    SliceTo(i32),
    /// `[a:b]`
    SliceBetween(i32, i32),
}

impl Token {
    /// Jayway's `PathToken.isTokenDefinite()`.
    #[inline]
    pub fn is_definite(&self) -> bool {
        matches!(self, Token::Prop(_) | Token::Index(_))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Unsupported(pub String);

#[derive(Debug, Clone)]
pub struct Path {
    pub tokens: Vec<Token>,
    /// Jayway's `Path.isDefinite()`: every token yields at most one result.
    pub definite: bool,
    /// `upstream[i]` == Jayway's `tokens[i].isUpstreamDefinite()`.
    upstream: Vec<bool>,
}

impl Path {
    pub fn compile(src: &str) -> Result<Path, Unsupported> {
        let b = src.as_bytes();
        if b.first() != Some(&b'$') {
            return Err(unsupported(src, "must start with '$'"));
        }
        let mut tokens = Vec::new();
        let mut i = 1;
        while i < b.len() {
            match b[i] {
                b'.' => {
                    i += 1;
                    if i >= b.len() {
                        return Err(unsupported(src, "trailing '.'"));
                    }
                    if b[i] == b'*' {
                        // `.*` must be a whole segment
                        i += 1;
                        if i < b.len() && b[i] != b'.' && b[i] != b'[' {
                            return Err(unsupported(src, "property name starting with '*'"));
                        }
                        tokens.push(Token::Wildcard);
                        continue;
                    }
                    let start = i;
                    while i < b.len() && b[i] != b'.' && b[i] != b'[' {
                        i += 1;
                    }
                    let name = &src[start..i];
                    check_name(src, name)?;
                    tokens.push(Token::Prop(name.to_string()));
                }
                b'[' => {
                    let Some(rel) = src[i + 1..].find(']') else {
                        return Err(unsupported(src, "unclosed '['"));
                    };
                    let inner = &src[i + 1..i + 1 + rel];
                    tokens.push(parse_bracket(src, inner)?);
                    i += rel + 2;
                }
                _ => return Err(unsupported(src, "expected '.' or '['")),
            }
        }

        let definite = tokens.iter().all(Token::is_definite);
        let mut upstream = Vec::with_capacity(tokens.len());
        let mut all_before = true;
        for t in &tokens {
            upstream.push(all_before);
            all_before &= t.is_definite();
        }
        Ok(Path {
            tokens,
            definite,
            upstream,
        })
    }

    /// Jayway's `isUpstreamDefinite()` for the token at `i`.
    #[inline]
    pub fn upstream_definite(&self, i: usize) -> bool {
        self.upstream[i]
    }
}

fn unsupported(src: &str, why: &str) -> Unsupported {
    Unsupported(format!("{why}: {src:?}"))
}

fn check_name(src: &str, name: &str) -> Result<(), Unsupported> {
    if name.is_empty() {
        return Err(unsupported(src, "empty property name"));
    }
    // Deliberately narrow. Anything Jayway might interpret specially (spaces,
    // parentheses => functions, quotes, '@', '?', ',' ...) is left to Jayway.
    if !name
        .bytes()
        .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-')
    {
        return Err(unsupported(src, "unsupported character in property name"));
    }
    Ok(())
}

fn parse_int(src: &str, s: &str) -> Result<i32, Unsupported> {
    let digits = s.strip_prefix('-').unwrap_or(s);
    if digits.is_empty() || !digits.bytes().all(|c| c.is_ascii_digit()) {
        return Err(unsupported(src, "bad array index"));
    }
    s.parse::<i32>()
        .map_err(|_| unsupported(src, "array index out of range"))
}

fn parse_bracket(src: &str, inner: &str) -> Result<Token, Unsupported> {
    if inner == "*" {
        return Ok(Token::Wildcard);
    }
    if let Some(first) = inner.chars().next() {
        if first == '\'' || first == '"' {
            // ['name'] / ["name"], no escapes, no unions
            let q = first;
            if inner.len() < 2 || !inner.ends_with(q) {
                return Err(unsupported(src, "malformed quoted property"));
            }
            let name = &inner[1..inner.len() - 1];
            if name.contains(['\'', '"', '\\', ',', ']']) {
                return Err(unsupported(src, "unsupported quoted property"));
            }
            check_name(src, name)?;
            return Ok(Token::Prop(name.to_string()));
        }
    }
    if let Some(colon) = inner.find(':') {
        let (a, b) = (&inner[..colon], &inner[colon + 1..]);
        if b.contains(':') {
            return Err(unsupported(src, "slice with step"));
        }
        return match (a.is_empty(), b.is_empty()) {
            (false, true) => Ok(Token::SliceFrom(parse_int(src, a)?)),
            (true, false) => Ok(Token::SliceTo(parse_int(src, b)?)),
            (false, false) => Ok(Token::SliceBetween(parse_int(src, a)?, parse_int(src, b)?)),
            (true, true) => Err(unsupported(src, "empty slice")),
        };
    }
    Ok(Token::Index(parse_int(src, inner)?))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ok(s: &str) -> Path {
        Path::compile(s).unwrap_or_else(|e| panic!("{e:?}"))
    }

    #[test]
    fn supported_forms() {
        assert_eq!(ok("$").tokens, vec![]);
        assert!(ok("$").definite);
        assert_eq!(
            ok("$.a.b-c_d[0][*].e").tokens,
            vec![
                Token::Prop("a".into()),
                Token::Prop("b-c_d".into()),
                Token::Index(0),
                Token::Wildcard,
                Token::Prop("e".into())
            ]
        );
        assert_eq!(ok("$['a']").tokens, vec![Token::Prop("a".into())]);
        assert_eq!(
            ok("$[\"a\"][-1]").tokens,
            vec![Token::Prop("a".into()), Token::Index(-1)]
        );
        assert_eq!(
            ok("$.a.*").tokens,
            vec![Token::Prop("a".into()), Token::Wildcard]
        );
        assert_eq!(ok("$[1:]").tokens, vec![Token::SliceFrom(1)]);
        assert_eq!(ok("$[:2]").tokens, vec![Token::SliceTo(2)]);
        assert_eq!(ok("$[-3:5]").tokens, vec![Token::SliceBetween(-3, 5)]);
        assert_eq!(
            ok("$[1][*][0]").tokens,
            vec![Token::Index(1), Token::Wildcard, Token::Index(0)]
        );
    }

    #[test]
    fn definiteness_matches_jayway() {
        assert!(ok("$.a.b[0].c").definite);
        assert!(!ok("$.a[*]").definite);
        assert!(!ok("$.a[1:]").definite);
        let p = ok("$.a[*].b[0]");
        assert!(p.upstream_definite(0)); // a: nothing before it
        assert!(p.upstream_definite(1)); // [*]: only 'a' before it
        assert!(!p.upstream_definite(2)); // b: after a wildcard
        assert!(!p.upstream_definite(3));
    }

    #[test]
    fn unsupported_forms_are_rejected_not_guessed() {
        for bad in [
            "",
            "a.b",
            "@.a",
            "$.",
            "$..a",
            "$.a..b",
            "$.a[?(@.b)]",
            "$.a['b','c']",
            "$.a[0,1]",
            "$.a[0:2:1]",
            "$.a[:]",
            "$.a[ 0 ]",
            "$.a[x]",
            "$.a b",
            "$.a.length()",
            "$.a[",
            "$.a[99999999999]",
            "$.a['b\\'c']",
            "$.a*b",
            "$.*a",
            "$ ",
            "$.a.",
            "$a",
        ] {
            assert!(Path::compile(bad).is_err(), "must reject {bad:?}");
        }
    }
}
