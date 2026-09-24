package com.zcshou.motion;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ProducerSessionExporter {
    private static final String[] REQUIRED = {
            ProducerEvidenceRecorder.LOCATION_FILE,
            ProducerEvidenceRecorder.SYNTHETIC_FILE,
            ProducerEvidenceRecorder.META_FILE,
            ProducerEvidenceRecorder.MANIFEST_FILE
    };

    private ProducerSessionExporter() {
    }

    public static File exportSession(
            File sessionDir,
            File outputDir,
            String rawSessionId
    ) throws IOException {
        String sessionId = MotionSessionId.validate(rawSessionId);

        if (sessionDir == null || !sessionDir.isDirectory()) {
            throw new IOException("Producer session directory is missing");
        }

        File manifest = new File(
                sessionDir,
                ProducerEvidenceRecorder.MANIFEST_FILE
        );
        if (!manifest.isFile()) {
            throw new IOException("SESSION_NOT_FINALIZED");
        }

        String manifestText = new String(
                Files.readAllBytes(manifest.toPath()),
                StandardCharsets.UTF_8
        );
        if (!manifestText.contains("\"finalized\": true")
                || !manifestText.contains(
                "\"session_id\": \"" + sessionId + "\"")
                || !manifestText.contains("\"role\": \"producer\"")) {
            throw new IOException("SESSION_NOT_FINALIZED");
        }

        List<File> payloads = new ArrayList<>();
        for (String name : REQUIRED) {
            File file = new File(sessionDir, name);
            if (!file.isFile() || !file.canRead()) {
                throw new IOException(
                        "Missing finalized producer evidence file: " + name
                );
            }
            payloads.add(file);
        }
        Collections.sort(
                payloads,
                (left, right) -> left.getName().compareTo(right.getName())
        );

        if (outputDir == null) {
            throw new IOException("Export directory is null");
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Unable to create export directory");
        }

        File zip = new File(
                outputDir,
                "gogogo_producer_" + sessionId + ".zip"
        );
        File partial = new File(zip.getAbsolutePath() + ".partial");

        if (partial.exists() && !partial.delete()) {
            throw new IOException("Unable to replace partial export");
        }

        try (ZipOutputStream output = new ZipOutputStream(
                new BufferedOutputStream(
                        new FileOutputStream(partial, false)
                ))) {
            byte[] buffer = new byte[8192];
            String prefix = "session_" + sessionId + "/";

            for (File file : payloads) {
                ZipEntry entry = new ZipEntry(prefix + file.getName());
                entry.setTime(0L);
                output.putNextEntry(entry);

                try (BufferedInputStream input =
                             new BufferedInputStream(
                                     new FileInputStream(file)
                             )) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
                output.closeEntry();
            }
        } catch (IOException e) {
            partial.delete();
            throw e;
        }

        if (zip.exists() && !zip.delete()) {
            partial.delete();
            throw new IOException("Unable to replace prior export");
        }
        if (!partial.renameTo(zip)) {
            partial.delete();
            throw new IOException("Unable to finalize producer ZIP");
        }
        return zip;
    }
}
