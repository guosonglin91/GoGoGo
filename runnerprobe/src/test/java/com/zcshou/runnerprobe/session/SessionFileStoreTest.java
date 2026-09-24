package com.zcshou.runnerprobe.session;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionFileStoreTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void closeFinalizesRequiredFilesAndManifestLast() throws Exception {
        File root = temporaryFolder.newFolder("consumer");
        SessionFileStore store = new SessionFileStore(root, "v2e_store_001");

        store.appendLocation("gps", 10L, 11L, 12L,
                34.0, 108.0, 3.0, 90.0, 5.0, true);
        store.appendStepDetector(20L, 21L, 1.0);
        store.appendStepCounter(30L, 31L, 100L, 0L, false);

        SessionMetadata metadata = new SessionMetadata.Builder("v2e_store_001")
                .elapsedRange(1L, 100L)
                .appVersion("0.1.0")
                .device("test", "test", 32)
                .permissionState("GRANTED")
                .build();

        SessionFileStore.CloseResult result = store.close(metadata);

        assertTrue(result.isSuccess());
        assertTrue(store.isClosed());
        assertTrue(new File(result.getSessionDir(), SessionFileStore.LOCATION_FILE).isFile());
        assertTrue(new File(result.getSessionDir(), SessionFileStore.DETECTOR_FILE).isFile());
        assertTrue(new File(result.getSessionDir(), SessionFileStore.COUNTER_FILE).isFile());
        assertTrue(new File(result.getSessionDir(), SessionFileStore.ACCEL_FILE).isFile());
        assertTrue(new File(result.getSessionDir(), SessionFileStore.GYRO_FILE).isFile());
        assertTrue(new File(result.getSessionDir(), SessionFileStore.META_FILE).isFile());
        assertTrue(new File(result.getSessionDir(), SessionFileStore.MANIFEST_FILE).isFile());

        assertFalse(new File(
                result.getSessionDir(),
                SessionFileStore.LOCATION_FILE + ".partial"
        ).exists());
    }

    @Test
    public void headersAreExact() throws Exception {
        File root = temporaryFolder.newFolder("headers");
        SessionFileStore store = new SessionFileStore(root, "v2e_store_002");

        SessionMetadata metadata = new SessionMetadata.Builder("v2e_store_002")
                .elapsedRange(1L, 2L)
                .build();
        File dir = store.close(metadata).getSessionDir();

        assertEquals(
                "session_id,provider,location_elapsed_ns,arrival_elapsed_ns,wall_time_ms,latitude,longitude,speed_mps,bearing_deg,accuracy_m,is_mock",
                firstLine(new File(dir, SessionFileStore.LOCATION_FILE))
        );
        assertEquals(
                "session_id,sensor_timestamp_ns,arrival_elapsed_ns,event_value",
                firstLine(new File(dir, SessionFileStore.DETECTOR_FILE))
        );
        assertEquals(
                "session_id,sensor_timestamp_ns,arrival_elapsed_ns,absolute_count,session_delta,discontinuity",
                firstLine(new File(dir, SessionFileStore.COUNTER_FILE))
        );
    }

    @Test
    public void writeAfterCloseIsRejectedAndDuplicateCloseIsIdempotent() throws Exception {
        File root = temporaryFolder.newFolder("closed");
        SessionFileStore store = new SessionFileStore(root, "v2e_store_003");
        SessionMetadata metadata = new SessionMetadata.Builder("v2e_store_003")
                .elapsedRange(1L, 2L)
                .build();

        SessionFileStore.CloseResult first = store.close(metadata);
        SessionFileStore.CloseResult second = store.close(metadata);

        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertEquals(first.getSessionDir(), second.getSessionDir());
        assertFalse(store.appendStepDetector(10L, 11L, 1.0));
    }

    @Test(expected = java.io.IOException.class)
    public void refusesToReuseExistingSessionDirectory() throws Exception {
        File root = temporaryFolder.newFolder("reuse");
        File existing = new File(root, "session_v2e_store_reused");
        assertTrue(existing.mkdirs());

        new SessionFileStore(root, "v2e_store_reused");
    }

    @Test
    public void manifestContainsSchemaSessionAndHashes() throws Exception {
        File root = temporaryFolder.newFolder("manifest");
        SessionFileStore store = new SessionFileStore(root, "v2e_store_004");
        SessionMetadata metadata = new SessionMetadata.Builder("v2e_store_004")
                .elapsedRange(1L, 2L)
                .build();

        File dir = store.close(metadata).getSessionDir();
        List<String> lines = Files.readAllLines(
                new File(dir, SessionFileStore.MANIFEST_FILE).toPath(),
                StandardCharsets.UTF_8
        );
        String joined = String.join("\n", lines);

        assertTrue(joined.contains("\"schema_version\": \"v2e-1\""));
        assertTrue(joined.contains("\"session_id\": \"v2e_store_004\""));
        assertTrue(joined.contains("\"finalized\": true"));
        assertTrue(joined.contains("\"sha256\""));
    }

    private static String firstLine(File file) throws Exception {
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).get(0);
    }
}
