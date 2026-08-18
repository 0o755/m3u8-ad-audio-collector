/* 公开 ProbePlayer 的窄桥接：统一播放会话、Surface 和稳定状态回调。 */
package com.fongmi.ad.collector.gateway;

import android.content.Context;
import android.view.Surface;

import java.util.concurrent.Executor;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;
import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackDiscontinuityReason;
import io.github.fongmi.adaudio.probe.player.ProbePlayer;
import io.github.fongmi.adaudio.probe.player.ProbePlayerError;
import io.github.fongmi.adaudio.probe.player.ProbePlayerListener;
import io.github.fongmi.adaudio.probe.player.ProbePlayerState;
import io.github.fongmi.adaudio.probe.player.ProbePlayerStatus;

final class ProbePlayerHost implements AutoCloseable {
    interface Listener {
        void onStatus(long sessionId, ProbePlayerState state, long positionMs, long durationMs);
        void onDiscontinuity(long sessionId, long positionMs,
                             ProbePlaybackDiscontinuityReason reason);
        void onError(long sessionId, ProbeErrorCode code, boolean retryable, String message);
    }

    private final ProbePlayer player;

    ProbePlayerHost(Context context, Executor callbackExecutor, Listener listener) {
        player = ProbePlayer.builder(context)
                .setCallbackExecutor(callbackExecutor)
                .setListener(new ProbePlayerListener() {
                    @Override public void onStatusChanged(ProbePlayerStatus status) {
                        listener.onStatus(status.getSessionId(), status.getState(),
                                status.getPositionMs(), status.getDurationMs());
                    }

                    @Override public void onPositionDiscontinuity(long sessionId,
                            long positionMs, ProbePlaybackDiscontinuityReason reason) {
                        listener.onDiscontinuity(sessionId, positionMs, reason);
                    }

                    @Override public void onError(ProbePlayerError error) {
                        listener.onError(error.getSessionId(), error.getCode(),
                                error.isRetryable(), error.getMessage());
                    }
                })
                .build();
    }

    long open(ProbeMedia media, long startPositionMs) {
        return player.open(media, startPositionMs, true);
    }

    void seekTo(long positionMs) {
        player.seekTo(positionMs);
    }

    void play() {
        player.play();
    }

    void pause() {
        player.pause();
    }

    void attachSurface(Surface surface) {
        player.attachSurface(surface);
    }

    void clearSurface(Surface surface, Runnable onCleared) {
        player.clearSurface(surface, onCleared);
    }

    long getCurrentPositionMs() {
        return Math.max(0L, player.getCurrentPositionMs());
    }

    long getDurationMs() {
        return Math.max(0L, player.getDurationMs());
    }

    boolean isPlaying() {
        return player.isPlaying();
    }

    void stop() {
        player.stop();
    }

    @Override public void close() {
        player.close();
    }
}
