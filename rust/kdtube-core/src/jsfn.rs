//! Where the function bodies of a JavaScript source are.
//!
//! # Why this exists
//!
//! To solve YouTube's `n` / signature challenges the app runs JavaScript
//! "solvers" inside V8. On the first solve for a new player version they parse
//! the whole player script (2.5-3 MB, minified) into an AST *made of JS
//! objects*. Measured with the app's own V8: that needs between 128 and 192 MB
//! of V8 heap, about +200 MB of resident memory, and a V8 out-of-memory there
//! is fatal for the whole process.
//!
//! The solvers only ever look at a tiny part of that tree, and their logic has
//! to stay JavaScript (it follows YouTube's changes and can be updated without
//! an app release). So the logic is left alone and only the parsing is made
//! lazy: with the spans from this module, a small JS shim
//! (`assets/nsigsolver/lazy.ast.js`) blanks out every function body, lets the
//! JS parser parse that skeleton, parses an individual body only when a solver
//! actually reads it, and copies untouched bodies back verbatim when code is
//! generated.
//!
//! Finding function bodies reliably needs a real parser (think of `/` being a
//! division or the start of a regex), which is cheap to do natively: the AST
//! lives in an arena and is dropped as soon as the spans are collected.

use oxc_allocator::Allocator;
use oxc_ast::ast::FunctionBody;
use oxc_ast_visit::{walk, Visit};
use oxc_parser::{ParseOptions, Parser};
use oxc_span::SourceType;

/// `[start, end)` of every function body (`{` ... `}` of a function, method,
/// getter/setter, constructor or block-bodied arrow function), in source order
/// of `start`, i.e. an enclosing body always comes before the ones inside it.
///
/// Offsets are in **UTF-16 code units**: that is how both Java and JavaScript
/// index strings.
///
/// `None` if the source can't be parsed as a classic script. The caller then
/// simply doesn't use the lazy path.
pub fn function_body_spans(source: &str) -> Option<Vec<(u32, u32)>> {
    if source.len() >= u32::MAX as usize {
        return None;
    }

    let allocator = Allocator::default();
    let options = ParseOptions {
        // regex contents are irrelevant here; don't spend memory on them
        parse_regular_expression: false,
        ..ParseOptions::default()
    };
    let ret = Parser::new(&allocator, source, SourceType::script())
        .with_options(options)
        .parse();
    if ret.fatal_error || ret.diagnostics.has_errors() {
        return None;
    }

    let mut collector = Collector { spans: Vec::new() };
    collector.visit_program(&ret.program);
    let mut spans = collector.spans;

    // The visitor already goes in source order; make the contract explicit.
    spans.sort_unstable_by(|a, b| a.0.cmp(&b.0).then(b.1.cmp(&a.1)));

    // Every body must be a brace pair, otherwise the shim would cut garbage.
    let bytes = source.as_bytes();
    if spans
        .iter()
        .any(|&(s, e)| e <= s + 1 || bytes[s as usize] != b'{' || bytes[e as usize - 1] != b'}')
    {
        return None;
    }

    if !source.is_ascii() {
        to_utf16_offsets(source, &mut spans);
    }
    Some(spans)
}

/// The spans as text, `start,end,start,end,...`: one string is far cheaper to
/// hand to a JS engine through JNI than a hundred thousand numbers.
pub fn function_body_spans_text(source: &str) -> Option<String> {
    let spans = function_body_spans(source)?;
    let mut out = String::with_capacity(spans.len() * 14);
    for (i, (s, e)) in spans.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str(itoa(*s).as_str());
        out.push(',');
        out.push_str(itoa(*e).as_str());
    }
    Some(out)
}

fn itoa(v: u32) -> String {
    v.to_string()
}

struct Collector {
    spans: Vec<(u32, u32)>,
}

impl<'a> Visit<'a> for Collector {
    fn visit_function_body(&mut self, it: &FunctionBody<'a>) {
        self.spans.push((it.span.start, it.span.end));
        walk::walk_function_body(self, it);
    }
}

