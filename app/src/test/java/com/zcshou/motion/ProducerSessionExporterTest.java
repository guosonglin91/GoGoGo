package com.zcshou.motion;

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

public class ProducerSessionExporterTest {
    @Rule
    public TemporaryFolder temporaryFolder =
            new TemporaryFolder();

    @Test
    public void exportsFinalizedProducerSession() throws Exception {
        String id = "v2e_export_producer";
        File root = temporaryFolder.newFolder("producer");
        ProducerEvidenceRecorder recorder =
                new ProducerEvidenceRecorder(root, id);
        ProducerSessionMetadata metadata =
                new ProducerSessionMetadata.Builder(
                        id,
                        1L,
                        HumanMotionConfig.defaultConfig(1L)
                )
                        .elapsedRange(1L, 2L)
                        .build();
        File sessionDir = recorder.close(metadata).getSessionDir();

        File zip = ProducerSessionExporter.exportSession(
                sessionDir,
                temporaryFolder.newFolder("exports"),
                id
        );
        assertTrue(zip.isFile());

        List<String> entries = new ArrayList<>();
        try (ZipInputStream input = new ZipInputStream(
                Files.newInputStream(zip.toPath()))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }

        List<String> expectedNames = new ArrayList<>(Arrays.asList(
                ProducerEvidenceRecorder.LOCATION_FILE,
                ProducerEvidenceRecorder.SYNTHETIC_FILE,
                ProducerEvidenceRecorder.META_FILE,
                ProducerEvidenceRecorder.MANIFEST_FILE
        ));
        Collections.sort(expectedNames);

        List<String> expected = new ArrayList<>();
        for (String name : expectedNames) {
            expected.add("session_" + id + "/" + name);
        }
        assertEquals(expected, entries);
    }

    @Test
    public void rejectsIncompleteProducerSession() throws Exception {
        String id = "v2e_export_incomplete";
        File session = temporaryFolder.newFolder(
                "session_" + id
        );
        Files.write(
                new File(
                        session,
                        ProducerEvidenceRecorder.LOCATION_FILE
                ).toPath(),
                "header\n".getBytes(StandardCharsets.UTF_8)
        );

        try {
            ProducerSessionExporter.exportSession(
                    session,
                    temporaryFolder.newFolder("out"),
                    id
            );
            fail("Expected IOException");
        } catch (java.io.IOException e) {
            assertTrue(
                    e.getMessage().contains("SESSION_NOT_FINALIZED")
            );
        }
    }
}
