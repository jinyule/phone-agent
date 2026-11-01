package dev.phoneagent.privileged;

import android.content.Intent;
import android.os.Bundle;
import android.view.Surface;

// Typed surface of the shell-uid (2000) helper (docs/design/phase1-architecture.md §3.5).
// There is deliberately no "run a command" method.
//
// Wire rules:
// - Every method has an explicit transaction id. Ids 1-4 are frozen: the same id and signature
//   in every protocol version, so any app build can read a helper's version and identity and
//   make it release and exit, whatever version the helper runs (engineering-baseline §6).
// - Only the app uid the helper was started for may call; other callers get SecurityException.
// - A method the helper does not implement throws UnsupportedOperationException whose message
//   starts with PrivilegedError.NOT_IMPLEMENTED.
// - Any change to this file must bump PrivilegedProtocol.VERSION and append the new fingerprint
//   to privileged/protocol-history.txt (checked by :privileged:verifyPrivilegedProtocol).
interface IPrivilegedCore {
    // ---- Frozen (ids 1-4). ----

    // PrivilegedProtocol.VERSION compiled into the helper.
    int protocolVersion() = 1;

    // [uid, pid] of the helper process; later versions may append elements, never reorder.
    int[] identity() = 2;

    // Releases everything the helper holds for the app (displays, mutes); idempotent.
    void releaseAll() = 3;

    // releaseAll(), then the helper process exits.
    oneway void shutdown() = 4;

    // ---- Capabilities. ----

    // Capability probe: builds a small display and injects one harmless tap.
    Bundle probe() = 10;

    // Flags grow with the API level; returns the displayId, negative on error.
    int createDisplay(in Surface s, int w, int h, int dpi) = 11;

    void releaseDisplay(int displayId) = 12;

    // Does not start an app the user is currently using on the main screen.
    int startActivity(int displayId, in Intent intent, int userId, boolean cold) = 13;

    int topTask(int displayId) = 14;

    boolean moveTask(int taskId, int displayId) = 15;

    // Starts watching the main screen before an action.
    int watchMainScreen(int windowMs) = 16;

    // After the action: tasks that surfaced on the main screen since sinceUptimeMs.
    Bundle mainArrivals(long sinceUptimeMs) = 17;

    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] t) = 18;

    // Allow-listed key codes only.
    boolean injectKey(int displayId, int keyCode) = 19;

    boolean setAudioMuted(String pkg, boolean muted) = 20;

    // Only WRITE_SECURE_SETTINGS is allowed.
    int grantSelf(String permission) = 21;
}
