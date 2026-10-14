//! Path evaluation with the exact observable semantics of Jayway JsonPath 2.4.0
//! running on `GsonJsonProvider` with default options, which is what the Java
//! mapper has always used.
//!
//! "Exact" matters because the mapper's fallback logic depends on *how* a path
//! fails:
//!
//! ```java
//! for (String path : paths) {
//!     try { value = doc.read(path); break; }       // any non-throwing result wins
//!     catch (PathNotFoundException e) { /* try the next path */ }
//! }
//! ```
//!
//! so "threw" ([`Outcome::NotFound`]) and "returned an empty array"
//! ([`Outcome::Multi`] with no items) select different fallbacks. The rules
//! ported here, token by token, from Jayway's `PathToken` hierarchy:
//!
//! * property on a non-object, index/slice on a non-array:
//!   throws if everything upstream is definite, otherwise the branch is skipped;
//! * missing property: a *leaf* is skipped silently (a definite path then ends
//!   with "no results" => throws); a non-leaf throws only if upstream is definite;
//! * out-of-range index: skipped silently;
//! * `[*]` over an array swallows `PathNotFoundException` from each element's
//!   sub-evaluation; `[*]` over an object does not;
//! * a definite path with no result throws; an indefinite path never throws at
//!   the end, it returns the (possibly empty) array of matches.
//!
//! # Copy mode
//!
//! Every value Jayway returns has been pushed through `Gson.toJsonTree`, which
//! deep-copies it with `serializeNulls == false`: **object members whose value
//! is JSON null disappear** from anything that was ever returned by a path
//! (array elements are kept). Paths evaluated against such a returned value
//! therefore see null members as *absent*. Callers pass `copy = true` for any
//! context that came out of a previous evaluation, `false` for the document
//! root.
//!
//! # Number representation
//!
//! See [`Repr`]. A leaf reached through a property is `Unwrapped`; one reached
//! through an array index is whatever it already was. A virtual array can only
//! ever be the *context* of an evaluation, and the old mapper serialised and
//! re-parsed every context, so unwrapped numbers taken out of a virtual array
//! come back as `Reparsed`.
//!
//! All of this is checked against the real libraries by `verify/` (millions of
//! evaluations over recorded responses and generated documents).

use crate::doc::{Doc, Item, Kind, NodeRef, Repr};
use crate::path::{Path, Token};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    /// Jayway would throw `PathNotFoundException`.
    NotFound,
    /// Definite path with a result (which may be JSON null).
    Single(Item),
    /// Indefinite path: a virtual array holding the matches (possibly empty).
    Multi(NodeRef),
}

/// The evaluation hit a case this engine does not reproduce (currently only:
/// a wildcard over an object with duplicate keys). The caller must use Jayway.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Unsupported;

enum Abort {
    NotFound,
    Unsupported,
}

struct Eval<'a> {
    doc: &'a Doc,
    path: &'a Path,
    copy: bool,
    results: Vec<Item>,
}

/// Evaluate `path` with `ctx` as `$`.
///
/// `copy` must be true when `ctx` was returned by a previous evaluation.
pub fn eval(doc: &mut Doc, path: &Path, ctx: Item, copy: bool) -> Result<Outcome, Unsupported> {
    // A virtual array only ever exists as the result of an earlier evaluation.
    let copy = copy || ctx.node.is_virtual();

    let (status, results) = {
        let mut e = Eval {
            doc,
            path,
            copy,
            results: Vec::new(),
        };
        let status = if path.tokens.is_empty() {
            // `$` alone: the context itself, after the mapper's re-parse
            e.results.push(Item {
                node: ctx.node,
                repr: ctx.repr.reparsed(),
            });
            Ok(())
        } else {
            e.token(0, ctx.node)
        };
        (status, e.results)
    };

    match status {
        Err(Abort::Unsupported) => Err(Unsupported),
        Err(Abort::NotFound) => Ok(Outcome::NotFound),
        Ok(()) => {
            if path.definite {
                Ok(match results.last() {
                    Some(item) => Outcome::Single(*item),
                    None => Outcome::NotFound,
                })
            } else {
                Ok(Outcome::Multi(doc.new_virtual(results)))
            }
        }
    }
}

