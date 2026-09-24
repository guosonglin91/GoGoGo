package com.zcshou.runnerprobe.service;

import java.util.concurrent.CopyOnWriteArrayList;

public final class RecordingSnapshotBus {
    public interface Listener {
        void onRecordingSnapshot(RecordingSnapshot snapshot);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();
    private static volatile RecordingSnapshot latest =
            new RecordingSnapshot.Builder().build();

    private RecordingSnapshotBus() {
    }

    public static void publish(RecordingSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        latest = snapshot;
        for (Listener listener : LISTENERS) {
            listener.onRecordingSnapshot(snapshot);
        }
    }

    public static void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        LISTENERS.addIfAbsent(listener);
        listener.onRecordingSnapshot(latest);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static RecordingSnapshot latest() {
        return latest;
    }
}
