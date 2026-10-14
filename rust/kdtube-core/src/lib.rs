//! KDTube native core.
//!
//! The part of the app that turns multi-megabyte YouTube JSON responses into
//! model objects used to dominate transient memory use. This crate replaces the
//! document model and the path evaluation with a zero-copy index:
//!
//! * [`doc`]  - the JSON bytes plus an 8-bytes-per-token structural index,
//! * [`path`] - the JsonPath dialect used by the `@JsonPath` annotations,
//! * [`eval`] - evaluation with the exact semantics of Jayway 2.4.0 + Gson,
//! * [`api`]  - value handles packed into `i64` for cheap JNI traffic,
//! * [`jsfn`] - where the function bodies of a script are (for the JS challenge solvers),
//! * `jni_api` - the `System.loadLibrary("kdtube_core")` entry points.
//!
//! The Java mapper (`JsonPathTypeAdapter`) keeps all of its reflection logic and
//! simply navigates native handles instead of Gson trees. Whenever anything here
//! reports "unsupported" or fails, the same request is replayed on the original
//! Jayway/Gson engine, so the native path can only ever reduce memory, not
//! change results.

pub mod api;
pub mod doc;
pub mod eval;
mod jni_api;
pub mod jsfn;
pub mod path;
pub mod registry;

/// Bumped whenever the JNI contract changes. Java refuses to use a library that
/// reports a different number (and silently falls back to the Java engine).
pub const ABI_VERSION: i32 = 2;