/// Convert UTF-8 byte offsets to UTF-16 code unit offsets, in one pass.
fn to_utf16_offsets(source: &str, spans: &mut [(u32, u32)]) {
    let mut points: Vec<u32> = spans.iter().flat_map(|&(s, e)| [s, e]).collect();
    points.sort_unstable();
    points.dedup();

    let mut mapped = Vec::with_capacity(points.len());
    let (mut byte, mut unit) = (0usize, 0u32);
    let mut chars = source.chars();
    for &p in &points {
        while byte < p as usize {
            // offsets come from the parser, so they are on char boundaries
            let Some(c) = chars.next() else { break };
            byte += c.len_utf8();
            unit += c.len_utf16() as u32;
        }
        mapped.push(unit);
    }

    let lookup = |p: u32| mapped[points.binary_search(&p).unwrap_or(0)];
    for span in spans.iter_mut() {
        *span = (lookup(span.0), lookup(span.1));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bodies(src: &str) -> Vec<String> {
        let units: Vec<u16> = src.encode_utf16().collect();
        function_body_spans(src)
            .unwrap()
            .iter()
            .map(|&(s, e)| String::from_utf16(&units[s as usize..e as usize]).unwrap())
            .collect()
    }

    #[test]
    fn all_kinds_of_function_bodies() {
        let src = "function a(){1} var b=function(){2}; var c=()=>{3}; var d=x=>x+1; \
                   var o={m(){4}, get g(){5}, set s(v){6}, async *n(){7}}; \
                   class K extends B{constructor(){super()} static t(){8} #p(){9}}";
        assert_eq!(
            bodies(src),
            [
                "{1}",
                "{2}",
                "{3}",
                "{4}",
                "{5}",
                "{6}",
                "{7}",
                "{super()}",
                "{8}",
                "{9}"
            ]
        );
    }

    #[test]
    fn nested_bodies_come_after_the_enclosing_one() {
        let src = "(function(g){var a=function(){return function(){return 1}};g.b=()=>{}})(x);";
        assert_eq!(
            bodies(src),
            [
                "{var a=function(){return function(){return 1}};g.b=()=>{}}",
                "{return function(){return 1}}",
                "{return 1}",
                "{}"
            ]
        );
    }

    #[test]
    fn braces_that_are_not_function_bodies() {
        // blocks, object literals, template literals, regexes and strings with braces
        let src = "if(a){b()} var o={k:{}}; var t=`${'}'}{`; var r=/[{}]/g; var s='function(){';for(;;){break}";
        assert_eq!(bodies(src), Vec::<String>::new());
        // the classic trap: division vs regex literal
        let src = "var q=a/2;function f(){return b/c/d}var re=/}/;function h(){return /{/.test(x)}";
        assert_eq!(bodies(src), ["{return b/c/d}", "{return /{/.test(x)}"]);
    }

    #[test]
    fn offsets_are_utf16_code_units() {
        // 'é' is 2 bytes / 1 unit, the emoji is 4 bytes / 2 units
        let src = "var a='é😀';function f(){return '😀é'}var z=function(){}";
        assert_eq!(bodies(src), ["{return '😀é'}", "{}"]);
    }

    #[test]
    fn sloppy_mode_script_is_accepted() {
        // classic script: `with`, octal-like literals, `await`/`yield` as identifiers
        let src = "function f(){with(o){x}} var await=1, yield=2; function g(){return 010}";
        assert_eq!(bodies(src).len(), 2);
    }

    #[test]
    fn broken_source_is_refused() {
        assert!(function_body_spans("function f( {").is_none());
        assert!(function_body_spans("var a = ;").is_none());
    }

    #[test]
    fn text_form() {
        assert_eq!(function_body_spans_text("function f(){}").unwrap(), "12,14");
        assert_eq!(function_body_spans_text("var a=1").unwrap(), "");
    }
}
