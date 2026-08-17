/* 播放进度控制器：放大拖动热区，并把一次拖动收敛为一次宿主 seek 请求。 */
package com.fongmi.ad.collector.ui;

import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

final class PlaybackSeekController implements AutoCloseable {
    interface Listener {
        void onSeekRequested(long positionMs);
    }

    private final SeekBar seekBar;
    private final TextView timeText;
    private final Listener listener;
    private long durationMs;
    private boolean dragging;

    PlaybackSeekController(SeekBar seekBar, TextView timeText, Listener listener) {
        this.seekBar = seekBar;
        this.timeText = timeText;
        this.listener = listener;
        seekBar.setMax(1);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress,
                                                     boolean fromUser) {
                if (fromUser) updateTime(progress, durationMs);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (durationMs > 0L) listener.onSeekRequested(bar.getProgress());
            }
        });
    }

    void render(long positionMs, long newDurationMs, boolean enabled) {
        durationMs = Math.max(0L, Math.min(Integer.MAX_VALUE, newDurationMs));
        seekBar.setEnabled(enabled && durationMs > 0L);
        if (dragging) return;
        int maximum = (int) Math.max(1L, durationMs);
        int progress = (int) Math.max(0L, Math.min(durationMs, positionMs));
        if (seekBar.getMax() != maximum) seekBar.setMax(maximum);
        seekBar.setProgress(progress);
        updateTime(progress, durationMs);
    }

    private void updateTime(long positionMs, long totalMs) {
        timeText.setText(formatPosition(positionMs) + " / "
                + (totalMs > 0L ? formatPosition(totalMs) : "--:--"));
    }

    private static String formatPosition(long timeMs) {
        long centiseconds = Math.max(0L, Math.round(timeMs / 10.0));
        long totalSeconds = centiseconds / 100L;
        if (totalSeconds < 3_600L) {
            return String.format(Locale.US, "%02d:%02d.%02d", totalSeconds / 60L,
                    totalSeconds % 60L, centiseconds % 100L);
        }
        return String.format(Locale.US, "%02d:%02d:%02d.%02d", totalSeconds / 3_600L,
                totalSeconds / 60L % 60L, totalSeconds % 60L, centiseconds % 100L);
    }

    @Override public void close() {
        seekBar.setOnSeekBarChangeListener(null);
    }
}
