# Kronos ORM iOS Example

A SwiftUI app that creates a SQLite table, inserts a person in a transaction, and reads the record through the Kronos Kotlin DSL. The shared Kotlin code also runs on macOS, Linux, and Windows.

The app displays `SQLite connected: id=1, name=Kronos iOS`. SQLite operations run outside the UI thread.

## Define a table

[NativePerson.kt](shared/src/commonMain/kotlin/com/kotlinorm/example/ios/NativePerson.kt) defines the shared model:

```kotlin
import com.kotlinorm.annotations.PrimaryKey
import com.kotlinorm.interfaces.KPojo

enum class NativeStatus { READY, DONE }

data class NativePerson(
    @PrimaryKey(identity = true) var id: Int? = null,
    var name: String? = null,
    var status: NativeStatus? = null,
    var age: Int? = null
) : KPojo
```

## Connect to SQLite

[SqliteDemo.kt](shared/src/nativeMain/kotlin/com/kotlinorm/example/ios/SqliteDemo.kt) connects the application-owned SQLite adapter to Kronos, initializes generated metadata, and performs the insert and query:

```kotlin
initializeOrmExample()
val database = KronosSqliteDataSource()
val previous = Kronos.dataSource
val wrapper = KronosExecutionWrapper(database)
Kronos.dataSource = { wrapper }
try {
    wrapper.table.createTable(NativePerson())
    val inserted = database.transact(
        KronosTransactionOptions(KronosTransactionMode.IMMEDIATE)
    ) {
        NativePerson(name = name, status = NativeStatus.READY)
            .insert().withId().execute()
    }
    val insertedId = checkNotNull(inserted.lastInsertId).toInt()
    val person = NativePerson().select().where { it.id == insertedId }.toList().single()
    return "SQLite connected: id=${person.id}, name=${person.name}"
} finally {
    Kronos.dataSource = previous
    database.close()
}
```

The SwiftUI app calls the same shared entry point:

```swift
SqliteDemo.shared.insertAndRead(name: "Kronos iOS")
```

## Run the iOS app

Install JDK 21, Xcode, and [XcodeGen](https://github.com/yonaskolb/XcodeGen). Dependency versions are managed in [gradle/libs.versions.toml](gradle/libs.versions.toml): Kotlin 2.4.21 and Kronos 0.4.0.

Build the Kronos artifacts from the sibling source checkout:

```sh
cd ../Kronos-orm
scripts/run release stage --repository build/release/repository
cd ../kronos-example-ios
```

Add the absolute staged repository path to your `~/.gradle/gradle.properties`, so both terminal builds and Xcode's Gradle build script resolve the same artifacts:

```properties
kronosRepository=/absolute/path/to/Kronos-orm/build/release/repository
```

Generate and open the Xcode project:

```sh
brew install xcodegen
(cd iosApp && xcodegen generate)
open iosApp/KronosIosApp.xcodeproj
```

Select the `KronosIosApp` scheme and an iOS simulator, then run. The Xcode build script builds and links the matching Kotlin framework with `embedAndSignAppleFrameworkForXcode`. The `KronosIosAppUITests` target launches the app and checks the SQLite result on screen.

## Run shared tests and desktop apps

```sh
# Intel macOS
./gradlew :shared:macosX64Test :shared:runDebugExecutableMacosX64

# Apple Silicon and iOS simulator
./gradlew :shared:macosArm64Test :shared:iosSimulatorArm64Test :shared:runDebugExecutableMacosArm64

# Linux x64
./gradlew :shared:linuxX64Test :shared:runDebugExecutableLinuxX64

# Windows, with MinGW GCC and ar on PATH
./gradlew :shared:mingwX64Test :shared:runDebugExecutableMingwX64
```

On Windows, `gradlew.bat` provides the same tasks. Custom C tool paths can be supplied with `-PsqliteCc=/path/to/gcc` and `-PsqliteAr=/path/to/ar`.

The shared tests exercise real SQLite CRUD, generated IDs, nullable values, enum/JSON/blob conversion, transactions and savepoints, concurrent access, file persistence, schema changes, and connection recovery. ORM tests use the compiler-generated KPojo metadata and DSL.

Statement errors preserve the connection and existing data. A driver error inside an owned transaction aborts the transaction and rolls it back, even when the callback catches the error. Only a broken connection or unknown driver failure requires `reopen()`; reopening an in-memory database creates a new, empty database.

Declared targets: iOS arm64/x64/simulatorArm64; macOS arm64/x64; tvOS arm64/x64/simulatorArm64; watchOS arm64/deviceArm64/x64/simulatorArm64; Linux x64/arm64; Windows MinGW x64.

## Project structure

```text
iosApp/                         SwiftUI app and UI test
shared/src/
  commonMain/                   KPojo model and metadata initialization
  nativeMain/                   SQLite adapter and shared application entry
  nativeTest/                   SQLite and ORM tests, including test-only models
  bundledMain/                  AndroidX bundled SQLite driver for Apple and Linux
  appleMain/                    Apple locking and thread identity
  linuxMain/                    Linux locking and thread identity
  mingwX64Main/                 Windows SQLite C API driver and locking
  nativeInterop/                SQLite headers, C source, and source lock
```

Apple and Linux use AndroidX bundled SQLite 2.6.2. Windows compiles the vendored SQLite 3.54.1 source with MinGW. Both drivers implement the same connection and statement interfaces, and use the same adapter and tests.

## License

The example code is licensed under Apache License 2.0; see [LICENSE](LICENSE). SQLite is public domain; its source version and checksums are recorded in [the SQLite source directory](shared/src/nativeInterop/sqlite/README.md).

## Logging

Kronos logs to the console by default. Disable logging with the shared switch; re-enabling preserves the adapter, paths and custom factory:

```kotlin
Kronos.logging.enabled = false
Kronos.logging.enabled = true
```
