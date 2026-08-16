/* Probe v1 规则的可选测试元数据，只保存链接与广告起点。 */
package com.fongmi.ad.collector.rules;

public final class RuleTest {
    private final String url;
    private final long adStartMs;

    public RuleTest(String url, long adStartMs) {
        this.url = url;
        this.adStartMs = adStartMs;
    }

    public String getUrl() {
        return url;
    }

    public long getAdStartMs() {
        return adStartMs;
    }
}
