SQLite 3.54.1 amalgamation (public domain).

Source: https://www.sqlite.org/2026/sqlite-amalgamation-3540100.zip
Official download manifest: https://www.sqlite.org/download.html
Archive SHA3-256: 92281c86e2957f9b9305890f9ac04204caa7b677cc868dc326d942689df28e30

The unmodified sqlite3.c and sqlite3.h are vendored so the MinGW backend compiles from source without
fetching a platform DLL at runtime. sqlite_bridge.h is example-owned glue for SQLITE_TRANSIENT.
The build uses SQLITE_THREADSAFE=1 and SQLITE_OMIT_LOAD_EXTENSION=1.
