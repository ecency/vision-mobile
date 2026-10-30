# Can wax's C++ core replace our Kotlin Hive core?

Desk assessment for #3573 ("Also check"). We did not build anything: this comes from reading wax's public sources (October 2026). Anything marked "not verified" is an inference.

## What we have

`native/shared/.../core/hive` is about 1,150 lines of Kotlin. It covers binary serialization for the roughly 30 operations the app broadcasts (vote, comment, transfers, savings, delegation, power up and down, conversions, orders, recurrent transfers, governance votes, account updates, custom_json), plus the transaction digest, WIF and public key encoding, and key derivation from the master password. Signing uses `fr.acinq.secp256k1` (secp256k1-kmp: JNI on Android, the native library on iOS). Hashing uses KotlinCrypto. Golden vectors generated from @ecency/sdk check all of it on both platforms.

## What wax is

- One repository with three bindings over a shared C++ core (`core/`): TypeScript via Emscripten/WASM, Python via Cython, and a newer Rust crate via `cxx`. The core links Hive's own `libraries/protocol` and `fc` from the `hive` submodule, so it serializes with the chain's own code. [README](https://gitlab.syncad.com/hive/wax/-/blob/develop/README.md), [ts/ARCHITECTURE.md](https://gitlab.syncad.com/hive/wax/-/blob/develop/ts/ARCHITECTURE.md), [rust/README.md](https://gitlab.syncad.com/hive/wax/-/blob/develop/rust/README.md)
- Dependencies: Boost 1.74 or newer (chrono, date_time, filesystem, program_options, system), OpenSSL, CMake, C++17, and secp256k1-zkp vendored in fc. [fc CMakeLists](https://gitlab.syncad.com/hive/fc/-/blob/main/CMakeLists.txt), [wasm_project.cmake](https://gitlab.syncad.com/hive/wax/-/blob/develop/ts/wasm/src/wasm_project.cmake)
- License: MIT ([LICENSE.md](https://gitlab.syncad.com/hive/wax/-/blob/develop/LICENSE.md)).
- Activity: active, with tags every few weeks (1.28.8 on 2026-10-05, a 1.29.0 release candidate in progress). npm `@hiveio/wax` is at 2.0.2. The Rust crates are not published yet. [npm](https://www.npmjs.com/package/@hiveio/wax)
- Beyond our scope: the full operation set from the protocol's protobuf definitions, transaction validation, required authorities, signature minimization, asset and HP/APR math, formatters, and an API client layer.
- What it does not do: the core API (`core/foundation.hpp`) has no transaction signing and no memo encryption. It only encodes and decodes the memo envelope. Signing goes to pluggable signers such as Beekeeper. So we would keep secp256k1-kmp either way.

## Mobile build feasibility

No iOS or Android build exists that we could find: wax's issue tracker has none, published Python wheels are `manylinux x86_64` only, and the Rust bundle ships prebuilt for `x86_64-unknown-linux-gnu`. Absence is not verified beyond that search.

Technically it looks possible. The WASM target shows the "minimal fc" configuration already cross-compiles away from Linux, and fc has an Apple code path. The work would be (all not verified):

- **iOS**: cross-compile Boost, OpenSSL (or drop it if the minimal build allows), secp256k1-zkp, fc and protocol for arm64 device and arm64 simulator, then package an XCFramework. Kotlin/Native cinterop needs a plain C header, so we would write a C wrapper over the C++ foundation API.
- **Android**: the same libraries through the NDK for arm64-v8a and x86_64 at least, plus a JNI layer. KMP's `androidMain` would call JNI and `iosMain` cinterop, behind one `expect` interface.
- **Size**: `wax.common.wasm` is 2.45 MB. Native code per ABI is probably similar, a few MB (not verified). Our Kotlin core is a small fraction of that.

## Trade-off

wax's benefit is real: the chain's own serializer, so new operations and hardfork format changes arrive with a submodule bump. Its costs are also real: a C++ cross-compile toolchain for two OSes and several ABIs owned by us alone, a C and JNI bridge to maintain, a larger app, and slower builds. We would also track upstream changes that no mobile consumer tests. Wax would not remove our crypto dependency, because it does not sign.

Our port only covers what the app broadcasts. Hive adds operations rarely and keeps the wire format stable. Golden vectors from the SDK catch drift, and a new operation is one small Kotlin class plus a vector.

## Recommendation

Keep the Kotlin port. Revisit wax if any of these happens: Hive changes serialization in a hardfork, the app needs many operations we do not have (for example witness or full account tooling), or upstream ships official iOS/Android builds or a stable C API. A cheap middle path is to generate extra golden vectors from wax (its npm package runs in Node) next to the SDK ones, so the Kotlin core is checked against the chain's own serializer without shipping C++.
