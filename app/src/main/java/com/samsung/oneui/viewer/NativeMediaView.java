package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure Native Android Video & Audio Player View.
 * Uses Android VideoView + MediaPlayer with a custom One UI 9.5 transport bar.
 */
public final class NativeMediaView extends FrameLayout {

    private static final Set<String> AUDIO_EXTS = new HashSet<>(Arrays.asList(
            ".mp3", ".wav", ".ogg", ".m4a", ".flac", ".aac", ".opus"
    ));

    private final VideoView videoView;
    private final LinearLayout audioCard;
    private final TextView audioTitle;
    private final ImageView playBtnIcon;
    private final SeekBar seekBar;
    private final TextView timeText;
    private final LinearLayout controlBar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer activePlayer;
    private boolean isPlaying = false;
    private boolean isDraggingSeek = false;

    public void setSafeInsets(int bottomPx, int rightPx) {
        if (controlBar == null) return;
        LayoutParams lp = (LayoutParams) controlBar.getLayoutParams();
        int wantB = Math.max(dp(84), bottomPx + dp(10));
        int wantR = dp(24) + Math.max(0, rightPx);
        if (lp != null && (lp.bottomMargin != wantB || lp.rightMargin != wantR)) {
            lp.bottomMargin = wantB;
            lp.rightMargin = wantR;
            controlBar.setLayoutParams(lp);
        }
    }

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (isPlaying) {
                handler.postDelayed(this, 250);
            }
        }
    };

    public NativeMediaView(Context context) {
        super(context);

        videoView = new VideoView(context);
        LayoutParams vlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        vlp.gravity = Gravity.CENTER;
        addView(videoView, vlp);

        // Audio artwork card for audio-only files
        audioCard = new LinearLayout(context);
        audioCard.setOrientation(LinearLayout.VERTICAL);
        audioCard.setGravity(Gravity.CENTER);
        int pad = dp(28);
        audioCard.setPadding(pad, pad, pad, pad);
        GradientDrawable acBg = new GradientDrawable();
        acBg.setColor(0xFF1A1D24);
        acBg.setCornerRadius(dp(24));
        acBg.setStroke(dp(1), 0xFF2D3342);
        audioCard.setBackground(acBg);

        ImageView noteIcon = new ImageView(context);
        noteIcon.setImageDrawable(VectorIcons.create(context, "play", 44f, 0xFF0381FE));
        audioCard.addView(noteIcon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        audioTitle = new TextView(context);
        audioTitle.setTextColor(Color.WHITE);
        audioTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        audioTitle.setTypeface(Typeface.DEFAULT_BOLD);
        audioTitle.setGravity(Gravity.CENTER);
        audioTitle.setPadding(0, dp(14), 0, 0);
        audioCard.addView(audioTitle);

        LayoutParams aclp = new LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT);
        aclp.gravity = Gravity.CENTER;
        addView(audioCard, aclp);

        // Bottom One UI Floating Transport Bar
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(8), dp(16), dp(8));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xDD171A21);
        barBg.setCornerRadius(dp(26));
        barBg.setStroke(dp(1), 0x44FFFFFF);
        bar.setBackground(barBg);

        playBtnIcon = new ImageView(context);
        playBtnIcon.setPadding(dp(8), dp(8), dp(8), dp(8));
        playBtnIcon.setImageDrawable(VectorIcons.create(context, "play", 22f, Color.WHITE));
        playBtnIcon.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlayPause();
            }
        });
        bar.addView(playBtnIcon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        seekBar = new SeekBar(context);
        seekBar.setMax(1000);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(6);
        slp.rightMargin = dp(8);
        bar.addView(seekBar, slp);

        timeText = new TextView(context);
        timeText.setText("0:00 / 0:00");
        timeText.setTextColor(0xFFD5DCE8);
        timeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        timeText.setTypeface(Typeface.MONOSPACE);
        bar.addView(timeText);

        LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        blp.leftMargin = dp(24);
        blp.rightMargin = dp(24);
        blp.bottomMargin = dp(84);
        controlBar = bar;
        addView(bar, blp);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && videoView.getDuration() > 0) {
                    int target = (int) ((progress / 1000.0) * videoView.getDuration());
                    videoView.seekTo(target);
                    updateProgress();
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                isDraggingSeek = true;
            }
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                isDraggingSeek = false;
            }
        });

        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                activePlayer = mp;
                videoView.start();
                isPlaying = true;
                updatePlayIcon();
                handler.post(progressTicker);
            }
        });

        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                isPlaying = false;
                updatePlayIcon();
                updateProgress();
            }
        });
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    public void loadMedia(String path) {
        stopMedia();
        if (path == null || path.isEmpty()) return;
        File f = new File(path);
        String ext = Util.extOf(path);
        boolean isAudio = AUDIO_EXTS.contains(ext);
        audioCard.setVisibility(isAudio ? View.VISIBLE : View.GONE);
        audioTitle.setText(f.getName());
        videoView.setVideoURI(Uri.fromFile(f));
        videoView.requestFocus();
    }

    public void togglePlayPause() {
        if (videoView.isPlaying()) {
            videoView.pause();
            isPlaying = false;
        } else {
            videoView.start();
            isPlaying = true;
            handler.post(progressTicker);
        }
        updatePlayIcon();
    }

    public void stopMedia() {
        handler.removeCallbacks(progressTicker);
        isPlaying = false;
        try {
            videoView.stopPlayback();
        } catch (Exception ignored) {}
        updatePlayIcon();
    }
    public void pauseMedia() { if (videoView.isPlaying()) videoView.pause(); isPlaying = false; handler.removeCallbacks(progressTicker); updatePlayIcon(); }

    private void updatePlayIcon() {
        playBtnIcon.setImageDrawable(VectorIcons.create(
                getContext(),
                isPlaying ? "pause" : "play",
                22f,
                Color.WHITE
        ));
    }

    private void updateProgress() {
        try {
            int dur = videoView.getDuration();
            int pos = videoView.getCurrentPosition();
            if (dur > 0 && !isDraggingSeek) {
                seekBar.setProgress(Math.min(1000, Math.max(0, (int) ((pos * 1000L) / dur))));
            }
            timeText.setText(fmtTime(pos) + " / " + fmtTime(Math.max(0, dur)));
        } catch (Exception ignored) {}
    }

    private static String fmtTime(int ms) {
        int totalSec = Math.max(0, ms / 1000);
        int m = totalSec / 60;
        int s = totalSec % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}
