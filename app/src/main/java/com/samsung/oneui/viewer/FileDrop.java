package com.samsung.oneui.viewer;

import android.content.ClipData;
import android.content.ClipDescription;
import android.net.Uri;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class FileDrop {
    private FileDrop() {}
    static boolean accepts(ClipDescription description) {
        return description != null && (description.hasMimeType("image/*") || description.hasMimeType("application/*")
            || description.hasMimeType("video/*") || description.hasMimeType("audio/*") || description.hasMimeType("text/*"));
    }
    static List<Uri> collect(ClipData clip) {
        List<Uri> values = new ArrayList<>();
        if (clip != null) for (int i=0;i<clip.getItemCount();i++) values.add(clip.getItemAt(i).getUri());
        return localUris(values);
    }
    static List<Uri> localUris(List<Uri> values) {
        LinkedHashSet<Uri> unique = new LinkedHashSet<>();
        for (Uri uri : values) if (uri != null && ("content".equalsIgnoreCase(uri.getScheme()) || "file".equalsIgnoreCase(uri.getScheme()))) unique.add(uri);
        return new ArrayList<>(unique);
    }
    static Runnable once(Runnable release) {
        AtomicBoolean completed = new AtomicBoolean();
        return () -> { if (completed.compareAndSet(false, true)) release.run(); };
    }
}
