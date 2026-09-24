package com.zcshou.runnerprobe.session;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SessionExporterTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void exportsFinalizedSessionInDeterministicOrder() throws Exception {
        String id = "v2e_export_001";
        File root = temporaryFolder.newFolder("consumer");
        File session = new File(root, "session_" + id);
        assertTrue(session.mkdirs());

        for (String name : Arrays.asList(
                SessionFileStore.LOCATION_FILE,
                SessionFileStore.DETECTOR_FILE,
                SessionFileStore.COUNTER_FILE,
                SessionFileStore.ACCEL_FILE,
                SessionFileStore.GYRO_FILE,
                SessionFileStore.META_FILE
        )) {
            Files.write(
                    new File(session, name).toPath(),
                    (name + "\n").getBytes(StandardCharsets.UTF_8)
            );
        }
        Files.write(
                new File(session, SessionFileStore.MANIFEST_FILE).toPath(),
                ("{\n"
                        + "  \"schema_version\": \"v2e-1\",\n"
                        + "  \"session_id\": \"" + id + "\",\n"
                        + "  \"role\": \"consumer\",\n"
                        + "  \"finalized\": true,\n"
                        + "  \"files\": []\n"
                        + "}\n").getBytes(StandardCharsets.UTF_8)
        );

        File out = temporaryFolder.newFolder("exports");
        File zip = SessionExporter.exportSession(session, out, id);

        assertTrue(zip.isFile());

        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(
                Files.newInputStream(zip.toPath()))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }

        List<String> expectedFiles = new ArrayList<>(Arrays.asList(
                SessionFileStore.LOCATION_FILE,
                SessionFileStore.DETECTOR_FILE,
                SessionFileStore.COUNTER_FILE,
                SessionFileStore.ACCEL_FILE,
                SessionFileStore.GYRO_FILE,
                SessionFileStore.META_FILE,
                SessionFileStore.MANIFEST_FILE
        ));
        Collections.sort(expectedFiles);

        List<String> expected = new ArrayList<>();
        for (String name : expectedFiles) {
            expected.add("session_" + id + "/" + name);
        }
        assertEquals(expected, names);
    }

    @Test
    public void rejectsSessionWithoutFinalManifest() throws Exception {
        String id = "v2e_export_002";
        File root = temporaryFolder.newFolder("partial");
        File session = new File(root, "session_" + id);
        assertTrue(session.mkdirs());

        try {
            SessionExporter.exportSession(
                    session,
                    temporaryFolder.newFolder("out"),
                    id
            );
            fail("Expected IOException");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage().contains("SESSION_NOT_FINALIZED"));
        }
    }
}
