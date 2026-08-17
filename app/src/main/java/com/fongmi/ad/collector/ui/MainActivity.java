/* Probe v1 采集器主界面：保留参考流程，所有底层操作统一交给 ViewModel/Gateway。 */
package com.fongmi.ad.collector.ui;

import android.Manifest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.TextureView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;

import com.fongmi.ad.collector.R;
import com.fongmi.ad.collector.gateway.CollectorGateway;
import com.fongmi.ad.collector.gateway.ProbeCollectorGateway;
import com.fongmi.ad.collector.presentation.CollectorUiState;
import com.fongmi.ad.collector.presentation.CollectorViewModel;
import com.fongmi.ad.collector.rules.ProbeRule;
import com.fongmi.ad.collector.rules.RuleTest;

import java.util.List;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity implements CollectorViewModel.Observer {
    private CollectorViewModel viewModel;
    private VideoSurfaceController videoSurfaceController;
    private PlaybackSeekController playbackSeekController;
    private AdBoundaryEditor boundaryEditor;
    private RuleTestOverlay testOverlay;
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<String> storagePermissionLauncher;
    private EditText urlInput;
    private Button extractButton;
    private Button playButton;
    private Button autoScanButton;
    private Button testButton;
    private Button confirmButton;
    private Button savedRulesButton;
    private CheckBox autoSkipCheckBox;
    private TextView statusText;
    private TextView playerTimeText;
    private TextView ruleText;
    private TextView ruleCountText;
    private TextView rulePathText;
    private ImageButton startPreviewButton;
    private ImageButton endPreviewButton;
    private ImageButton durationPreviewButton;
    private Button useCurrentStartButton;
    private Button useCurrentEndButton;
    private long startMs;
    private long durationMs = 30_000L;
    private boolean savedTemplateApplied;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onRuleFileSelected);
        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted && viewModel != null) viewModel.reloadRules();
                });
        setContentView(R.layout.activity_main);
        bindViews();
        bindBoundaryEditor();
        bindActions();
        viewModel = new CollectorViewModel(ProbeCollectorGateway.create(this));
        viewModel.observe(this);
        videoSurfaceController = new VideoSurfaceController(
                (TextureView) findViewById(R.id.playerView),
                new VideoSurfaceController.Listener() {
                    @Override public void onAttach(android.view.Surface surface) {
                        CollectorViewModel active = viewModel;
                        if (active != null) active.attachVideoSurface(surface);
                    }

                    @Override public void onClear(android.view.Surface surface,
                                                  Runnable onCleared) {
                        CollectorViewModel active = viewModel;
                        if (active != null) active.clearVideoSurface(surface, onCleared);
                        else onCleared.run();
                    }
                });
        requestLegacyStoragePermission();
        refreshBoundary();
    }

    private void bindViews() {
        urlInput = findViewById(R.id.urlInput);
        extractButton = findViewById(R.id.extractButton);
        playButton = findViewById(R.id.playButton);
        autoScanButton = findViewById(R.id.autoScanButton);
        testButton = findViewById(R.id.testButton);
        confirmButton = findViewById(R.id.confirmButton);
        savedRulesButton = findViewById(R.id.savedRulesButton);
        autoSkipCheckBox = findViewById(R.id.autoSkipCheckBox);
        statusText = findViewById(R.id.statusText);
        playerTimeText = findViewById(R.id.playerTimeText);
        playbackSeekController = new PlaybackSeekController(
                (SeekBar) findViewById(R.id.playbackSeekBar), playerTimeText,
                positionMs -> {
                    if (viewModel != null) viewModel.seek(positionMs);
                });
        ruleText = findViewById(R.id.ruleText);
        ruleCountText = findViewById(R.id.confirmedRuleCountText);
        rulePathText = findViewById(R.id.rulePathText);
        startPreviewButton = findViewById(R.id.startPreviewButton);
        endPreviewButton = findViewById(R.id.endPreviewButton);
        durationPreviewButton = findViewById(R.id.durationPreviewButton);
        useCurrentStartButton = findViewById(R.id.useCurrentPositionButton);
        useCurrentEndButton = findViewById(R.id.useCurrentEndPositionButton);
        testOverlay = new RuleTestOverlay(this, findViewById(R.id.testOverlay),
                findViewById(R.id.testOverlayMessage), findViewById(R.id.skipAdButton));
    }

    private void bindBoundaryEditor() {
        boundaryEditor = new AdBoundaryEditor(
                findViewById(R.id.startMinuteText), findViewById(R.id.startSecondText),
                findViewById(R.id.startCentisecondText), findViewById(R.id.endMinuteText),
                findViewById(R.id.endSecondText), findViewById(R.id.endCentisecondText),
                findViewById(R.id.durationMinuteInput), findViewById(R.id.durationSecondInput),
                findViewById(R.id.durationCentisecondInput), findViewById(R.id.startTimeMinus),
                findViewById(R.id.startTimePlus), findViewById(R.id.endTimeMinus),
                findViewById(R.id.endTimePlus), findViewById(R.id.durationMinus),
                findViewById(R.id.durationPlus), new AdBoundaryEditor.Listener() {
                    @Override public void onSetStart(long positionMs) {
                        startMs = positionMs;
                        refreshBoundary();
                    }

                    @Override public void onSetEnd(long positionMs) {
                        durationMs = Math.max(5_000L, positionMs - startMs);
                        refreshBoundary();
                    }

                    @Override public void onSetDuration(long value) {
                        durationMs = Math.max(5_000L, value);
                        refreshBoundary();
                    }
                });
    }

    private void bindActions() {
        playButton.setOnClickListener(view -> {
            testOverlay.hide();
            viewModel.play(urlInput.getText().toString(), startMs, autoSkipCheckBox.isChecked());
        });
        autoScanButton.setOnClickListener(view -> {
            testOverlay.hide();
            viewModel.scan(urlInput.getText().toString(), autoSkipCheckBox.isChecked());
        });
        useCurrentStartButton.setOnClickListener(view -> {
            startMs = viewModel.getState().getPositionMs();
            refreshBoundary();
        });
        useCurrentEndButton.setOnClickListener(view -> {
            durationMs = Math.max(5_000L, viewModel.getState().getPositionMs() - startMs);
            refreshBoundary();
        });
        startPreviewButton.setOnClickListener(view -> viewModel.previewPosition(startMs));
        endPreviewButton.setOnClickListener(view ->
                viewModel.previewPosition(startMs + durationMs));
        durationPreviewButton.setOnClickListener(view ->
                viewModel.previewDuration(startMs, startMs + durationMs));
        extractButton.setOnClickListener(view -> viewModel.capture(startMs, durationMs));
        testButton.setOnClickListener(view -> {
            testOverlay.showWaiting();
            viewModel.testDraft(autoSkipCheckBox.isChecked());
        });
        confirmButton.setOnClickListener(view -> viewModel.saveDraft());
        savedRulesButton.setOnClickListener(view -> showRuleList());
        findViewById(R.id.mergeRulesButton).setOnClickListener(view ->
                importLauncher.launch(new String[]{"application/json", "text/json", "text/plain"}));
        findViewById(R.id.helpButton).setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.help_title).setMessage(R.string.help_message)
                .setPositiveButton(android.R.string.ok, null).show());
    }

    @Override
    public void onState(CollectorUiState state) {
        statusText.setText(state.getStatus());
        playbackSeekController.render(state.getPositionMs(), state.getMediaDurationMs(),
                state.isMediaReady());
        int count = state.getDocument().getRules().size();
        ruleCountText.setText(getString(R.string.confirmed_rule_count, count));
        savedRulesButton.setEnabled(count > 0);
        savedRulesButton.setText(getString(R.string.saved_rule_list_count, count));
        rulePathText.setText(getString(R.string.rule_file_path, state.getRulePath()));
        ProbeRule draft = state.getDraft();
        int draftCount = state.getDraftDocument().getRules().size();
        ruleText.setText(draft == null ? "" : "规则草稿 " + draftCount
                + " 条（未保存，可直接测试）\n"
                + draft.getId() + " · "
                + String.format(Locale.US, "%.2f 秒", draft.getDurationMs() / 1000.0));
        CollectorGateway.AutomaticCaptureProgress progress =
                state.getAutomaticCaptureProgress();
        if (progress != null && progress.getRange() != null) {
            startMs = progress.getRange().getAdStartMs();
            durationMs = progress.getRange().getDurationMs();
            savedTemplateApplied = false;
            refreshBoundary();
        }
        boolean automaticScanning = progress != null || state.getPlaybackState()
                == CollectorGateway.Snapshot.State.SCANNING;
        autoScanButton.setEnabled(!automaticScanning);
        if (progress == null) {
            autoScanButton.setText(automaticScanning
                    ? R.string.auto_scan_scanning : R.string.auto_scan);
        } else if (progress.getStage()
                == CollectorGateway.AutomaticCaptureProgress.Stage.SCANNING) {
            autoScanButton.setText(R.string.auto_scan_scanning);
        } else {
            autoScanButton.setText(getString(R.string.auto_scan_capturing,
                    progress.getCurrent(), progress.getTotal(), progress.getPercent()));
        }
        playButton.setEnabled(!automaticScanning);
        boolean selectionEnabled = state.isMediaReady() || savedTemplateApplied;
        boundaryEditor.setEnabled(selectionEnabled);
        useCurrentStartButton.setEnabled(state.isMediaReady());
        useCurrentEndButton.setEnabled(state.isMediaReady());
        startPreviewButton.setEnabled(state.isMediaReady());
        endPreviewButton.setEnabled(state.isMediaReady());
        durationPreviewButton.setEnabled(state.isMediaReady());
        extractButton.setEnabled(state.isMediaReady());
        testButton.setEnabled(draft != null && state.getPlaybackState()
                != CollectorGateway.Snapshot.State.TESTING);
        confirmButton.setEnabled(draft != null);
        confirmButton.setText(draft == null ? getString(R.string.confirm_rule)
                : getString(R.string.confirm_rule_count, draftCount));
    }

    @Override
    public void onMatch(CollectorGateway.Match match) {
        if (match.isAutomatic()) {
            testOverlay.hide();
            return;
        }
        testOverlay.showAdDetected(formatPosition(match.getEndMs()), () -> {
            viewModel.skipPendingMatch();
            testOverlay.hide();
        });
    }

    @Override
    public void onMatchCleared() {
        testOverlay.hide();
    }

    @Override
    public void onFailure(CollectorGateway.Failure failure) {
        testOverlay.hide();
        String message = failure == null ? "操作失败" : failure.getMessage();
        Toast.makeText(this, message == null || message.isEmpty() ? "操作失败" : message,
                Toast.LENGTH_LONG).show();
    }

    private void showRuleList() {
        List<ProbeRule> rules = viewModel.getState().getDocument().getRules();
        CharSequence[] labels = new CharSequence[rules.size()];
        for (int index = 0; index < rules.size(); index++) {
            ProbeRule rule = rules.get(index);
            RuleTest test = rule.getTest();
            String position = test == null ? "未保存开始位置"
                    : "开始 " + formatPosition(test.getAdStartMs());
            labels[index] = rule.getId() + "\n" + String.format(Locale.US,
                    "%.2f 秒 · %s · %s", rule.getDurationMs() / 1000.0, position,
                    test == null ? "无测试链接" : "有测试链接");
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.saved_rule_list_title, rules.size()))
                .setItems(labels, (dialog, which) -> applyRuleTemplate(rules.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyRuleTemplate(ProbeRule rule) {
        if (rule.getTest() != null) startMs = rule.getTest().getAdStartMs();
        durationMs = rule.getDurationMs();
        savedTemplateApplied = true;
        refreshBoundary();
        // 有意不修改 URL、不播放，也不改变网关当前参与匹配的完整规则文档。
        onState(viewModel.getState());
    }

    private void onRuleFileSelected(Uri uri) {
        if (uri == null) return;
        viewModel.merge(() -> getContentResolver().openInputStream(uri));
    }

    private void requestLegacyStoragePermission() {
        // API29 使用公共下载目录原子写入，必须先获得旧版存储授权。
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    private void refreshBoundary() {
        if (boundaryEditor != null) boundaryEditor.update(startMs, startMs + durationMs, durationMs);
    }

    private static String formatPosition(long timeMs) {
        long centiseconds = Math.max(0L, Math.round(timeMs / 10.0));
        long seconds = centiseconds / 100L;
        return String.format(Locale.US, "%02d:%02d.%02d",
                seconds / 60L, seconds % 60L, centiseconds % 100L);
    }

    @Override
    protected void onDestroy() {
        if (playbackSeekController != null) playbackSeekController.close();
        if (boundaryEditor != null) boundaryEditor.close();
        CollectorViewModel closing = viewModel;
        viewModel = null;
        if (closing != null) closing.observe(null);
        if (videoSurfaceController != null && closing != null) {
            videoSurfaceController.close(closing::close);
        } else if (closing != null) {
            closing.close();
        }
        super.onDestroy();
    }
}
