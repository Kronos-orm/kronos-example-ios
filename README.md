# Kronos Native SQLite example

This application owns its SQLite adapter and driver selection. Kronos publishes the common execution
contracts and ORM runtime; no separate SQLite wrapper artifact is required.

The sample uses Kotlin 2.4.21 / Kronos 0.4.0-SNAPSHOT. Apple and Linux use AndroidX bundled
SQLite 2.6.2. MinGW builds the vendored, checksum-recorded SQLite 3.54.1 amalgamation through the example's
C API driver. Android/JVM remains in the existing `kronos-example-android` repository.

## Run a real SQLite connection

Build Kronos with Kotlin Toolchain and pass its staged Maven repository to the example:

```bash
cd ../Kronos-orm
python3 tools/release.py stage --repository build/release/repository
cd ../kronos-example-ios
```

The repository contains the compiler plugin, Gradle consumer plugin and all Native runtime variants.

```sh
# Intel macOS: AndroidX backend plus desktop executable
./gradlew -PkronosRepository=../Kronos-orm/build/release/repository :shared:macosX64Test :shared:runDebugExecutableMacosX64

# Intel macOS: the same tests through the C API backend used on Windows
./gradlew -PkronosRepository=../Kronos-orm/build/release/repository -PsqliteBackend=c :shared:macosX64Test

# Apple Silicon: desktop and iOS simulator
./gradlew -PkronosRepository=../Kronos-orm/build/release/repository :shared:macosArm64Test :shared:iosSimulatorArm64Test

# Linux x64
./gradlew -PkronosRepository=../Kronos-orm/build/release/repository :shared:linuxX64Test :shared:runDebugExecutableLinuxX64

# Windows: install MinGW GCC/ar and put its bin directory on PATH first
./gradlew -PkronosRepository=../Kronos-orm/build/release/repository :shared:mingwX64Test :shared:runDebugExecutableMingwX64
```

The MinGW source build accepts `-PsqliteCc=/path/to/gcc` and `-PsqliteAr=/path/to/ar`. Cross-compiling this
C backend requires a matching C compiler/sysroot. The default Windows gate builds natively.

Run `python3 scripts/verify-sqlite-source.py` to check the vendored C source and header against
`shared/src/nativeInterop/sqlite/source-lock.json`. CI runs the same check before building.

Declared targets: iOS arm64/x64/simulatorArm64; macOS arm64/x64; tvOS arm64/x64/simulatorArm64;
watchOS arm64/deviceArm64/x64/simulatorArm64; Linux x64/arm64; MinGW x64.

## iOS app

`iosApp/KronosIosApp.swift` displays the result of creating a table, inserting a generated
`NativePerson` in a transaction, and querying it through the Kronos DSL. The SQLite work runs off the UI thread.

```sh
brew install xcodegen
cd iosApp
xcodegen generate
# For local source development, expose the same property to Xcode's Gradle script:
export ORG_GRADLE_PROJECT_kronosRepository=../Kronos-orm/build/release/repository
open KronosIosApp.xcodeproj
```

Select the `KronosIosApp` scheme and a supported simulator. Its build script links the matching Kotlin
framework through `embedAndSignAppleFrameworkForXcode`. Simulator builds do not require signing.

## Adapter behavior

The synchronous adapter owns one connection and one reentrant lock. A new source can lazy-open. Explicit
close requires explicit `open()`/`reopen()` afterward; reopening an in-memory database intentionally starts
empty. Driver errors enter `FAILED` and require explicit `reopen()`. Application mapper exceptions leave
the connection usable. Closing from inside an operation expires active rows, closes statements, and rolls back an active
transaction. That operation aborts explicitly. Opening/reopening remains rejected until the earlier
operation has unwound.

Transaction scopes are thread-owned and expire with their callback. Foreign-thread transaction calls fail
before acquiring the connection lock. Nested transactions use unique opaque savepoints; released or expired
handles fail before reaching SQLite. Transaction timeouts are unsupported and are rejected explicitly.

The lock covers statement preparation, one-based binding, zero-based cursor advancement, mapper lifetime,
and generated-key capture. Rows expire after their mapper returns. Duplicate labels require positional reads.
Batches reuse one statement and return per-entry affected counts; batch generated keys remain empty.
Generated-row IDs require the requested column to be SQLite's single `INTEGER PRIMARY KEY` rowid
alias. `WITHOUT ROWID`, compound, non-INTEGER, and primary keys backed by a separate SQLite index reject capture before
executing the insert. A zero-row ignored insert returns no ID. UPSERT
conflict-update plans must not request generated keys, because SQLite's last-insert-rowid does not identify
the updated row.

Tests include real CRUD/binding, nullable/enum/JSON codec round trips, rollback/savepoints, worker concurrency,
generated IDs, file persistence, WAL/schema rebuild/index recreation, lifecycle recovery, parser comments and
quoted identifiers, and statement ownership. JSON serialization is a Native test dependency managed by the
example's version catalog.

KPojo/DSL integration is enabled by default. Application commonMain calls `initializeOrmExample()`
before ORM access to initialize generated metadata. Use `-PenableOrm=false` to run the connection-contract
tests independently of generated KPojo metadata.

## License

The example code is licensed under Apache License 2.0; see [LICENSE](LICENSE). The vendored SQLite
amalgamation is public domain; its source, version, and archive checksum are recorded in
[the SQLite source directory](shared/src/nativeInterop/sqlite/README.md).
