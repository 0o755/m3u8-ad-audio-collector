/* 自动采集批次只记录公开 HLS 候选的顺序、进度和成功草稿数量。 */
package com.fongmi.ad.collector.gateway;

import java.util.List;

import io.github.fongmi.adaudio.probe.tools.HlsAdCandidate;
import io.github.fongmi.adaudio.probe.tools.HlsScanResult;

final class AutomaticCaptureBatch {
    private final List<HlsAdCandidate> candidates;
    private int index;
    private int accepted;
    private CollectorGateway.Failure firstFailure;

    AutomaticCaptureBatch(HlsScanResult result) {
        candidates = result.getCandidates();
    }

    HlsAdCandidate current() {
        return isComplete() ? null : candidates.get(index);
    }

    int currentNumber() {
        return Math.min(index + 1, candidates.size());
    }

    int size() {
        return candidates.size();
    }

    int accepted() {
        return accepted;
    }

    CollectorGateway.Failure firstFailure() {
        return firstFailure;
    }

    boolean isComplete() {
        return index >= candidates.size();
    }

    void accept() {
        accepted++;
        index++;
    }

    void reject(CollectorGateway.Failure failure) {
        if (firstFailure == null) firstFailure = failure;
        index++;
    }
}
