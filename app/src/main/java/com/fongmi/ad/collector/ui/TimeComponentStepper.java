/* 位置分段步进器：分钟、秒和百分秒均支持加减与直接输入。 */
package com.fongmi.ad.collector.ui;

import com.fongmi.ad.collector.R;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;

import java.util.Locale;

final class TimeComponentStepper {
    interface Listener {
        void onSetPosition(long positionMs);
    }

    private enum Unit {
        MINUTE(60_000L),
        SECOND(1_000L),
        CENTISECOND(10L);

        final long stepMs;

        Unit(long stepMs) {
            this.stepMs = stepMs;
        }
    }

    private static final long INPUT_DEBOUNCE_MS = 350L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EditText minuteInput;
    private final EditText secondInput;
    private final EditText centisecondInput;
    private final Button minusButton;
    private final Button plusButton;
    private final Listener listener;
    private final Runnable applyInput = this::applyDirectInput;
    private Unit selectedUnit = Unit.SECOND;
    private boolean internalUpdate;
    private boolean enabled;
    private long positionMs;

    TimeComponentStepper(EditText minuteInput, EditText secondInput, EditText centisecondInput,
                         Button minusButton, Button plusButton, Listener listener) {
        this.minuteInput = minuteInput;
        this.secondInput = secondInput;
        this.centisecondInput = centisecondInput;
        this.minusButton = minusButton;
        this.plusButton = plusButton;
        this.listener = listener;
        bindInput(minuteInput, Unit.MINUTE);
        bindInput(secondInput, Unit.SECOND);
        bindInput(centisecondInput, Unit.CENTISECOND);
        minusButton.setOnClickListener(view ->
                listener.onSetPosition(Math.max(0L, positionMs - selectedUnit.stepMs)));
        plusButton.setOnClickListener(view ->
                listener.onSetPosition(positionMs + selectedUnit.stepMs));
        reset();
    }

    void setPosition(long value) {
        positionMs = Math.max(0L, value);
        long centiseconds = Math.round(positionMs / 10.0);
        long totalSeconds = centiseconds / 100L;
        internalUpdate = true;
        setText(minuteInput, String.format(Locale.US, "%02d", totalSeconds / 60L));
        setText(secondInput, String.format(Locale.US, "%02d", totalSeconds % 60L));
        setText(centisecondInput, String.format(Locale.US, "%02d", centiseconds % 100L));
        internalUpdate = false;
    }

    void setEnabled(boolean value) {
        enabled = value;
        minuteInput.setEnabled(value);
        secondInput.setEnabled(value);
        centisecondInput.setEnabled(value);
        minusButton.setEnabled(value);
        plusButton.setEnabled(value);
    }

    void reset() {
        handler.removeCallbacks(applyInput);
        positionMs = 0L;
        internalUpdate = true;
        minuteInput.setText(R.string.time_component_unset);
        secondInput.setText(R.string.time_component_unset);
        centisecondInput.setText(R.string.time_component_unset);
        internalUpdate = false;
        select(Unit.SECOND);
        setEnabled(false);
    }

    void close() {
        handler.removeCallbacksAndMessages(null);
    }

    private void bindInput(EditText input, Unit unit) {
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) select(unit);
            else applyDirectInput();
        });
        input.setOnClickListener(view -> select(unit));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                if (internalUpdate || !enabled) return;
                handler.removeCallbacks(applyInput);
                handler.postDelayed(applyInput, INPUT_DEBOUNCE_MS);
            }
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            handler.removeCallbacks(applyInput);
            applyDirectInput();
            return false;
        });
    }

    private void applyDirectInput() {
        if (!enabled || internalUpdate) return;
        try {
            long minutes = Long.parseLong(minuteInput.getText().toString());
            long seconds = Long.parseLong(secondInput.getText().toString());
            long centiseconds = Long.parseLong(centisecondInput.getText().toString());
            listener.onSetPosition(minutes * 60_000L + seconds * 1_000L + centiseconds * 10L);
        } catch (NumberFormatException ignored) {
            setPosition(positionMs);
        }
    }

    private void select(Unit unit) {
        selectedUnit = unit;
        minuteInput.setSelected(unit == Unit.MINUTE);
        secondInput.setSelected(unit == Unit.SECOND);
        centisecondInput.setSelected(unit == Unit.CENTISECOND);
    }

    private static void setText(EditText input, String text) {
        if (text.equals(input.getText().toString())) return;
        input.setText(text);
        input.setSelection(text.length());
    }
}
