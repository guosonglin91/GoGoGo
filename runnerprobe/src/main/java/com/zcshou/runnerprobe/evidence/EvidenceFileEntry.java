package com.zcshou.runnerprobe.evidence;

import java.util.Locale;
import java.util.regex.Pattern;

public final class EvidenceFileEntry {
    private static final Pattern FINAL_FILE_NAME =
            Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern SHA256 =
            Pattern.compile("[0-9a-fA-F]{64}");

    private final String name;
    private final long bytes;
    private final String sha256;

    public EvidenceFileEntry(String name, long bytes, String sha256)
            throws EvidenceValidationException {
        if (name == null
                || !FINAL_FILE_NAME.matcher(name).matches()
                || name.equals(".")
                || name.equals("..")
                || name.contains("..")
                || name.endsWith(".partial")) {
            throw new EvidenceValidationException(
                    "INVALID_EVIDENCE_FILE_NAME",
                    "Invalid finalized evidence file name: " + name
            );
        }
        if (bytes < 0L) {
            throw new EvidenceValidationException(
                    "INVALID_EVIDENCE_FILE_SIZE",
                    "Evidence file size must be non-negative"
            );
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new EvidenceValidationException(
                    "INVALID_EVIDENCE_HASH",
                    "Evidence SHA-256 must contain exactly 64 hexadecimal characters"
            );
        }

        this.name = name;
        this.bytes = bytes;
        this.sha256 = sha256.toLowerCase(Locale.US);
    }

    public String getName() {
        return name;
    }

    public long getBytes() {
        return bytes;
    }

    public String getSha256() {
        return sha256;
    }
}
