/* 规则测试覆盖层：集中管理等待、命中、跳过和完整验证的醒目提示。 */
package com.fongmi.ad.collector.ui;

import com.fongmi.ad.collector.R;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

final class RuleTestOverlay {
    private final Context context;
    private final View container;
    private final TextView messageView;
    private final Button skipButton;

    RuleTestOverlay(Context context, View container, TextView messageView, Button skipButton) {
        this.context = context;
        this.container = container;
        this.messageView = messageView;
        this.skipButton = skipButton;
    }

    void hide() {
        skipButton.setOnClickListener(null);
        skipButton.setVisibility(View.GONE);
        container.setVisibility(View.GONE);
    }

    void showWaiting() {
        showMessage(context.getString(R.string.test_waiting), R.color.test_waiting, false);
    }

    void showAdDetected(String endPosition, Runnable skipAction) {
        showMessage(context.getString(R.string.test_ad_detected, endPosition), R.color.test_ad, true);
        skipButton.setOnClickListener(view -> skipAction.run());
    }

    void showCannotSkip(String endPosition) {
        showMessage(context.getString(R.string.test_cannot_skip, endPosition), R.color.test_warning, false);
    }

    void showSeekFailed(String endPosition) {
        showMessage(context.getString(R.string.test_seek_failed, endPosition), R.color.test_warning, false);
    }

    private void showMessage(String text, int color, boolean showSkip) {
        messageView.setText(text);
        messageView.setTextColor(ContextCompat.getColor(context, color));
        skipButton.setOnClickListener(null);
        skipButton.setVisibility(showSkip ? View.VISIBLE : View.GONE);
        container.setVisibility(View.VISIBLE);
        // TextureView 画面更新时也要保证测试提示和跳过按钮处于最上层。
        container.bringToFront();
    }
}
