/* 广告参数编辑器：联动可编辑的开始位置、辅助结束位置和广告时长。 */
package com.fongmi.ad.collector.ui;

import com.fongmi.ad.collector.R;

import android.widget.Button;
import android.widget.EditText;

final class AdBoundaryEditor {
    interface Listener {
        void onSetStart(long positionMs);

        void onSetEnd(long positionMs);

        void onSetDuration(long durationMs);
    }

    private final TimeComponentStepper startStepper;
    private final TimeComponentStepper endStepper;
    private final DurationComponentStepper durationStepper;

    AdBoundaryEditor(EditText startMinute, EditText startSecond, EditText startCentisecond,
                     EditText endMinute, EditText endSecond, EditText endCentisecond,
                     EditText durationMinute, EditText durationSecond,
                     EditText durationCentisecond,
                     Button startMinus, Button startPlus, Button endMinus, Button endPlus,
                     Button durationMinus, Button durationPlus, Listener listener) {
        startStepper = new TimeComponentStepper(startMinute, startSecond, startCentisecond,
                startMinus, startPlus, listener::onSetStart);
        endStepper = new TimeComponentStepper(endMinute, endSecond, endCentisecond,
                endMinus, endPlus, listener::onSetEnd);
        durationStepper = new DurationComponentStepper(durationMinute, durationSecond,
                durationCentisecond,
                durationMinus, durationPlus, listener::onSetDuration);
    }

    void update(long startMs, long endMs, long durationMs) {
        startStepper.setPosition(startMs);
        endStepper.setPosition(endMs);
        durationStepper.setDuration(durationMs);
    }

    void setEnabled(boolean enabled) {
        startStepper.setEnabled(enabled);
        endStepper.setEnabled(enabled);
        durationStepper.setEnabled(enabled);
    }

    void reset() {
        startStepper.reset();
        endStepper.reset();
        durationStepper.reset();
    }

    void close() {
        startStepper.close();
        endStepper.close();
        durationStepper.close();
    }
}
