package com.zcshou.motion;

import com.zcshou.route.RouteSample;
import com.zcshou.route.RouteSessionState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SyntheticMotionCoordinatorTest {
    @Rule
    public TemporaryFolder temporaryFolder =
            new TemporaryFolder();

    @Test
    public void playingSamplesProduceSyntheticEvidence()
            throws Exception {
        File root = temporaryFolder.newFolder("producer");
        SyntheticMotionCoordinator coordinator =
                new SyntheticMotionCoordinator();
        HumanMotionConfig config =
                HumanMotionConfig.defaultConfig(101L);

        coordinator.start(
                7L,
                "v2e_coord_001",
                root,
                config
        );

        coordinator.onRouteSample(
                sample(7L, RouteSessionState.PLAYING, 0L, 4.0)
        );
        coordinator.onRouteSample(
                sample(
                        7L,
                        RouteSessionState.PLAYING,
                        3_000_000_000L,
                        4.0
                )
        );

        assertTrue(
                coordinator.snapshot().getSyntheticStepCount() > 0L
        );

        ProducerEvidenceRecorder.CloseResult result =
                coordinator.finish(
                        metadata(
                                "v2e_coord_001",
                                7L,
                                config
                        )
                );

        assertTrue(result.isSuccess());

        List<String> rows = Files.readAllLines(
                new File(
                        result.getSessionDir(),
                        ProducerEvidenceRecorder.SYNTHETIC_FILE
                ).toPath(),
                StandardCharsets.UTF_8
        );
        assertTrue(rows.size() > 1);
    }

    @Test
    public void staleRouteSampleIsIgnored()
            throws Exception {
        File root = temporaryFolder.newFolder("stale");
        SyntheticMotionCoordinator coordinator =
                new SyntheticMotionCoordinator();
        HumanMotionConfig config =
                HumanMotionConfig.defaultConfig(102L);

        coordinator.start(
                8L,
                "v2e_coord_002",
                root,
                config
        );
        coordinator.onRouteSample(
                sample(7L, RouteSessionState.PLAYING, 0L, 4.0)
        );
        coordinator.onRouteSample(
                sample(
                        7L,
                        RouteSessionState.PLAYING,
                        5_000_000_000L,
                        4.0
                )
        );

        assertEquals(
                0L,
                coordinator.snapshot().getSyntheticStepCount()
        );

        coordinator.finish(
                metadata(
                        "v2e_coord_002",
                        8L,
                        config
                )
        );
    }

    @Test
    public void pauseResumeDoesNotCatchUp()
            throws Exception {
        File root = temporaryFolder.newFolder("pause");
        SyntheticMotionCoordinator coordinator =
                new SyntheticMotionCoordinator();
        HumanMotionConfig config =
                HumanMotionConfig.defaultConfig(103L);

        coordinator.start(
                9L,
                "v2e_coord_003",
                root,
                config
        );
        coordinator.onRouteSample(
                sample(9L, RouteSessionState.PLAYING, 0L, 4.0)
        );
        coordinator.onRouteSample(
                sample(
                        9L,
                        RouteSessionState.PLAYING,
                        2_000_000_000L,
                        4.0
                )
        );

        long before =
                coordinator.snapshot().getSyntheticStepCount();

        coordinator.onRouteSample(
                sample(
                        9L,
                        RouteSessionState.PAUSED,
                        2_000_000_000L,
                        0.0
                )
        );
        coordinator.onRouteSample(
                sample(
                        9L,
                        RouteSessionState.PAUSED,
                        12_000_000_000L,
                        0.0
                )
        );
        coordinator.onRouteSample(
                sample(
                        9L,
                        RouteSessionState.PLAYING,
                        12_000_000_001L,
                        4.0
                )
        );

        assertEquals(
                before,
                coordinator.snapshot().getSyntheticStepCount()
        );

        coordinator.finish(
                metadata(
                        "v2e_coord_003",
                        9L,
                        config
                )
        );
    }

    @Test
    public void publicationTraceIsRecordedIndependently()
            throws Exception {
        File root = temporaryFolder.newFolder("location");
        SyntheticMotionCoordinator coordinator =
                new SyntheticMotionCoordinator();
        HumanMotionConfig config =
                HumanMotionConfig.defaultConfig(104L);

        coordinator.start(
                10L,
                "v2e_coord_004",
                root,
                config
        );

        coordinator.onLocationPublished(
                "gps",
                200L,
                190L,
                34.0,
                108.0,
                3.0,
                90.0,
                5.0
        );

        ProducerEvidenceRecorder.CloseResult result =
                coordinator.finish(
                        metadata(
                                "v2e_coord_004",
                                10L,
                                config
                        )
                );

        assertNotNull(result);
        assertTrue(result.isSuccess());
        List<String> rows = Files.readAllLines(
                new File(
                        result.getSessionDir(),
                        ProducerEvidenceRecorder.LOCATION_FILE
                ).toPath(),
                StandardCharsets.UTF_8
        );
        assertEquals(2, rows.size());
        assertTrue(rows.get(1).contains(",gps,"));
    }

    @Test(expected = IllegalStateException.class)
    public void refusesOverlappingEvidenceSessions()
            throws Exception {
        File root = temporaryFolder.newFolder("overlap");
        SyntheticMotionCoordinator coordinator =
                new SyntheticMotionCoordinator();

        coordinator.start(
                11L,
                "v2e_coord_005",
                root,
                HumanMotionConfig.defaultConfig(105L)
        );
        coordinator.start(
                12L,
                "v2e_coord_006",
                root,
                HumanMotionConfig.defaultConfig(106L)
        );
    }

    private static RouteSample sample(
            long sessionId,
            RouteSessionState state,
            long elapsedNs,
            double speedMps
    ) {
        return new RouteSample.Builder(sessionId)
                .state(state)
                .latitudeWgs84(34.0)
                .longitudeWgs84(108.0)
                .altitudeMeters(55.0)
                .targetSpeedMps(speedMps)
                .outputSpeedMps(speedMps)
                .bearingDeg(90.0)
                .elapsedRealtimeNanos(elapsedNs)
                .timestampMs(1L)
                .build();
    }

    private static ProducerSessionMetadata metadata(
            String id,
            long routeSessionId,
            HumanMotionConfig config
    ) {
        return new ProducerSessionMetadata.Builder(
                id,
                routeSessionId,
                config
        )
                .elapsedRange(0L, 20_000_000_000L)
                .build();
    }
}
