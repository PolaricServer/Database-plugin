# Critical Bugs Fixed

This document describes the serious bugs that were identified and fixed in the codebase.

## 1. NullPointerException in DBSession.getTrans() - CRITICAL

**Location:** `src/DBSession.java`, lines 92-98

**Severity:** Critical - Runtime crash

**Description:**
The `getTrans()` method retrieves a transaction from the `_inProgress` map using a key. If the key is not found in the map, `ti` will be null. The code then immediately calls `ti.cancel()` without checking for null, causing a NullPointerException.

**Impact:**
- Application crash when trying to retrieve a non-existent or expired transaction
- Potential for denial of service
- Loss of user data or requests in progress

**Fix:**
Added a null check before calling methods on the TransInfo object:
```java
public static DBSession getTrans(String key) {
    TransInfo ti = _inProgress.get(key);
    if (ti == null)
        return null;  // Return null instead of crashing
    ti.cancel();
    _inProgress.remove(key);
    return ti.trans;
}
```

## 2. Resource Leak in SignsApi.java POST /signs endpoint - HIGH

**Location:** `src/http/SignsApi.java`, lines 223-227

**Severity:** High - Resource leak

**Description:**
When the input cannot be parsed (sc==null), the ABORT method is called but there's no finally block to ensure `db.close()` is called. The code structure used `else try` which means the finally block only applies when parsing succeeds. This causes a database connection leak.

**Impact:**
- Database connections are not returned to the pool
- Eventually exhausts connection pool
- Application becomes unresponsive to new requests
- Requires application restart to recover

**Fix:**
- Added explicit `db.close()` call after ABORT
- Added explicit `return` statement to prevent further execution
- Removed the `else` keyword to ensure the finally block applies to all code paths

```java
if (sc==null) {
    ABORT(ctx, db, "POST /signs: cannot parse input", 400, "Cannot parse input");
    db.close();  // Ensure connection is closed
    return;       // Prevent further execution
}

try {
    // ... normal processing
}
catch (java.sql.SQLException e) {
    ABORT(ctx, db, "POST /signs: SQL error:"+e.getMessage(), 500, "SQL error: "+e.getMessage());
}
finally { db.close(); }
```

## 3. Missing Return Statement in RestApi.java GET /open/objects - HIGH

**Location:** `src/http/RestApi.java`, lines 352-359

**Severity:** High - Logic error and potential NullPointerException

**Description:**
When an object is not found (`a == null`), the ABORT method is called to send an error response, but execution continues. The code then tries to commit the transaction and serialize a null object with `ctx.json(a)`, which could cause issues.

**Impact:**
- Unnecessary database commit after error condition
- Potential NullPointerException when serializing null object
- Incorrect behavior - error response sent but processing continues
- Confusion in logs with multiple operations on failed requests

**Fix:**
Added a return statement after ABORT to prevent further execution:

```java
if (a == null) {
    ABORT(ctx, db, "GET /open/objects/"+tag+"/"+id+": Item not found: ",
        404, "Item not found: "+tag+": "+id);
    return;  // Stop processing after error
}
db.commit();
ctx.json(a);
```

## 4. Slow Performance in /hist/snapshot Query - MEDIUM

**Location:** `src/MyDBSession.java`, lines 282-310

**Severity:** Medium - Performance issue

**Description:**
The `/hist/snapshot/{x1}/{x2}/{x3}/{x4}` REST endpoint was slow due to an inefficient database query in `getTrailsAt()`. The query had three main performance issues:

1. Used `ST_Contains()` instead of the faster `&&` (overlaps) operator for spatial filtering
2. Time filter `pr.time + INTERVAL '2 hour' > ?` prevented PostgreSQL from using the time index
3. Missing composite index on `PosReport(src, time)` for efficient ORDER BY

**Impact:**
- Slow response times for historical snapshot queries
- Poor user experience when viewing historical map data
- Increased database load and resource consumption
- Potential timeout issues on large geographic areas

**Fix:**
Applied three optimizations:

1. **Replaced ST_Contains with && operator:**
```sql
-- Before: ST_Contains(ST_MakeEnvelope(...), position)
-- After:  position && ST_MakeEnvelope(...)
```
The `&&` operator uses the GiST index more efficiently and only checks bounding box overlap, which is sufficient for this use case.

2. **Rewrote time filter to enable index usage:**
```sql
-- Before: pr.time <= ? AND pr.time + INTERVAL '2 hour' > ?
-- After:  pr.time <= ? AND pr.time > ? - INTERVAL '2 hour'
```
Moving the calculation to the parameter side allows PostgreSQL to use the time index.

3. **Added composite index:**
```sql
CREATE INDEX posreport_src_time_idx ON "PosReport" (src, time);
```
This index significantly speeds up the `ORDER BY pr.src, pr.time DESC` clause.

**Schema Changes:**
- Updated schema version from 9 to 13
- Added new index in `DbInstaller.java` for fresh installations
- Added upgrade path in `scripts/polaric-dbupgrade-pguser` for existing databases

## Summary

All four issues have been fixed with minimal code changes:
- **Bug 1:** 2 lines added (null check and early return)
- **Bug 2:** 3 lines modified (added explicit cleanup and return)
- **Bug 3:** 2 lines modified (added braces and return statement)
- **Bug 4:** 3 lines modified in query, 1 index added

These fixes prevent:
- Application crashes from NullPointerException
- Database connection leaks that degrade performance over time
- Logic errors that cause incorrect behavior
- Poor query performance and slow response times

The fixes are surgical and minimal, addressing only the specific issues without changing the overall architecture or behavior of the application.
