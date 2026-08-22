package com.piecetrack.domain;

/**
 * Computed fresh from the stored {@link License} row every time {@code LicenseService.state()}
 * is called - never itself persisted, the same reasoning FR-SYS-02's balance columns avoid
 * storage (see {@code docs/02-data-model.md}): a stored state would drift from the lease it
 * was derived from the moment the lease is renewed or expires.
 */
public enum LicenseState {

    /** Valid signature, fingerprint matches this machine, and the lease has not expired. */
    ACTIVE,

    /** Same as {@link #ACTIVE}, but the lease expires within 7 days - everything still works,
     *  with a persistent warning banner so a connectivity problem gets noticed before it
     *  turns into a wind-down. */
    GRACE,

    /** The lease is expired, revoked, signed by the wrong key, bound to a different machine,
     *  or the system clock has been rolled back past the stored watermark. The app becomes
     *  read-only (M14) - never a hard lock, since any of these can also be a false positive
     *  (a legitimate hardware swap, a long stretch offline). */
    WIND_DOWN,

    /** No licence row exists at all - the setup wizard cannot be completed without one. */
    UNLICENSED
}
