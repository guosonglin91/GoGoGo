package com.zcshou.runnerprobe.evidence;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class EvidenceManifestTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void finalizedManifestVerifiesUntamperedFile() throws Exception {
        File root = temporaryFolder.newFolder("session_v2e_001");
        File payload = new File(root, "location_events.csv");
        Files.write(payload.toPath(), "header\nrow\n".getBytes(StandardCharsets.UTF_8));

        EvidenceFileEntry entry = new EvidenceFileEntry(
                payload.getName(),
                payload.length(),
                EvidenceManifest.sha256(payload)
        );
        EvidenceManifest manifest = new EvidenceManifest(
                EvidenceSchema.VERSION,
                "v2e_001",
                EvidenceManifest.ROLE_CONSUMER,
                true,
                Collections.singletonList(entry)
        );

        manifest.verifyFinalizedDirectory(root);
    }

    @Test
    public void notFinalizedIsRejected() throws Exception {
        File root = temporaryFolder.newFolder("session_v2e_002");
        EvidenceManifest manifest = new EvidenceManifest(
                EvidenceSchema.VERSION,
                "v2e_002",
                EvidenceManifest.ROLE_CONSUMER,
                false,
                Collections.emptyList()
        );

        assertCode("SESSION_NOT_FINALIZED",
                () -> manifest.verifyFinalizedDirectory(root));
    }

    @Test
    public void tamperedFileFailsHashCheck() throws Exception {
        File root = temporaryFolder.newFolder("session_v2e_003");
        File payload = new File(root, "step_detector_events.csv");
        Files.write(payload.toPath(), "original".getBytes(StandardCharsets.UTF_8));

        long originalSize = payload.length();
        String originalHash = EvidenceManifest.sha256(payload);
        EvidenceManifest manifest = new EvidenceManifest(
                EvidenceSchema.VERSION,
                "v2e_003",
                EvidenceManifest.ROLE_CONSUMER,
                true,
                Collections.singletonList(
                        new EvidenceFileEntry(payload.getName(), originalSize, originalHash)
                )
        );

        Files.write(payload.toPath(), "changed!".getBytes(StandardCharsets.UTF_8));
        assertEquals(originalSize, payload.length());

        assertCode("EVIDENCE_HASH_MISMATCH",
                () -> manifest.verifyFinalizedDirectory(root));
    }

    @Test
    public void truncatedFileFailsSizeCheck() throws Exception {
        File root = temporaryFolder.newFolder("session_v2e_004");
        File payload = new File(root, "step_counter_events.csv");
        Files.write(payload.toPath(), "abcdef".getBytes(StandardCharsets.UTF_8));

        EvidenceManifest manifest = new EvidenceManifest(
                EvidenceSchema.VERSION,
                "v2e_004",
                EvidenceManifest.ROLE_CONSUMER,
                true,
                Collections.singletonList(
                        new EvidenceFileEntry(
                                payload.getName(),
                                payload.length(),
                                EvidenceManifest.sha256(payload)
                        )
                )
        );

        Files.write(payload.toPath(), "a".getBytes(StandardCharsets.UTF_8));

        assertCode("EVIDENCE_SIZE_MISMATCH",
                () -> manifest.verifyFinalizedDirectory(root));
    }

    @Test
    public void missingFileIsRejected() throws Exception {
        File root = temporaryFolder.newFolder("session_v2e_005");
        EvidenceManifest manifest = new EvidenceManifest(
                EvidenceSchema.VERSION,
                "v2e_005",
                EvidenceManifest.ROLE_CONSUMER,
                true,
                Collections.singletonList(
                        new EvidenceFileEntry(
                                "gyro_summary.csv",
                                1L,
                                zeroHash()
                        )
                )
        );

        assertCode("EVIDENCE_FILE_MISSING",
                () -> manifest.verifyFinalizedDirectory(root));
    }

    @Test
    public void duplicateEntriesAreRejected() throws Exception {
        EvidenceFileEntry entry = new EvidenceFileEntry(
                "location_events.csv",
                0L,
                zeroHash()
        );

        assertCode("DUPLICATE_EVIDENCE_FILE",
                () -> new EvidenceManifest(
                        EvidenceSchema.VERSION,
                        "v2e_006",
                        EvidenceManifest.ROLE_CONSUMER,
                        true,
                        Arrays.asList(entry, entry)
                ));
    }

    private static String zeroHash() {
        StringBuilder out = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            out.append('0');
        }
        return out.toString();
    }

    private static void assertCode(String expected, ThrowingRunnable action) {
        try {
            action.run();
            fail("Expected EvidenceValidationException");
        } catch (EvidenceValidationException e) {
            assertEquals(expected, e.getCode());
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
