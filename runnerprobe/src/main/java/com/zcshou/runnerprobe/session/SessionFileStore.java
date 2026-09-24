package com.zcshou.runnerprobe.session;

import com.zcshou.runnerprobe.evidence.EvidenceFileEntry;
import com.zcshou.runnerprobe.evidence.EvidenceManifest;
import com.zcshou.runnerprobe.evidence.EvidenceSchema;
import com.zcshou.runnerprobe.evidence.EvidenceValidationException;
import com.zcshou.runnerprobe.evidence.SessionId;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SessionFileStore {
    public static final String LOCATION_FILE = "location_events.csv";
    public static final String DETECTOR_FILE = "step_detector_events.csv";
    public static final String COUNTER_FILE = "step_counter_events.csv";
    public static final String ACCEL_FILE = "accel_summary.csv";
    public static final String GYRO_FILE = "gyro_summary.csv";
    public static final String META_FILE = "runnerprobe_meta.json";
    public static final String MANIFEST_FILE = "evidence_manifest.json";

    private static final String LOCATION_HEADER =
            "session_id,provider,location_elapsed_ns,arrival_elapsed_ns,wall_time_ms,latitude,longitude,speed_mps,bearing_deg,accuracy_m,is_mock";
    private static final String DETECTOR_HEADER =
            "session_id,sensor_timestamp_ns,arrival_elapsed_ns,event_value";
    private static final String COUNTER_HEADER =
            "session_id,sensor_timestamp_ns,arrival_elapsed_ns,absolute_count,session_delta,discontinuity";
    private static final String SUMMARY_HEADER =
            "session_id,window_start_elapsed_ns,window_end_elapsed_ns,event_count,mean_magnitude,min_magnitude,max_magnitude";

    private final String sessionId;
    private final File sessionDir;
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<String> writeError = new AtomicReference<>();

    private final ManagedWriter locationWriter;
    private final ManagedWriter detectorWriter;
    private final ManagedWriter counterWriter;
    private final ManagedWriter accelWriter;
    private final ManagedWriter gyroWriter;

    private volatile CloseResult closeResult;

    public SessionFileStore(File rootDir, String rawSessionId)
            throws IOException, EvidenceValidationException {
        sessionId = SessionId.validate(rawSessionId);
        if (rootDir == null) {
            throw new IOException("Root directory is null");
        }
        sessionDir = new File(rootDir, "session_" + sessionId);
        if (sessionDir.exists()) {
            throw new IOException(
                    "SESSION_ID_ALREADY_EXISTS: " + sessionId
            );
        }
        if (!sessionDir.mkdirs()) {
            throw new IOException("Unable to create session directory: " + sessionDir);
        }

        locationWriter = openCsv(LOCATION_FILE, LOCATION_HEADER);
        detectorWriter = openCsv(DETECTOR_FILE, DETECTOR_HEADER);
        counterWriter = openCsv(COUNTER_FILE, COUNTER_HEADER);
        accelWriter = openCsv(ACCEL_FILE, SUMMARY_HEADER);
        gyroWriter = openCsv(GYRO_FILE, SUMMARY_HEADER);
    }

    public File getSessionDir() {
        return sessionDir;
    }

    public boolean appendLocation(
            String provider,
            long locationElapsedNs,
            long arrivalElapsedNs,
            long wallTimeMs,
            double latitude,
            double longitude,
            Double speedMps,
            Double bearingDeg,
            Double accuracyM,
            boolean isMock
    ) {
        return submit(locationWriter, csv(
                sessionId,
                safe(provider),
                locationElapsedNs,
                arrivalElapsedNs,
                wallTimeMs,
                latitude,
                longitude,
                speedMps,
                bearingDeg,
                accuracyM,
                isMock
        ));
    }

    public boolean appendStepDetector(
            long sensorTimestampNs,
            long arrivalElapsedNs,
            double eventValue
    ) {
        return submit(detectorWriter, csv(
                sessionId,
                sensorTimestampNs,
                arrivalElapsedNs,
                eventValue
        ));
    }

    public boolean appendStepCounter(
            long sensorTimestampNs,
            long arrivalElapsedNs,
            long absoluteCount,
            long sessionDelta,
            boolean discontinuity
    ) {
        return submit(counterWriter, csv(
                sessionId,
                sensorTimestampNs,
                arrivalElapsedNs,
                absoluteCount,
                sessionDelta,
                discontinuity
        ));
    }

    public boolean appendAccelSummary(SensorSummaryAccumulator.Summary s) {
        return appendSummary(accelWriter, s);
    }

    public boolean appendGyroSummary(SensorSummaryAccumulator.Summary s) {
        return appendSummary(gyroWriter, s);
    }

    private boolean appendSummary(ManagedWriter writer, SensorSummaryAccumulator.Summary s) {
        if (s == null) {
            return false;
        }
        return submit(writer, csv(
                sessionId,
                s.getWindowStartElapsedNs(),
                s.getWindowEndElapsedNs(),
                s.getEventCount(),
                s.getMeanMagnitude(),
                s.getMinMagnitude(),
                s.getMaxMagnitude()
        ));
    }

    public CloseResult close(SessionMetadata metadata) {
        CloseResult previous = closeResult;
        if (previous != null) {
            return previous;
        }
        synchronized (this) {
            if (closeResult != null) {
                return closeResult;
            }
            accepting.set(false);

            Future<CloseResult> future = writerExecutor.submit(() -> finalizeEvidence(metadata));
            writerExecutor.shutdown();

            try {
                closeResult = future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                closeResult = new CloseResult(false, "TRACE_CLOSE_TIMEOUT", sessionDir);
            } catch (Exception e) {
                closeResult = new CloseResult(false, "TRACE_CLOSE_FAILURE", sessionDir);
            } finally {
                writerExecutor.shutdownNow();
                closed.set(true);
            }
            return closeResult;
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    private CloseResult finalizeEvidence(SessionMetadata metadata) {
        List<String> extraErrors = new ArrayList<>();
        String pendingWriteError = writeError.get();
        if (pendingWriteError != null) {
            extraErrors.add(pendingWriteError);
        }

        try {
            locationWriter.finish();
            detectorWriter.finish();
            counterWriter.finish();
            accelWriter.finish();
            gyroWriter.finish();

            File metaPartial = partialFile(META_FILE);
            writeUtf8Sync(metaPartial, metadata.toJson(
                    pendingWriteError == null ? "SUCCESS" : pendingWriteError,
                    extraErrors
            ));

            List<String> payloadNames = Arrays.asList(
                    LOCATION_FILE,
                    DETECTOR_FILE,
                    COUNTER_FILE,
                    ACCEL_FILE,
                    GYRO_FILE,
                    META_FILE
            );

            for (String name : payloadNames) {
                File partial = partialFile(name);
                File target = new File(sessionDir, name);
                replaceByRename(partial, target);
            }

            List<EvidenceFileEntry> entries = new ArrayList<>();
            for (String name : payloadNames) {
                File file = new File(sessionDir, name);
                entries.add(new EvidenceFileEntry(
                        name,
                        file.length(),
                        EvidenceManifest.sha256(file)
                ));
            }

            File manifestPartial = partialFile(MANIFEST_FILE);
            writeUtf8Sync(manifestPartial, manifestJson(entries));
            replaceByRename(manifestPartial, new File(sessionDir, MANIFEST_FILE));

            if (pendingWriteError != null) {
                return new CloseResult(false, pendingWriteError, sessionDir);
            }
            return new CloseResult(true, null, sessionDir);
        } catch (Exception e) {
            closeQuietly(locationWriter);
            closeQuietly(detectorWriter);
            closeQuietly(counterWriter);
            closeQuietly(accelWriter);
            closeQuietly(gyroWriter);
            return new CloseResult(false, "TRACE_CLOSE_FAILURE", sessionDir);
        }
    }

    private ManagedWriter openCsv(String finalName, String header) throws IOException {
        ManagedWriter writer = new ManagedWriter(partialFile(finalName));
        writer.writeLine(header);
        return writer;
    }

    private boolean submit(ManagedWriter writer, String line) {
        if (!accepting.get()) {
            return false;
        }
        try {
            writerExecutor.execute(() -> {
                if (writeError.get() != null) {
                    return;
                }
                try {
                    writer.writeLine(line);
                } catch (IOException e) {
                    writeError.compareAndSet(null, "TRACE_WRITE_FAILURE");
                }
            });
            return true;
        } catch (RuntimeException e) {
            writeError.compareAndSet(null, "TRACE_WRITE_FAILURE");
            return false;
        }
    }

    private File partialFile(String finalName) {
        return new File(sessionDir, finalName + ".partial");
    }

    private String manifestJson(List<EvidenceFileEntry> entries) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"schema_version\": \"").append(EvidenceSchema.VERSION).append("\",\n");
        out.append("  \"session_id\": \"").append(sessionId).append("\",\n");
        out.append("  \"role\": \"consumer\",\n");
        out.append("  \"finalized\": true,\n");
        out.append("  \"files\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            EvidenceFileEntry entry = entries.get(i);
            out.append("    {\"name\": \"").append(entry.getName())
                    .append("\", \"bytes\": ").append(entry.getBytes())
                    .append(", \"sha256\": \"").append(entry.getSha256()).append("\"}");
            if (i + 1 < entries.size()) {
                out.append(",");
            }
            out.append("\n");
        }
        out.append("  ]\n");
        out.append("}\n");
        return out.toString();
    }

    private static void writeUtf8Sync(File file, String content) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(file, false);
             OutputStreamWriter output = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(output)) {
            writer.write(content);
            writer.flush();
            stream.getFD().sync();
        }
    }

    private static void replaceByRename(File source, File target) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("Unable to replace existing file: " + target);
        }
        if (!source.renameTo(target)) {
            throw new IOException("Unable to finalize file: " + source);
        }
    }

    private static void closeQuietly(ManagedWriter writer) {
        try {
            writer.closeOnly();
        } catch (Exception ignored) {
        }
    }

    private static String csv(Object... values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            Object value = values[i];
            if (value instanceof Double || value instanceof Float) {
                out.append(String.format(Locale.US, "%.9f", ((Number) value).doubleValue()));
            } else {
                out.append(safe(value));
            }
        }
        return out.toString();
    }

    private static String safe(Object value) {
        return value == null ? "" : value.toString().replace(',', '_').replace('\n', ' ');
    }

    private static final class ManagedWriter {
        private final FileOutputStream stream;
        private final BufferedWriter writer;
        private boolean finished;

        ManagedWriter(File file) throws IOException {
            stream = new FileOutputStream(file, false);
            writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
        }

        void writeLine(String line) throws IOException {
            if (finished) {
                throw new IOException("Writer already finished");
            }
            writer.write(line);
            writer.newLine();
        }

        void finish() throws IOException {
            if (finished) {
                return;
            }
            writer.flush();
            stream.getFD().sync();
            writer.close();
            finished = true;
        }

        void closeOnly() throws IOException {
            if (!finished) {
                writer.close();
                finished = true;
            }
        }
    }

    public static final class CloseResult {
        private final boolean success;
        private final String errorCode;
        private final File sessionDir;

        CloseResult(boolean success, String errorCode, File sessionDir) {
            this.success = success;
            this.errorCode = errorCode;
            this.sessionDir = sessionDir;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public File getSessionDir() {
            return sessionDir;
        }
    }
}
