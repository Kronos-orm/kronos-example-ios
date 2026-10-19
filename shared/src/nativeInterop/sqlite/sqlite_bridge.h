#include "sqlite3.h"
/* SQLITE_TRANSIENT is a function-pointer cast macro, so expose ordinary C functions to cinterop. */
static int kronos_sqlite_bind_text(sqlite3_stmt *s, int index, const char *value, int size) {
    return sqlite3_bind_text(s, index, value, size, SQLITE_TRANSIENT);
}
static int kronos_sqlite_bind_blob(sqlite3_stmt *s, int index, const void *value, int size) {
    return sqlite3_bind_blob(s, index, value, size, SQLITE_TRANSIENT);
}
