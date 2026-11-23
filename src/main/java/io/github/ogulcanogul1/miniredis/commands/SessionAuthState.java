package io.github.ogulcanogul1.miniredis.commands;

/**
 * Per-connection authentication and protected-mode state. Owned by a single connection/session
 * thread (or reactor loop), not shared across threads.
 */
public final class SessionAuthState {

    private static final SessionAuthState UNRESTRICTED = new SessionAuthState(true, false);

    private boolean authenticated;
    private final boolean protectedModeBlocked;

    public SessionAuthState(boolean authenticated, boolean protectedModeBlocked) {
        this.authenticated = authenticated;
        this.protectedModeBlocked = protectedModeBlocked;
    }

    /** Returns an unrestricted state for in-process unit tests without network auth. */
    public static SessionAuthState unrestricted() {
        return UNRESTRICTED;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    void markAuthenticated() {
        this.authenticated = true;
    }

    public boolean isProtectedModeBlocked() {
        return protectedModeBlocked;
    }
}
