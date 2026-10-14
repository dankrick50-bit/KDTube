//! JNI entry points for
//! `com.liskovsoft.googlecommon.common.converters.jsonpath.engine.NativeJson`.
//!
//! Conventions:
//!
//! * a document is a `jlong` owning pointer created by `nCreate` and released
//!   exactly once by `nFree` (Java does this in a `finally`);
//! * nothing here throws: failures are reported as status codes / `null` and
//!   Java replays the request on its own engine;
//! * every body runs inside `catch_unwind`, so a bug in this crate degrades to
//!   "native engine unavailable for this request" instead of killing the app.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jlongArray, jsize, jstring};
use jni::JNIEnv;

use crate::api;
use crate::doc::Doc;
use crate::registry;

/// Upper bound for the initial reservation; the hint comes from an HTTP header.
const MAX_CAPACITY_HINT: usize = 16 << 20;

#[inline]
fn guard<T>(default: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(default)
}

/// # Safety
/// `h` must be 0 or a pointer returned by `nCreate` that has not been passed to `nFree`.
#[inline]
unsafe fn doc_mut<'a>(h: jlong) -> Option<&'a mut Doc> {
    (h as *mut Doc).as_mut()
}

fn long_array(env: &JNIEnv, values: &[i64]) -> jlongArray {
    let Ok(arr) = env.new_long_array(values.len() as jsize) else {
        return ptr::null_mut();
    };
    if env.set_long_array_region(&arr, 0, values).is_err() {
        return ptr::null_mut();
    }
    arr.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nAbiVersion<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    crate::ABI_VERSION
}

#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nCreate<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    capacity_hint: jint,
) -> jlong {
    guard(0, || {
        let cap = (capacity_hint.max(0) as usize).min(MAX_CAPACITY_HINT);
        Box::into_raw(Box::new(Doc::with_capacity(cap))) as jlong
    })
}

/// Append `len` bytes of `buf`. Returns 0 on success.
#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nAppend<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    buf: JByteArray<'local>,
    len: jint,
) -> jint {
    guard(-1, || {
        let Some(doc) = (unsafe { doc_mut(handle) }) else {
            return -1;
        };
        if len < 0 {
            return -1;
        }
        let res = doc.append_with(len as usize, |dst| {
            // SAFETY: u8 and i8 have identical size and alignment, and `dst` is
            // initialised (zero-filled) memory exclusively borrowed for this call.
            let dst =
                unsafe { std::slice::from_raw_parts_mut(dst.as_mut_ptr() as *mut i8, dst.len()) };
            env.get_byte_array_region(&buf, 0, dst).is_ok()
        });
        match res {
            Ok(true) => 0,
            Ok(false) => -1,
            Err(e) => e.code(),
        }
    })
}

