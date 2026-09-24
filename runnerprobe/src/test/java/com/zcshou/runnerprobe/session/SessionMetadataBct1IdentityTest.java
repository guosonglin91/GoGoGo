package com.zcshou.runnerprobe.session;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class SessionMetadataBct1IdentityTest {
    @Test
    public void metadataContainsPublicSensorIdentityFields() throws Exception {
        SessionMetadata metadata = new SessionMetadata.Builder("v2e_bct1_sensor")
                .elapsedRange(1L, 2L)
                .detectorIdentity(
                        "V2F Virtual Step Detector",
                        "V2F Lab",
                        101,
                        18,
                        "android.sensor.step_detector",
                        1,
                        false,
                        3)
                .counterIdentity(
                        "V2F Virtual Step Counter",
                        "V2F Lab",
                        102,
                        19,
                        "android.sensor.step_counter",
                        1,
                        false,
                        1)
                .build();

        String json = metadata.toJson("SUCCESS", null);
        assertTrue(json.contains("\"detector_id\": 101"));
        assertTrue(json.contains("\"detector_type\": 18"));
        assertTrue(json.contains(
                "\"detector_string_type\": \"android.sensor.step_detector\""));
        assertTrue(json.contains("\"counter_id\": 102"));
        assertTrue(json.contains("\"counter_type\": 19"));
        assertTrue(json.contains(
                "\"counter_string_type\": \"android.sensor.step_counter\""));
    }
}
