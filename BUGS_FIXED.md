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

## Summary

All three bugs have been fixed with minimal code changes:
- **Bug 1:** 2 lines added (null check and early return)
- **Bug 2:** 3 lines modified (added explicit cleanup and return)
- **Bug 3:** 2 lines modified (added braces and return statement)

These fixes prevent:
- Application crashes from NullPointerException
- Database connection leaks that degrade performance over time
- Logic errors that cause incorrect behavior

The fixes are surgical and minimal, addressing only the specific bugs without changing the overall architecture or behavior of the application.