/// Index the appended bytes. Returns 0 on success, otherwise a `ParseError` code.
#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nFinish<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    skip_first_line: jboolean,
) -> jint {
    guard(-1, || {
        let Some(doc) = (unsafe { doc_mut(handle) }) else {
            return -1;
        };
        match doc.index(skip_first_line != 0) {
            Ok(()) => 0,
            Err(e) => e.code(),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nFree<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    guard((), || {
        if handle != 0 {
            // SAFETY: see `doc_mut`; Java clears its copy of the handle after this call.
            drop(unsafe { Box::from_raw(handle as *mut Doc) });
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nRoot<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlong {
    guard(api::UNSUPPORTED, || match unsafe { doc_mut(handle) } {
        Some(doc) => api::root(doc),
        None => api::UNSUPPORTED,
    })
}

/// Returns a path handle `>= 1`, or 0 if the expression is not supported natively.
#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nCompilePath<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jlong {
    guard(0, || {
        let Ok(s) = env.get_string(&path) else {
            return 0;
        };
        let s: String = s.into();
        registry::intern(&s).map_or(0, |h| h as jlong)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nEval<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    ctx: jlong,
    path: jlong,
) -> jlong {
    guard(api::UNSUPPORTED, || {
        let Some(doc) = (unsafe { doc_mut(handle) }) else {
            return api::UNSUPPORTED;
        };
        if path <= 0 || path > u32::MAX as jlong {
            return api::UNSUPPORTED;
        }
        match registry::get(path as u32) {
            Some(p) => api::eval(doc, ctx, &p),
            None => api::UNSUPPORTED,
        }
    })
}

/// Same as `nEval` for a throwaway expression: compiled on every call and never
/// kept in the registry (tests, diagnostics, dynamically built paths).
#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nEvalText<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    ctx: jlong,
    path: JString<'local>,
) -> jlong {
    guard(api::UNSUPPORTED, || {
        let Some(doc) = (unsafe { doc_mut(handle) }) else {
            return api::UNSUPPORTED;
        };
        let Ok(s) = env.get_string(&path) else {
            return api::UNSUPPORTED;
        };
        let s: String = s.into();
        match crate::path::Path::compile(&s) {
            Ok(p) => api::eval(doc, ctx, &p),
            Err(_) => api::UNSUPPORTED,
        }
    })
}

/// String value / number text, or `null` (other kinds, or not representable).
#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nText<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    node: jlong,
) -> jstring {
    guard(ptr::null_mut(), || {
        let Some(doc) = (unsafe { doc_mut(handle) }) else {
            return ptr::null_mut();
        };
        match api::text(doc, node) {
            // new_string() encodes to Java's modified UTF-8 (supplementary
            // characters as surrogate pairs), which NewStringUTF requires.
            Some(s) => env
                .new_string(&*s)
                .map_or(ptr::null_mut(), |j| j.into_raw()),
            None => ptr::null_mut(),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nElements<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    node: jlong,
) -> jlongArray {
    guard(ptr::null_mut(), || {
        let Some(doc) = (unsafe { doc_mut(handle) }) else {
            return ptr::null_mut();
        };
        match api::elements(doc, node) {
            Some(v) => long_array(&env, &v),
            None => ptr::null_mut(),
        }
    })
}

/// Diagnostics / tests: `[key0, value0, key1, value1, ...]`.
#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nMembers<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    node: jlong,
) -> jlongArray {
    guard(ptr::null_mut(), || {
        let Some(doc) = (unsafe { doc_mut(handle) }) else {
            return ptr::null_mut();
        };
        match api::members(doc, node) {
            Some(v) => long_array(&env, &v),
            None => ptr::null_mut(),
        }
    })
}

/// The bytes exactly as appended, so Java can replay the request on its own engine.
#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nBytes<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    guard(ptr::null_mut(), || {
        let Some(doc) = (unsafe { doc_mut(handle) }) else {
            return ptr::null_mut();
        };
        env.byte_array_from_slice(doc.data())
            .map_or(ptr::null_mut(), |a| a.into_raw())
    })
}

/// Diagnostics: `[input bytes, tape entries, native heap bytes]`.
#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nStats<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlongArray {
    guard(ptr::null_mut(), || {
        let Some(doc) = (unsafe { doc_mut(handle) }) else {
            return ptr::null_mut();
        };
        long_array(
            &env,
            &[
                doc.data().len() as i64,
                doc.tape_len() as i64,
                doc.heap_bytes() as i64,
            ],
        )
    })
}

/// Positions of the function bodies of a script, `"start,end,start,end,..."`
/// (see `crate::jsfn`). `null` if the source can't be parsed.
#[no_mangle]
pub extern "system" fn Java_com_liskovsoft_googlecommon_common_converters_jsonpath_engine_NativeJson_nJsFunctionSpans<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    source: JString<'local>,
) -> jstring {
    guard(ptr::null_mut(), || {
        let Ok(s) = env.get_string(&source) else {
            return ptr::null_mut();
        };
        let s: String = s.into();
        match crate::jsfn::function_body_spans_text(&s) {
            Some(text) => env
                .new_string(text)
                .map_or(ptr::null_mut(), |j| j.into_raw()),
            None => ptr::null_mut(),
        }
    })
}
