package com.zcshou.runnerprobe.session;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class SessionMetadataSensorDescriptorTest {
    @Test
    public void metadataContainsSensorDescriptorFields() throws Exception {
        SessionMetadata metadata = new SessionMetadata.Builder("v2e_meta_sensor")
                .elapsedRange(1L, 2L)
                .detector("Detector", "VendorD", 3, true, 1)
                .counter("Counter", "VendorC", 4, false, 2)
                .build();

        String json = metadata.toJson("SUCCESS", null);
        assertTrue(json.contains("\"detector_version\": 3"));
        assertTrue(json.contains("\"detector_wake_up\": true"));
        assertTrue(json.contains("\"detector_reporting_mode\": 1"));
        assertTrue(json.contains("\"counter_version\": 4"));
        assertTrue(json.contains("\"counter_wake_up\": false"));
        assertTrue(json.contains("\"counter_reporting_mode\": 2"));
    }
}