impl<'a> Eval<'a> {
    /// `isLeaf() ? ctx.addResult(...) : next().evaluate(...)`
    #[inline]
    fn step(&mut self, i: usize, item: Item) -> Result<(), Abort> {
        if i + 1 == self.path.tokens.len() {
            self.results.push(item);
            Ok(())
        } else {
            self.token(i + 1, item.node)
        }
    }

    /// `throw` if upstream is definite, otherwise skip this branch.
    #[inline]
    fn wrong_model(&self, i: usize) -> Result<(), Abort> {
        if self.path.upstream_definite(i) {
            Err(Abort::NotFound)
        } else {
            Ok(())
        }
    }

    /// `JsonProvider.getMapValue`, as seen through a (possibly copied) tree.
    #[inline]
    fn member(&self, obj: NodeRef, name: &str) -> Option<NodeRef> {
        let v = self.doc.object_get(obj, name)?;
        if self.copy && self.doc.kind(v) == Kind::Null {
            None
        } else {
            Some(v)
        }
    }

    /// An element as `JsonProvider.getArrayIndex` returns it.
    #[inline]
    fn element(model: NodeRef, item: Item) -> Item {
        if model.is_virtual() {
            // `model` is the (re-parsed) context, see the module docs
            Item {
                node: item.node,
                repr: item.repr.reparsed(),
            }
        } else {
            item
        }
    }

    /// `PathToken.handleArrayIndex`
    fn array_index(&mut self, i: usize, model: NodeRef, index: i64) -> Result<(), Abort> {
        let effective = if index < 0 {
            self.doc.array_len(model) as i64 + index
        } else {
            index
        };
        if effective < 0 {
            return Ok(()); // IndexOutOfBoundsException is swallowed
        }
        match self.doc.array_get(model, effective as usize) {
            Some(item) => self.step(i, Self::element(model, item)),
            None => Ok(()),
        }
    }

