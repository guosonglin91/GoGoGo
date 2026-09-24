package com.zcshou.motion;

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

public class ProducerEvidenceRecorderTest {
    @Rule
    public TemporaryFolder temporaryFolder =
            new TemporaryFolder();

    @Test
    public void finalizesProducerPayloadsAndManifest()
            throws Exception {
        File root = temporaryFolder.newFolder("producer");
        HumanMotionConfig config =
                HumanMotionConfig.defaultConfig(123L);
        ProducerEvidenceRecorder recorder =
                new ProducerEvidenceRecorder(
                        root,
                        "v2e_producer_001"
                );

        assertTrue(recorder.appendLocation(
                "gps",
                1_100L,
                1_000L,
                34.0,
                108.0,
                3.0,
                90.0,
                5.0
        ));
        assertTrue(recorder.appendSyntheticStep(
                new SyntheticStepEvent(
                        1L,
                        2_000L,
                        3.0,
                        145.0,
                        144.0,
                        416_666_667L
                )
        ));

        ProducerSessionMetadata metadata =
                new ProducerSessionMetadata.Builder(
                        "v2e_producer_001",
                        7L,
                        config
                )
                        .elapsedRange(1L, 3_000L)
                        .appVersion("1.12.3")
                        .sourceCommitSha("abc")
                        .device("test", "test", 32)
                        .bootMarker("boot")
                        .build();

        ProducerEvidenceRecorder.CloseResult result =
                recorder.close(metadata);

        assertTrue(result.isSuccess());
        assertTrue(new File(
                result.getSessionDir(),
                ProducerEvidenceRecorder.LOCATION_FILE
        ).isFile());
        assertTrue(new File(
                result.getSessionDir(),
                ProducerEvidenceRecorder.SYNTHETIC_FILE
        ).isFile());
        assertTrue(new File(
                result.getSessionDir(),
                ProducerEvidenceRecorder.META_FILE
        ).isFile());
        assertTrue(new File(
                result.getSessionDir(),
                ProducerEvidenceRecorder.MANIFEST_FILE
        ).isFile());
    }

    @Test
    public void headersAreFrozen() throws Exception {
        File root = temporaryFolder.newFolder("headers");
        ProducerEvidenceRecorder recorder =
                new ProducerEvidenceRecorder(
                        root,
                        "v2e_producer_002"
                );

        ProducerSessionMetadata metadata =
                new ProducerSessionMetadata.Builder(
                        "v2e_producer_002",
                        8L,
                        HumanMotionConfig.defaultConfig(8L)
                )
                        .elapsedRange(1L, 2L)
                        .build();

        File dir = recorder.close(metadata).getSessionDir();

        assertEquals(
                "session_id,provider,publication_elapsed_ns,location_elapsed_ns,latitude,longitude,speed_mps,bearing_deg,accuracy_m",
                firstLine(new File(
                        dir,
                        ProducerEvidenceRecorder.LOCATION_FILE
                ))
        );
        assertEquals(
                "session_id,step_index,step_elapsed_ns,speed_mps,target_cadence_spm,instantaneous_cadence_spm,interval_ns",
                firstLine(new File(
                        dir,
                        ProducerEvidenceRecorder.SYNTHETIC_FILE
                ))
        );
    }

    @Test
    public void writeAfterCloseRejectedAndCloseIdempotent()
            throws Exception {
        File root = temporaryFolder.newFolder("closed");
        ProducerEvidenceRecorder recorder =
                new ProducerEvidenceRecorder(
                        root,
                        "v2e_producer_003"
                );

        ProducerSessionMetadata metadata =
                new ProducerSessionMetadata.Builder(
                        "v2e_producer_003",
                        9L,
                        HumanMotionConfig.defaultConfig(9L)
                )
                        .elapsedRange(1L, 2L)
                        .build();

        ProducerEvidenceRecorder.CloseResult first =
                recorder.close(metadata);
        ProducerEvidenceRecorder.CloseResult second =
                recorder.close(metadata);

        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertEquals(
                first.getSessionDir(),
                second.getSessionDir()
        );
        assertFalse(recorder.appendSyntheticStep(
                new SyntheticStepEvent(
                        1L,
                        3L,
                        3.0,
                        145.0,
                        145.0,
                        400_000_000L
                )
        ));
    }

    @Test
    public void twoSessionsRemainIsolated() throws Exception {
        File root = temporaryFolder.newFolder("isolated");

        ProducerEvidenceRecorder a =
                new ProducerEvidenceRecorder(
                        root,
                        "v2e_a"
                );
        ProducerEvidenceRecorder b =
                new ProducerEvidenceRecorder(
                        root,
                        "v2e_b"
                );

        File aDir = a.close(
                metadata("v2e_a", 1L)
        ).getSessionDir();
        File bDir = b.close(
                metadata("v2e_b", 2L)
        ).getSessionDir();

        assertFalse(aDir.equals(bDir));
        assertTrue(aDir.isDirectory());
        assertTrue(bDir.isDirectory());
    }

    @Test(expected = java.io.IOException.class)
    public void refusesToReuseExistingSessionDirectory()
            throws Exception {
        File root = temporaryFolder.newFolder("reuse");
        File existing =
                new File(root, "session_v2e_producer_reused");
        assertTrue(existing.mkdirs());

        new ProducerEvidenceRecorder(
                root,
                "v2e_producer_reused"
        );
    }

    @Test
    public void manifestContainsHashesAndProducerRole()
            throws Exception {
        File root = temporaryFolder.newFolder("manifest");
        ProducerEvidenceRecorder recorder =
                new ProducerEvidenceRecorder(
                        root,
                        "v2e_producer_004"
                );
        File dir = recorder.close(
                metadata("v2e_producer_004", 4L)
        ).getSessionDir();

        List<String> lines = Files.readAllLines(
                new File(
                        dir,
                        ProducerEvidenceRecorder.MANIFEST_FILE
                ).toPath(),
                StandardCharsets.UTF_8
        );
        String joined = String.join("\n", lines);

        assertTrue(joined.contains(
                "\"schema_version\": \"v2e-1\""
        ));
        assertTrue(joined.contains(
                "\"role\": \"producer\""
        ));
        assertTrue(joined.contains(
                "\"finalized\": true"
        ));
        assertTrue(joined.contains("\"sha256\""));
    }

    private static ProducerSessionMetadata metadata(
            String id,
            long routeSessionId
    ) {
        return new ProducerSessionMetadata.Builder(
                id,
                routeSessionId,
                HumanMotionConfig.defaultConfig(routeSessionId)
        )
                .elapsedRange(1L, 2L)
                .build();
    }

    private static String firstLine(File file)
            throws Exception {
        return Files.readAllLines(
                file.toPath(),
                StandardCharsets.UTF_8
        ).get(0);
    }
}
