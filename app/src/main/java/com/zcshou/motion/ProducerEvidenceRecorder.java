package com.zcshou.motion;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ProducerEvidenceRecorder {
    public static final String LOCATION_FILE = "producer_location.csv";
    public static final String SYNTHETIC_FILE = "synthetic_motion.csv";
    public static final String META_FILE = "producer_meta.json";
    public static final String MANIFEST_FILE = "evidence_manifest.json";

    private static final String LOCATION_HEADER =
            "session_id,provider,publication_elapsed_ns,location_elapsed_ns,latitude,longitude,speed_mps,bearing_deg,accuracy_m";
    private static final String SYNTHETIC_HEADER =
            "session_id,step_index,step_elapsed_ns,speed_mps,target_cadence_spm,instantaneous_cadence_spm,interval_ns";

    private final String sessionId;
    private final File sessionDir;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicReference<String> writeError = new AtomicReference<>();

    private final ManagedWriter locationWriter;
    private final ManagedWriter syntheticWriter;

    private volatile CloseResult closeResult;

    public ProducerEvidenceRecorder(File root, String rawSessionId)
            throws IOException {
        sessionId = MotionSessionId.validate(rawSessionId);
        if (root == null) {
            throw new IOException("Producer evidence root is null");
        }

        sessionDir = new File(root, "session_" + sessionId);
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            throw new IOException(
                    "Unable to create producer session directory"
            );
        }
        if (!sessionDir.isDirectory()) {
            throw new IOException("Producer session path is not a directory");
        }

        locationWriter = openCsv(LOCATION_FILE, LOCATION_HEADER);
        syntheticWriter = openCsv(SYNTHETIC_FILE, SYNTHETIC_HEADER);
    }

    public File getSessionDir() {
        return sessionDir;
    }

    public boolean appendLocation(
            String provider,
            long publicationElapsedNs,
            long locationElapsedNs,
            double latitude,
            double longitude,
            double speedMps,
            double bearingDeg,
            double accuracyM
    ) {
        return submit(locationWriter, csv(
                sessionId,
                safe(provider),
                publicationElapsedNs,
                locationElapsedNs,
                latitude,
                longitude,
                speedMps,
                bearingDeg,
                accuracyM
        ));
    }

    public boolean appendSyntheticStep(SyntheticStepEvent event) {
        if (event == null) {
            return false;
        }
        return submit(syntheticWriter, csv(
                sessionId,
                event.getStepIndex(),
                event.getElapsedRealtimeNs(),
                event.getSpeedMps(),
                event.getTargetCadenceSpm(),
                event.getInstantaneousCadenceSpm(),
                event.getIntervalNs()
        ));
    }

    public CloseResult close(ProducerSessionMetadata metadata) {
        CloseResult previous = closeResult;
        if (previous != null) {
            return previous;
        }

        synchronized (this) {
            if (closeResult != null) {
                return closeResult;
            }
            accepting.set(false);

            Future<CloseResult> future =
                    executor.submit(() -> finalizeEvidence(metadata));
            executor.shutdown();

            try {
                closeResult = future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                closeResult = new CloseResult(
                        false,
                        "TRACE_CLOSE_TIMEOUT",
                        sessionDir
                );
            } catch (Exception e) {
                closeResult = new CloseResult(
                        false,
                        "TRACE_CLOSE_FAILURE",
                        sessionDir
                );
            } finally {
                executor.shutdownNow();
            }
            return closeResult;
        }
    }

    private CloseResult finalizeEvidence(
            ProducerSessionMetadata metadata
    ) {
        String pendingError = writeError.get();
        List<String> extraErrors = new ArrayList<>();
        if (pendingError != null) {
            extraErrors.add(pendingError);
        }

        try {
            if (metadata == null) {
                throw new IOException("Producer metadata is required");
            }

            locationWriter.finish();
            syntheticWriter.finish();

            File metaPartial = partialFile(META_FILE);
            writeUtf8Sync(
                    metaPartial,
                    metadata.toJson(
                            pendingError == null ? "SUCCESS" : pendingError,
                            extraErrors
                    )
            );

            List<String> payloadNames = Arrays.asList(
                    LOCATION_FILE,
                    SYNTHETIC_FILE,
                    META_FILE
            );

            for (String name : payloadNames) {
                replaceByRename(
                        partialFile(name),
                        new File(sessionDir, name)
                );
            }

            List<FileEntry> entries = new ArrayList<>();
            for (String name : payloadNames) {
                File file = new File(sessionDir, name);
                entries.add(new FileEntry(
                        name,
                        file.length(),
                        sha256(file)
                ));
            }

            File manifestPartial = partialFile(MANIFEST_FILE);
            writeUtf8Sync(
                    manifestPartial,
                    manifestJson(entries)
            );
            replaceByRename(
                    manifestPartial,
                    new File(sessionDir, MANIFEST_FILE)
            );

            if (pendingError != null) {
                return new CloseResult(
                        false,
                        pendingError,
                        sessionDir
                );
            }

            return new CloseResult(true, null, sessionDir);
        } catch (Exception e) {
            closeQuietly(locationWriter);
            closeQuietly(syntheticWriter);
            return new CloseResult(
                    false,
                    "TRACE_CLOSE_FAILURE",
                    sessionDir
            );
        }
    }

    private ManagedWriter openCsv(String finalName, String header)
            throws IOException {
        ManagedWriter writer =
                new ManagedWriter(partialFile(finalName));
        writer.writeLine(header);
        return writer;
    }

    private boolean submit(ManagedWriter writer, String line) {
        if (!accepting.get()) {
            return false;
        }

        try {
            executor.execute(() -> {
                if (writeError.get() != null) {
                    return;
                }
                try {
                    writer.writeLine(line);
                } catch (IOException e) {
                    writeError.compareAndSet(
                            null,
                            "TRACE_WRITE_FAILURE"
                    );
                }
            });
            return true;
        } catch (RuntimeException e) {
            writeError.compareAndSet(
                    null,
                    "TRACE_WRITE_FAILURE"
            );
            return false;
        }
    }

    private File partialFile(String finalName) {
        return new File(
                sessionDir,
                finalName + ".partial"
        );
    }

    private String manifestJson(List<FileEntry> entries) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"schema_version\": \"v2e-1\",\n");
        out.append("  \"session_id\": \"")
                .append(sessionId)
                .append("\",\n");
        out.append("  \"role\": \"producer\",\n");
        out.append("  \"finalized\": true,\n");
        out.append("  \"files\": [\n");

        for (int i = 0; i < entries.size(); i++) {
            FileEntry entry = entries.get(i);
            out.append("    {\"name\": \"")
                    .append(entry.name)
                    .append("\", \"bytes\": ")
                    .append(entry.bytes)
                    .append(", \"sha256\": \"")
                    .append(entry.sha256)
                    .append("\"}");
            if (i + 1 < entries.size()) {
                out.append(",");
            }
            out.append("\n");
        }

        out.append("  ]\n");
        out.append("}\n");
        return out.toString();
    }

    private static String csv(Object... values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            Object value = values[i];
            if (value instanceof Double || value instanceof Float) {
                out.append(String.format(
                        Locale.US,
                        "%.12g",
                        ((Number) value).doubleValue()
                ));
            } else {
                out.append(safe(value));
            }
        }
        return out.toString();
    }

    private static String safe(Object value) {
        return value == null
                ? ""
                : value.toString()
                        .replace(',', '_')
                        .replace('\n', ' ')
                        .replace('\r', ' ');
    }

    private static void writeUtf8Sync(
            File file,
            String content
    ) throws IOException {
        try (FileOutputStream stream =
                     new FileOutputStream(file, false);
             OutputStreamWriter output =
                     new OutputStreamWriter(
                             stream,
                             StandardCharsets.UTF_8
                     );
             BufferedWriter writer =
                     new BufferedWriter(output)) {
            writer.write(content);
            writer.flush();
            stream.getFD().sync();
        }
    }

    private static void replaceByRename(
            File source,
            File target
    ) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException(
                    "Unable to replace existing producer file"
            );
        }
        if (!source.renameTo(target)) {
            throw new IOException(
                    "Unable to finalize producer file"
            );
        }
    }

    private static String sha256(File file)
            throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }

        try (FileInputStream in =
                     new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        byte[] bytes = digest.digest();
        StringBuilder out =
                new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format(
                    Locale.US,
                    "%02x",
                    value & 0xff
            ));
        }
        return out.toString();
    }

    private static void closeQuietly(ManagedWriter writer) {
        try {
            writer.closeOnly();
        } catch (Exception ignored) {
        }
    }

    private static final class ManagedWriter {
        private final FileOutputStream stream;
        private final BufferedWriter writer;
        private boolean finished;

        ManagedWriter(File file) throws IOException {
            stream = new FileOutputStream(file, false);
            writer = new BufferedWriter(
                    new OutputStreamWriter(
                            stream,
                            StandardCharsets.UTF_8
                    )
            );
        }

        void writeLine(String line) throws IOException {
            if (finished) {
                throw new IOException("Writer finalized");
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

    private static final class FileEntry {
        final String name;
        final long bytes;
        final String sha256;

        FileEntry(String name, long bytes, String sha256) {
            this.name = name;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    public static final class CloseResult {
        private final boolean success;
        private final String errorCode;
        private final File sessionDir;

        CloseResult(
                boolean success,
                String errorCode,
                File sessionDir
        ) {
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