    fn token(&mut self, i: usize, model: NodeRef) -> Result<(), Abort> {
        let doc = self.doc;
        let path = self.path;
        let leaf = i + 1 == path.tokens.len();

        match &path.tokens[i] {
            // ------------------------------------------- PropertyPathToken
            Token::Prop(name) => {
                if !doc.is_object(model) {
                    return self.wrong_model(i);
                }
                match self.member(model, name) {
                    Some(v) => self.step(
                        i,
                        Item {
                            node: v,
                            repr: Repr::Unwrapped,
                        },
                    ),
                    None if leaf => Ok(()),
                    // this token is definite, so only upstream decides
                    None => self.wrong_model(i),
                }
            }

            // --------------------------------------- ArrayPathToken (index)
            Token::Index(index) => {
                if !doc.is_array(model) {
                    return self.wrong_model(i);
                }
                self.array_index(i, model, *index as i64)
            }

            // --------------------------------------- ArrayPathToken (slices)
            Token::SliceFrom(from) => {
                if !doc.is_array(model) {
                    return self.wrong_model(i);
                }
                let length = doc.array_len(model) as i64;
                let mut from = *from as i64;
                if from < 0 {
                    from += length;
                }
                from = from.max(0);
                if length == 0 || from >= length {
                    return Ok(());
                }
                for k in from..length {
                    self.array_index(i, model, k)?;
                }
                Ok(())
            }
            Token::SliceTo(to) => {
                if !doc.is_array(model) {
                    return self.wrong_model(i);
                }
                let length = doc.array_len(model) as i64;
                if length == 0 {
                    return Ok(());
                }
                let mut to = *to as i64;
                if to < 0 {
                    to += length;
                }
                to = to.min(length);
                for k in 0..to {
                    self.array_index(i, model, k)?;
                }
                Ok(())
            }
            Token::SliceBetween(from, to) => {
                if !doc.is_array(model) {
                    return self.wrong_model(i);
                }
                let length = doc.array_len(model) as i64;
                let from = *from as i64;
                let to = (*to as i64).min(length);
                if from >= to || length == 0 {
                    return Ok(());
                }
                for k in from..to {
                    self.array_index(i, model, k)?;
                }
                Ok(())
            }

            // ------------------------------------------- WildcardPathToken
            Token::Wildcard => match doc.kind(model) {
                Kind::Object => {
                    // Gson keeps one entry per key (first position, last value).
                    // Rather than emulate that, hand such documents to Jayway.
                    if doc.object_has_duplicate_keys(model) {
                        return Err(Abort::Unsupported);
                    }
                    let copy = self.copy;
                    doc.for_each_member(model, |_key, v| {
                        if copy && doc.kind(v) == Kind::Null {
                            return Ok(()); // dropped by the copy
                        }
                        self.step(
                            i,
                            Item {
                                node: v,
                                repr: Repr::Unwrapped,
                            },
                        )
                    })
                }
                Kind::Array => doc.for_each_element(model, |item| {
                    match self.step(i, Self::element(model, item)) {
                        // `catch (PathNotFoundException p) { if (REQUIRE_PROPERTIES) throw p; }`
                        //
                        // Kept to mirror the original, but nothing downstream of a
                        // wildcard can throw: upstream is no longer definite, so
                        // every later token skips instead (mutation testing
                        // confirms that removing this arm changes no result).
                        Err(Abort::NotFound) => Ok(()),
                        other => other,
                    }
                }),
                _ => Ok(()),
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Evaluate against the root (non-copy), rendering the outcome compactly.
    fn run(json: &str, path: &str) -> String {
        run_ctx(json, path, false)
    }

    fn run_ctx(json: &str, path: &str, copy: bool) -> String {
        let mut d = Doc::parse(json.as_bytes()).unwrap();
        let p = Path::compile(path).unwrap();
        let root = Item::raw(d.root());
        match eval(&mut d, &p, root, copy).unwrap() {
            Outcome::NotFound => "THROW".into(),
            Outcome::Single(it) => render(&d, it.node),
            Outcome::Multi(v) => {
                let parts: Vec<String> = d
                    .virtual_items(v)
                    .iter()
                    .map(|it| render(&d, it.node))
                    .collect();
                format!("[{}]", parts.join(","))
            }
        }
    }

    fn render(d: &Doc, n: NodeRef) -> String {
        match d.kind(n) {
            Kind::Null => "null".into(),
            Kind::True => "true".into(),
            Kind::False => "false".into(),
            Kind::Number => d.number_text(n).to_string(),
            Kind::String => format!("{:?}", d.string_value(n).unwrap()),
            Kind::Array => format!("<array:{}>", d.array_len(n)),
            Kind::Object => "<object>".into(),
        }
    }

    fn p(s: &str) -> Path {
        Path::compile(s).unwrap()
    }

    const J: &str = r#"{"a":{"b":[{"c":1},{"c":2,"d":null},{"x":3},7],"n":null,"s":"str"},"arr":[10,20,30],"e":[]}"#;

    #[test]
    fn definite_paths() {
        assert_eq!(run(J, "$.a.s"), "\"str\"");
        assert_eq!(run(J, "$.a.b[0].c"), "1");
        assert_eq!(run(J, "$.arr[2]"), "30");
        assert_eq!(run(J, "$.arr[-1]"), "30");
        assert_eq!(run(J, "$"), "<object>");
        // a JSON-null leaf is a *result*, not a miss (it stops the fallback chain)
        assert_eq!(run(J, "$.a.n"), "null");
    }

    #[test]
    fn definite_misses_throw() {
        assert_eq!(run(J, "$.zzz"), "THROW"); // missing leaf => "no results"
        assert_eq!(run(J, "$.zzz.y"), "THROW"); // missing non-leaf, definite upstream
        assert_eq!(run(J, "$.arr[9]"), "THROW"); // out of range => no results
        assert_eq!(run(J, "$.arr[-9]"), "THROW");
        assert_eq!(run(J, "$.a.s.t"), "THROW"); // member of a string
        assert_eq!(run(J, "$.a[0]"), "THROW"); // index into an object
        assert_eq!(run(J, "$.a.n.x"), "THROW"); // member of null
        assert_eq!(run(J, "$.a.n[0]"), "THROW"); // index into null
    }

    #[test]
    fn indefinite_paths_return_arrays_even_when_empty() {
        assert_eq!(run(J, "$.arr[*]"), "[10,20,30]");
        assert_eq!(run(J, "$.e[*]"), "[]");
        assert_eq!(run(J, "$.a.b[*].c"), "[1,2]"); // missing leaf is skipped per element
        assert_eq!(run(J, "$.a.b[*].zzz"), "[]");
        assert_eq!(run(J, "$.a.b[*].c.deeper"), "[]"); // member of a number, upstream indefinite
        assert_eq!(run(J, "$.a.b[*].x.y"), "[]");
        assert_eq!(run(J, "$.a.b[*][0]"), "[]"); // index into objects/number, upstream indefinite
        assert_eq!(run(J, "$.a.s[*]"), "[]"); // wildcard over a primitive: nothing
        assert_eq!(run(J, "$.a.n[*]"), "[]");
    }

    #[test]
    fn missing_definite_prefix_of_an_indefinite_path_throws() {
        // This is what lets `{"$.x.items[*]", "$.y.items[*]"}` fall through to the second path.
        assert_eq!(run(J, "$.zzz[*]"), "THROW");
        assert_eq!(run(J, "$.zzz.items[*]"), "THROW");
        assert_eq!(run(J, "$.a.zzz[*].c"), "THROW");
        assert_eq!(run(J, "$.arr[*]"), "[10,20,30]");
    }

    #[test]
    fn wildcard_over_object_values() {
        assert_eq!(
            run(r#"{"o":{"x":1,"y":"b","z":[1]}}"#, "$.o[*]"),
            "[1,\"b\",<array:1>]"
        );
        assert_eq!(run(r#"{"o":{"x":1,"y":"b"}}"#, "$.o.*"), "[1,\"b\"]");
        // no try/catch around object iteration, but upstream is indefinite so nothing throws
        assert_eq!(run(r#"{"o":{"x":{"k":1},"y":2}}"#, "$.o[*].k"), "[1]");
    }

    #[test]
    fn slices() {
        let j = r#"{"a":[0,1,2,3,4]}"#;
        assert_eq!(run(j, "$.a[1:]"), "[1,2,3,4]");
        assert_eq!(run(j, "$.a[-2:]"), "[3,4]");
        assert_eq!(run(j, "$.a[-9:]"), "[0,1,2,3,4]");
        assert_eq!(run(j, "$.a[9:]"), "[]");
        assert_eq!(run(j, "$.a[:2]"), "[0,1]");
        assert_eq!(run(j, "$.a[:-1]"), "[0,1,2,3]");
        assert_eq!(run(j, "$.a[:99]"), "[0,1,2,3,4]");
        assert_eq!(run(j, "$.a[1:3]"), "[1,2]");
        assert_eq!(run(j, "$.a[3:1]"), "[]");
        assert_eq!(run(j, "$.a[1:99]"), "[1,2,3,4]");
        assert_eq!(run(j, "$.zzz[1:]"), "THROW");
        assert_eq!(run(r#"{"a":5}"#, "$.a[1:]"), "THROW"); // slice of a non-array, definite upstream
    }

    #[test]
    fn copy_mode_hides_null_members_but_not_null_elements() {
        let j = r#"{"n":null,"o":{"k":null,"v":1},"arr":[null,2]}"#;
        assert_eq!(run_ctx(j, "$.n", false), "null");
        assert_eq!(run_ctx(j, "$.n", true), "THROW"); // member dropped by toJsonTree
        assert_eq!(run_ctx(j, "$.o[*]", false), "[null,1]");
        assert_eq!(run_ctx(j, "$.o[*]", true), "[1]");
        assert_eq!(run_ctx(j, "$.arr[0]", true), "null"); // array elements survive the copy
        assert_eq!(run_ctx(j, "$.arr[*]", true), "[null,2]");
    }

    fn reprs(d: &mut Doc, ctx: Item, path: &str) -> Vec<Repr> {
        match eval(d, &p(path), ctx, ctx.node.is_virtual()).unwrap() {
            Outcome::Single(it) => vec![it.repr],
            Outcome::Multi(v) => d.virtual_items(v).iter().map(|i| i.repr).collect(),
            Outcome::NotFound => vec![],
        }
    }

    #[test]
    fn number_representation_tracks_how_the_leaf_was_reached() {
        let mut d = Doc::parse(br#"{"p":1,"a":[2],"o":{"q":3}}"#).unwrap();
        let root = Item::raw(d.root());
        assert_eq!(reprs(&mut d, root, "$.p"), [Repr::Unwrapped]); // via getMapValue
        assert_eq!(reprs(&mut d, root, "$.a[0]"), [Repr::Raw]); // via getArrayIndex
        assert_eq!(reprs(&mut d, root, "$.a[*]"), [Repr::Raw]);
        assert_eq!(reprs(&mut d, root, "$.o[*]"), [Repr::Unwrapped]); // wildcard over an object reads properties
    }

    #[test]
    fn unwrapped_numbers_are_reparsed_once_their_array_becomes_a_context() {
        let mut d = Doc::parse(br#"{"o":{"q":1.5e3,"r":[7]}}"#).unwrap();
        let root = Item::raw(d.root());
        // `$.o[*]` => [Integer(1500), copy of [7]]
        let Outcome::Multi(v) = eval(&mut d, &p("$.o[*]"), root, false).unwrap() else {
            panic!()
        };
        assert_eq!(d.virtual_items(v)[0].repr, Repr::Unwrapped);
        // the mapper serialises that array and parses it again before reading from it
        let ctx = Item::raw(v);
        assert_eq!(reprs(&mut d, ctx, "$[0]"), [Repr::Reparsed]);
        // (the flag is only meaningful for numbers; item 1 is an array)
        assert_eq!(reprs(&mut d, ctx, "$[*]")[0], Repr::Reparsed);
        assert_eq!(reprs(&mut d, ctx, "$[:1]"), [Repr::Reparsed]);
        assert_eq!(reprs(&mut d, ctx, "$[1][0]"), [Repr::Raw]); // nested content was never unwrapped
                                                                // and it stays that way however often it is passed around
        let Outcome::Multi(again) = eval(&mut d, &p("$[*]"), ctx, true).unwrap() else {
            panic!()
        };
        assert_eq!(reprs(&mut d, Item::raw(again), "$[0]"), [Repr::Reparsed]);
        // `$` on a primitive context
        let unwrapped = Item {
            node: d.virtual_items(v)[0].node,
            repr: Repr::Unwrapped,
        };
        assert_eq!(reprs(&mut d, unwrapped, "$"), [Repr::Reparsed]);
        assert_eq!(reprs(&mut d, root, "$"), [Repr::Raw]);
    }

    #[test]
    fn results_can_be_used_as_contexts() {
        let mut d = Doc::parse(br#"{"items":[{"t":"a"},{"t":"b"},{"u":1}]}"#).unwrap();
        let root = Item::raw(d.root());
        let Outcome::Multi(v) = eval(&mut d, &p("$.items[*]"), root, false).unwrap() else {
            panic!()
        };
        let v = Item::raw(v);
        // paths relative to the result array
        let Outcome::Single(first) = eval(&mut d, &p("$[0].t"), v, true).unwrap() else {
            panic!()
        };
        assert_eq!(d.string_value(first.node).unwrap(), "a");
        let Outcome::Multi(ts) = eval(&mut d, &p("$[*].t"), v, true).unwrap() else {
            panic!()
        };
        assert_eq!(d.array_len(ts), 2);
        // a property of the (array) result: definite upstream => throws
        assert_eq!(eval(&mut d, &p("$.t"), v, true).unwrap(), Outcome::NotFound);
    }

    #[test]
    fn wildcard_over_object_with_duplicate_keys_is_delegated() {
        let mut d = Doc::parse(br#"{"o":{"a":1,"a":2}}"#).unwrap();
        let root = Item::raw(d.root());
        assert_eq!(eval(&mut d, &p("$.o[*]"), root, false), Err(Unsupported));
        // plain lookups are fine: last one wins, as in Gson
        let Outcome::Single(it) = eval(&mut d, &p("$.o.a"), root, false).unwrap() else {
            panic!()
        };
        assert_eq!(d.number_text(it.node), "2");
    }
}
