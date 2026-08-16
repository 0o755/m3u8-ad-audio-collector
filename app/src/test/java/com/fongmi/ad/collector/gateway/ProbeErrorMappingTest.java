/* 验证 Probe 公开错误分类到采集器稳定错误码的完整映射。 */
package com.fongmi.ad.collector.gateway;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;
import io.github.fongmi.adaudio.probe.tools.ProbeToolErrorCode;

public final class ProbeErrorMappingTest {
    @Test
    public void mapsEveryPublicProbeErrorCode() {
        assertMapped(CollectorGateway.Failure.Code.INVALID_REQUEST,
                ProbeErrorCode.INVALID_SOURCE);
        assertMapped(CollectorGateway.Failure.Code.UNSUPPORTED_SOURCE,
                ProbeErrorCode.UNSUPPORTED_SOURCE,
                ProbeErrorCode.LIVE_STREAM_NOT_SUPPORTED,
                ProbeErrorCode.DRM_NOT_SUPPORTED,
                ProbeErrorCode.NO_AUDIO_TRACK,
                ProbeErrorCode.UNSUPPORTED_AUDIO,
                ProbeErrorCode.DECODER_FAILED);
        assertMapped(CollectorGateway.Failure.Code.SOURCE_IO,
                ProbeErrorCode.SOURCE_IO);
        assertMapped(CollectorGateway.Failure.Code.RULES_INVALID,
                ProbeErrorCode.RULE_PARSE_FAILED,
                ProbeErrorCode.RULE_REVISION_CONFLICT);
        assertMapped(CollectorGateway.Failure.Code.RULES_UNAVAILABLE,
                ProbeErrorCode.RULE_FETCH_FAILED,
                ProbeErrorCode.RULES_UNAVAILABLE);
        assertMapped(CollectorGateway.Failure.Code.TIMELINE_UNRELIABLE,
                ProbeErrorCode.TIMELINE_UNRELIABLE);
        assertMapped(CollectorGateway.Failure.Code.RESOURCE_EXHAUSTED,
                ProbeErrorCode.RESOURCE_EXHAUSTED);
        assertMapped(CollectorGateway.Failure.Code.INTERNAL,
                ProbeErrorCode.INTERNAL);
    }

    @Test
    public void mapsEveryPublicToolErrorCode() {
        assertToolMapped(CollectorGateway.Failure.Code.INVALID_REQUEST,
                ProbeToolErrorCode.INVALID_REQUEST);
        assertToolMapped(CollectorGateway.Failure.Code.UNSUPPORTED_SOURCE,
                ProbeToolErrorCode.UNSUPPORTED_SOURCE,
                ProbeToolErrorCode.LIVE_STREAM_NOT_SUPPORTED,
                ProbeToolErrorCode.DRM_NOT_SUPPORTED,
                ProbeToolErrorCode.NO_AUDIO_TRACK,
                ProbeToolErrorCode.UNSUPPORTED_AUDIO,
                ProbeToolErrorCode.DECODER_FAILED);
        assertToolMapped(CollectorGateway.Failure.Code.SOURCE_IO,
                ProbeToolErrorCode.SOURCE_IO);
        assertToolMapped(CollectorGateway.Failure.Code.TIMELINE_UNRELIABLE,
                ProbeToolErrorCode.TIMELINE_UNRELIABLE);
        assertToolMapped(CollectorGateway.Failure.Code.RESOURCE_EXHAUSTED,
                ProbeToolErrorCode.RESOURCE_EXHAUSTED);
        assertToolMapped(CollectorGateway.Failure.Code.TIMEOUT,
                ProbeToolErrorCode.TIMEOUT);
        assertToolMapped(CollectorGateway.Failure.Code.INTERNAL,
                ProbeToolErrorCode.INTERNAL);
    }

    private static void assertMapped(CollectorGateway.Failure.Code expected,
                                     ProbeErrorCode... sourceCodes) {
        for (ProbeErrorCode sourceCode : sourceCodes) {
            assertEquals(sourceCode.name(), expected,
                    ProbeCollectorGateway.mapProbeError(sourceCode));
        }
    }

    private static void assertToolMapped(CollectorGateway.Failure.Code expected,
                                         ProbeToolErrorCode... sourceCodes) {
        for (ProbeToolErrorCode sourceCode : sourceCodes) {
            assertEquals(sourceCode.name(), expected,
                    ProbeCollectorGateway.mapToolError(sourceCode));
        }
    }
}
