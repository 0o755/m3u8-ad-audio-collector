/* Probe collector-tools 的窄桥接：管理单个采集会话和单个 HLS 扫描会话。 */
package com.fongmi.ad.collector.gateway;

import android.content.Context;

import java.util.concurrent.Executor;

import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.tools.AudioFingerprintCollector;
import io.github.fongmi.adaudio.probe.tools.FingerprintCaptureListener;
import io.github.fongmi.adaudio.probe.tools.FingerprintCaptureProgress;
import io.github.fongmi.adaudio.probe.tools.FingerprintCaptureRequest;
import io.github.fongmi.adaudio.probe.tools.FingerprintRuleDraft;
import io.github.fongmi.adaudio.probe.tools.HlsCandidateScanner;
import io.github.fongmi.adaudio.probe.tools.HlsScanListener;
import io.github.fongmi.adaudio.probe.tools.HlsScanResult;
import io.github.fongmi.adaudio.probe.tools.ProbeToolError;
import io.github.fongmi.adaudio.probe.tools.ProbeToolSession;

final class ProbeToolHost implements AutoCloseable {
    interface CaptureListener {
        void onProgress(FingerprintCaptureProgress progress);
        void onCompleted(long sessionId, FingerprintRuleDraft draft);
        void onCancelled(long sessionId);
        void onError(ProbeToolError error);
    }

    interface ScanListener {
        void onCompleted(HlsScanResult result);
        void onCancelled(long sessionId);
        void onError(ProbeToolError error);
    }

    private final AudioFingerprintCollector collector;
    private final HlsCandidateScanner scanner;
    private ProbeToolSession captureSession;
    private ProbeToolSession scanSession;

    ProbeToolHost(Context context, Executor callbackExecutor) {
        collector = new AudioFingerprintCollector.Builder(context)
                .setCallbackExecutor(callbackExecutor)
                .build();
        scanner = new HlsCandidateScanner.Builder()
                .setCallbackExecutor(callbackExecutor)
                .build();
    }

    long capture(ProbeMedia media, String ruleId, CollectorGateway.CaptureRange range,
                 CaptureListener listener) {
        FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(media, ruleId,
                        range.getAdStartMs(), range.getAdStartMs() + range.getDurationMs())
                .setAnchor(range.getAnchorOffsetMs(), range.getAnchorDurationMs())
                .build();
        captureSession = collector.capture(request, new FingerprintCaptureListener() {
            @Override public void onProgress(FingerprintCaptureProgress progress) {
                listener.onProgress(progress);
            }

            @Override public void onCompleted(long sessionId, FingerprintRuleDraft draft) {
                listener.onCompleted(sessionId, draft);
            }

            @Override public void onCancelled(long sessionId) {
                listener.onCancelled(sessionId);
            }

            @Override public void onError(ProbeToolError error) {
                listener.onError(error);
            }
        });
        return captureSession.getSessionId();
    }

    long scan(ProbeMedia media, ScanListener listener) {
        scanSession = scanner.scan(media, new HlsScanListener() {
            @Override public void onCompleted(HlsScanResult result) {
                listener.onCompleted(result);
            }

            @Override public void onCancelled(long sessionId) {
                listener.onCancelled(sessionId);
            }

            @Override public void onError(ProbeToolError error) {
                listener.onError(error);
            }
        });
        return scanSession.getSessionId();
    }

    void cancelCapture() {
        if (captureSession != null) captureSession.cancel();
        captureSession = null;
    }

    void cancelScan() {
        if (scanSession != null) scanSession.cancel();
        scanSession = null;
    }

    @Override public void close() {
        collector.close();
        scanner.close();
    }
}
