package com.piecetrack.util;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.IntByReference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Binds a licence to one physical machine (M14, FR-LIC-02). {@code jna-platform} is already a
 * dependency for {@link WindowsDpapi}, so this needs no new library.
 * <p>
 * Two components, deliberately weighted differently:
 * <ul>
 *   <li>{@link #stableId()} - the Windows {@code MachineGuid}
 *       ({@code HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid}), generated once at OS
 *       install and stable across a disk swap, a drive letter change, or a hardware repair.
 *       This is the value a lease is actually bound to.</li>
 *   <li>{@link #volumeSerial()} - the {@code C:} volume serial number, reported to the
 *       licence server as a soft signal only ("hardware changed") and never itself a reason
 *       to wind down. A fresh format changes it; that must never cost a shop their licence.</li>
 * </ul>
 * Binding only the stable component is what keeps false positives near zero - see the M14
 * plan's threat-model table for why a hard failure here would be worse than the piracy this
 * exists to catch.
 */
public final class MachineFingerprint {

    private MachineFingerprint() {
    }

    /** SHA-256 hex digest of {@link #stableId()} - the value sent as {@code fingerprint} and
     *  the one a lease's {@code fp} claim is checked against. Never sent in raw form: the
     *  MachineGuid itself is a stable per-PC identifier and there is no reason to expose it
     *  to a third party beyond what activation strictly needs. */
    public static String stableId() {
        return sha256Hex(readMachineGuid());
    }

    /** The raw {@code C:} volume serial number as an 8-digit hex string, or {@code null} if
     *  it could not be read (e.g. a non-{@code C:} install root) - reported alongside the
     *  fingerprint for the server's activity log, never enforced against. */
    public static String volumeSerial() {
        char[] volumeNameBuffer = new char[261];
        IntByReference serialNumber = new IntByReference();
        IntByReference maxComponentLength = new IntByReference();
        IntByReference fileSystemFlags = new IntByReference();
        char[] fileSystemNameBuffer = new char[261];

        boolean ok = Kernel32.INSTANCE.GetVolumeInformation("C:\\", volumeNameBuffer, volumeNameBuffer.length,
                serialNumber, maxComponentLength, fileSystemFlags, fileSystemNameBuffer, fileSystemNameBuffer.length);
        return ok ? String.format("%08X", serialNumber.getValue()) : null;
    }

    private static String readMachineGuid() {
        return Advapi32Util.registryGetStringValue(
                WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Cryptography", "MachineGuid");
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JDK (Java Cryptography Architecture
            // Standard Algorithm Names) - this can never actually happen.
            throw new IllegalStateException(e);
        }
    }
}
