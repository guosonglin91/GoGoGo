package com.zcshou.runnerprobe.evidence;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class EvidenceManifest {
    public static final String ROLE_PRODUCER = "producer";
    public static final String ROLE_CONSUMER = "consumer";

    private final String schemaVersion;
    private final String sessionId;
    private final String role;
    private final boolean finalized;
    private final List<EvidenceFileEntry> files;

    public EvidenceManifest(
            String schemaVersion,
            String sessionId,
            String role,
            boolean finalized,
            List<EvidenceFileEntry> files
    ) throws EvidenceValidationException {
        this.schemaVersion = EvidenceSchema.requireSupported(schemaVersion);
        this.sessionId = SessionId.validate(sessionId);

        if (!ROLE_PRODUCER.equals(role) && !ROLE_CONSUMER.equals(role)) {
            throw new EvidenceValidationException(
                    "INVALID_MANIFEST_ROLE",
                    "Manifest role must be producer or consumer"
            );
        }
        this.role = role;
        this.finalized = finalized;

        if (files == null) {
            throw new EvidenceValidationException(
                    "INVALID_EVIDENCE_FILE_LIST",
                    "Manifest file list must not be null"
            );
        }

        Set<String> names = new HashSet<>();
        for (EvidenceFileEntry entry : files) {
            if (entry == null) {
                throw new EvidenceValidationException(
                        "INVALID_EVIDENCE_FILE_LIST",
                        "Manifest file list contains null"
                );
            }
            if (!names.add(entry.getName())) {
                throw new EvidenceValidationException(
                        "DUPLICATE_EVIDENCE_FILE",
                        "Duplicate manifest entry: " + entry.getName()
                );
            }
        }
        this.files = Collections.unmodifiableList(files);
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRole() {
        return role;
    }

    public boolean isFinalized() {
        return finalized;
    }

    public List<EvidenceFileEntry> getFiles() {
        return files;
    }

    public void verifyFinalizedDirectory(File root) throws EvidenceValidationException {
        if (!finalized) {
            throw new EvidenceValidationException(
                    "SESSION_NOT_FINALIZED",
                    "Evidence manifest is not finalized"
            );
        }
        if (root == null || !root.isDirectory()) {
            throw new EvidenceValidationException(
                    "EVIDENCE_DIRECTORY_MISSING",
                    "Evidence root directory does not exist"
            );
        }

        final String canonicalRoot;
        try {
            canonicalRoot = root.getCanonicalPath() + File.separator;
        } catch (IOException e) {
            throw new EvidenceValidationException(
                    "EVIDENCE_FILE_READ_FAILURE",
                    "Unable to resolve evidence root",
                    e
            );
        }

        for (EvidenceFileEntry entry : files) {
            File file = new File(root, entry.getName());
            final String canonicalFile;
            try {
                canonicalFile = file.getCanonicalPath();
            } catch (IOException e) {
                throw new EvidenceValidationException(
                        "EVIDENCE_FILE_READ_FAILURE",
                        "Unable to resolve evidence file: " + entry.getName(),
                        e
                );
            }

            if (!canonicalFile.startsWith(canonicalRoot)) {
                throw new EvidenceValidationException(
                        "INVALID_EVIDENCE_FILE_NAME",
                        "Evidence file escapes session directory: " + entry.getName()
                );
            }
            if (!file.isFile()) {
                throw new EvidenceValidationException(
                        "EVIDENCE_FILE_MISSING",
                        "Missing evidence file: " + entry.getName()
                );
            }
            if (file.length() != entry.getBytes()) {
                throw new EvidenceValidationException(
                        "EVIDENCE_SIZE_MISMATCH",
                        "Evidence file size mismatch: " + entry.getName()
                );
            }

            String actual = sha256(file);
            if (!entry.getSha256().equals(actual)) {
                throw new EvidenceValidationException(
                        "EVIDENCE_HASH_MISMATCH",
                        "Evidence SHA-256 mismatch: " + entry.getName()
                );
            }
        }
    }

    public static String sha256(File file) throws EvidenceValidationException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new EvidenceValidationException(
                    "HASH_ALGORITHM_UNAVAILABLE",
                    "SHA-256 is unavailable",
                    e
            );
        }

        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new EvidenceValidationException(
                    "EVIDENCE_FILE_READ_FAILURE",
                    "Unable to hash evidence file: " + file.getName(),
                    e
            );
        }

        byte[] value = digest.digest();
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) {
            out.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return out.toString();
    }
}
