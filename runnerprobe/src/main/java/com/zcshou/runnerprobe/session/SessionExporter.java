package com.zcshou.runnerprobe.session;

import com.zcshou.runnerprobe.evidence.EvidenceValidationException;
import com.zcshou.runnerprobe.evidence.SessionId;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SessionExporter {
    private static final String[] REQUIRED = {
            SessionFileStore.LOCATION_FILE,
            SessionFileStore.DETECTOR_FILE,
            SessionFileStore.COUNTER_FILE,
            SessionFileStore.ACCEL_FILE,
            SessionFileStore.GYRO_FILE,
            SessionFileStore.META_FILE,
            SessionFileStore.MANIFEST_FILE
    };

    private SessionExporter() {
    }

    public static File exportSession(
            File sessionDir,
            File outputDir,
            String rawSessionId
    ) throws IOException, EvidenceValidationException {
        String sessionId = SessionId.validate(rawSessionId);

        if (sessionDir == null || !sessionDir.isDirectory()) {
            throw new IOException("Session directory is missing");
        }

        File manifest = new File(sessionDir, SessionFileStore.MANIFEST_FILE);
        if (!manifest.isFile()) {
            throw new IOException("SESSION_NOT_FINALIZED");
        }

        String manifestText = new String(
                Files.readAllBytes(manifest.toPath()),
                StandardCharsets.UTF_8
        );
        if (!manifestText.contains("\"finalized\": true")
                || !manifestText.contains(
                "\"session_id\": \"" + sessionId + "\"")) {
            throw new IOException("SESSION_NOT_FINALIZED");
        }

        List<File> files = new ArrayList<>();
        for (String name : REQUIRED) {
            File file = new File(sessionDir, name);
            if (!file.isFile() || !file.canRead()) {
                throw new IOException("Missing finalized evidence file: " + name);
            }
            files.add(file);
        }
        Collections.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

        if (outputDir == null) {
            throw new IOException("Export directory is null");
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Unable to create export directory");
        }

        File zip = new File(
                outputDir,
                "runnerprobe_" + sessionId + ".zip"
        );
        File partial = new File(zip.getAbsolutePath() + ".partial");

        if (partial.exists() && !partial.delete()) {
            throw new IOException("Unable to replace partial export");
        }

        try (ZipOutputStream out = new ZipOutputStream(
                new BufferedOutputStream(
                        new FileOutputStream(partial, false)
                ))) {
            byte[] buffer = new byte[8192];
            String root = "session_" + sessionId + "/";

            for (File file : files) {
                ZipEntry entry = new ZipEntry(root + file.getName());
                entry.setTime(0L);
                out.putNextEntry(entry);

                try (BufferedInputStream in = new BufferedInputStream(
                        new FileInputStream(file))) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                out.closeEntry();
            }
        } catch (IOException e) {
            partial.delete();
            throw e;
        }

        if (zip.exists() && !zip.delete()) {
            partial.delete();
            throw new IOException("Unable to replace previous export");
        }
        if (!partial.renameTo(zip)) {
            partial.delete();
            throw new IOException("Unable to finalize evidence ZIP");
        }

        return zip;
    }
}
