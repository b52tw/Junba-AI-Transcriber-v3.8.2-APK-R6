package tw.junba.transcriber;

import android.app.Activity;
import android.Manifest;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class FullTranscriberActivity extends Activity {
    private static final int REQ_AUDIO = 1001;
    private static final int REQ_SAVE_TXT = 1002;
    private static final int REQ_SAVE_MD = 1003;
    private static final String PREFS = "junba_android_v382";
    private static final String TRANSCRIBE_MODEL = "gemini-3.5-transcribe";
    private static final String LONG_AUDIO_MODEL = "gemini-3.8-flash";
    private static final long LONG_AUDIO_RANGE_THRESHOLD_MS = 3L * 60L * 60L * 1000L;
    private static final long LONG_AUDIO_RANGE_MS = 60L * 60L * 1000L;
    private static final String AI_STUDIO_URL = "https://aistudio.google.com/app/apikey";
    private static final String MODEL_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService settlementExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private final AtomicBoolean pauseFlag = new AtomicBoolean(false);
    private final AtomicBoolean pauseSettlementDone = new AtomicBoolean(false);
    private final AtomicBoolean failoverUsed = new AtomicBoolean(false);

    private Uri audioUri;
    private String audioName = "";
    private String lastTranscript = "";
    private String lastRawResponse = "";
    private String lastEngineLabel = "—";

    private TextView audioLabel;
    private EditText apiKey;
    private Spinner engineSpinner;
    private Spinner modeSpinner;
    private Spinner languageSpinner;
    private Spinner localModelSpinner;
    private CheckBox diarization;
    private CheckBox timestamps;
    private ProgressBar progress;
    private TextView stage;
    private TextView modelStatus;
    private TextView deviceStatus;
    private EditText result;
    private Button startButton;
    private Button cancelButton;
    private Button pauseButton;
    private Button resumeButton;
    private Button ktvButton;
    private Button modelButton;

    // R3 Safe: enhancements are deliberately isolated from the proven startup path.
    private Button apiKeyToggleButton;
    private View[] apiKeyViews;
    private boolean apiKeyExpanded = true;
    private TextView progressDetail;
    private TextView saveFolderHint;
    private final AtomicBoolean busyState = new AtomicBoolean(false);
    private Runnable heartbeatRunnable;
    private long operationStartedMs = 0L;
    private String progressRangeText = "";
    private String sessionDate = "";
    private String sessionStamp = "";
    private volatile boolean transcriptionJobActive = false;
    private volatile boolean sessionOriginalSaved = false;
    private volatile Uri lastKtvUri = null;
    private volatile long currentAudioDurationMs = -1L;
    private volatile long whisperInferenceStartedMs = 0L;
    private volatile long whisperEstimatedTotalMs = -1L;
    private volatile int whisperCompletedChunks = 0;
    private volatile int whisperTotalChunks = 0;
    private volatile boolean whisperModeActive = false;
    private volatile boolean lastTimelineEstimated = false;
    private ScrollView pageScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagnosticApplication.writeStage(this, "FULL:onCreate entered");
        try {
            DiagnosticApplication.writeStage(this, "FULL:before migrateLegacyApiKey");
            migrateLegacyApiKey();
            DiagnosticApplication.writeStage(this, "FULL:before buildUi");
            setContentView(buildUi());
            DiagnosticApplication.writeStage(this, "FULL:after buildUi / before updateModelStatus");
            updateModelStatus();
            restoreLastResultIfAny();
            DiagnosticApplication.writeStage(this, "FULL:READY");
        } catch (Throwable startupError) {
            DiagnosticApplication.writeCrashReport(this, startupError, "FullTranscriberActivity.onCreate");
            DiagnosticApplication.writeStage(this, "FULL:startup caught " + startupError.getClass().getSimpleName());
            showStartupFallback(startupError);
        }
    }

    /**
     * Last-resort startup guard. The R3 Safe build must never disappear back to the launcher
     * just because an optional UI enhancement failed on a specific OEM Android skin.
     */
    private void showStartupFallback(Throwable error) {
        try {
            ScrollView scroll = new ScrollView(this);
            // Android 16 / vivo V2413 workaround: scrolling stays enabled,
            // but the framework scrollbar drawable is never drawn.
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(16), dp(20), dp(16), dp(28));
            scroll.addView(root);
            root.addView(text("峻爸 AI Transcriber｜R6 啟動保護", 20, true));
            root.addView(text("完整功能已攔截 Java/Kotlin 啟動錯誤。請將下方錯誤文字或 R6 安全啟動層診斷內容回報。", 14, false));
            String detail = error == null ? "未知啟動錯誤" : error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            TextView e = text(detail, 13, false);
            e.setTextIsSelectable(true);
            e.setPadding(0, dp(12), 0, dp(12));
            root.addView(e);
            Button retry = button("重新載入完整介面");
            retry.setOnClickListener(v -> {
                try {
                    setContentView(buildUi());
                    updateModelStatus();
                } catch (Throwable again) {
                    e.setText(again.getClass().getSimpleName() + ": " + String.valueOf(again.getMessage()));
                }
            });
            root.addView(retry, lpMatch());
            setContentView(scroll);
        } catch (Throwable ignored) {
            // At this point Android itself will decide how to present the fatal condition.
        }
    }

    private void migrateLegacyApiKey() {
        if (!SecureKeyStore.load(this).isEmpty()) return;
        try {
            SharedPreferences old = getSharedPreferences("junba_android", MODE_PRIVATE);
            String legacy = old.getString("api_key", "");
            if (legacy != null && !legacy.trim().isEmpty()) {
                SecureKeyStore.save(this, legacy.trim());
                old.edit().remove("api_key").apply();
            }
        } catch (Exception ignored) {}
    }

    private View buildUi() {
        int pad = dp(14);
        ScrollView scroll = new ScrollView(this);
        pageScroll = scroll;
        scroll.setFillViewport(true);
        // Android 16 / vivo V2413 can crash in View.onDrawScrollBars() when
        // ScrollBarDrawable is null. Keep touch scrolling, disable only the bar.
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("峻爸 AI Transcriber v3.8.2 R6｜背景續跑 × 繁中 × KTV", 22, true));
        TextView note = text("自動混合會依手機、檔案格式、Whisper 模型與進階需求選擇本機 Whisper 或 Gemini。Gemini 採 Files API 整檔上傳；長錄音會自動切換 AI Studio 同級長上下文模式，不先切實體音檔。API Key 只儲存在這一台手機。", 13, false);
        note.setPadding(0, dp(4), 0, dp(10));
        root.addView(note);

        Button pick = button("選擇錄音檔");
        pick.setOnClickListener(v -> chooseAudio());
        root.addView(pick, lpMatch());
        audioLabel = text("尚未選擇錄音檔", 14, false);
        audioLabel.setPadding(0, dp(6), 0, dp(10));
        root.addView(audioLabel);

        root.addView(sectionLabel("辨識引擎"));
        engineSpinner = new Spinner(this);
        String[] engines = {
                "自動混合（建議）",
                "本機 Whisper（離線）",
                "Google Gemini（雲端）"
        };
        engineSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, engines));
        engineSpinner.setSelection(getSharedPreferences(PREFS, MODE_PRIVATE).getInt("engine", 0));
        root.addView(engineSpinner, lpMatch());

        deviceStatus = text(deviceSummary(), 12, false);
        deviceStatus.setPadding(0, dp(4), 0, dp(8));
        root.addView(deviceStatus);

        Button backgroundSettings = button("背景長時間執行／省電設定");
        backgroundSettings.setOnClickListener(v -> openBackgroundPowerSettings());
        root.addView(backgroundSettings, lpMatch());
        TextView bgNote = text("R6 轉錄時會啟動前景服務＋CPU WakeLock；若 vivo 仍限制背景工作，請在此把本 App 設為允許背景／不受電池最佳化限制。", 11, false);
        bgNote.setPadding(0, dp(2), 0, dp(8));
        root.addView(bgNote);

        root.addView(sectionLabel("本機 Whisper 模型"));
        localModelSpinner = new Spinner(this);
        String[] models = {
                "自動（依手機記憶體）",
                "tiny｜約 75 MB｜最快",
                "base｜約 142 MB｜建議",
                "small｜約 466 MB｜較準但較慢"
        };
        localModelSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, models));
        localModelSpinner.setSelection(getSharedPreferences(PREFS, MODE_PRIVATE).getInt("model", 0));
        localModelSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("model", position).apply();
                updateModelStatus();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        root.addView(localModelSpinner, lpMatch());
        modelStatus = text("檢查模型中…", 12, false);
        modelStatus.setPadding(0, dp(4), 0, dp(4));
        root.addView(modelStatus);
        modelButton = button("下載／準備本機 Whisper 模型");
        modelButton.setOnClickListener(v -> downloadSelectedModel());
        root.addView(modelButton, lpMatch());
        TextView localNote = text("本機 Whisper 完全離線，免費 AAR 目前支援 arm64-v8a。WAV／MP3／FLAC 由 Whisper 直接解碼；M4A／AAC／OGG／Opus／MP4 由 Android MediaCodec 在手機本機轉成 PCM WAV。此路徑不再使用 FFmpegKit。", 12, false);
        localNote.setPadding(0, dp(2), 0, dp(10));
        root.addView(localNote);

        // Keep the proven API-Key controls, but collapse them only AFTER the first frame is built.
        final String savedKeyForUi = SecureKeyStore.load(this);
        apiKeyToggleButton = button(savedKeyForUi.isEmpty() ? "Gemini API Key｜未設定 ▼" : "Gemini API Key｜已安全記憶 ▼");
        root.addView(apiKeyToggleButton, lpMatch());

        TextView apiKeyLabel = sectionLabel("Gemini API Key（每台手機獨立）");
        root.addView(apiKeyLabel);
        apiKey = new EditText(this);
        apiKey.setSingleLine(true);
        apiKey.setHint("先到 AI Studio 建立 Key，再貼回這裡");
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setText(savedKeyForUi);
        root.addView(apiKey, lpMatch());

        CheckBox showKey = new CheckBox(this);
        showKey.setText("顯示 API Key");
        showKey.setOnCheckedChangeListener((buttonView, checked) -> {
            apiKey.setInputType(InputType.TYPE_CLASS_TEXT | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            apiKey.setSelection(apiKey.length());
        });
        root.addView(showKey);

        Button aiStudio = button("前往 AI Studio 取得 API Key");
        aiStudio.setOnClickListener(v -> openAiStudio());
        Button pasteKey = button("從剪貼簿貼上並儲存");
        pasteKey.setOnClickListener(v -> pasteAndSaveKey());
        Button saveKey = button("儲存 Key");
        saveKey.setOnClickListener(v -> saveApiKey());
        Button testKey = button("測試 Key");
        testKey.setOnClickListener(v -> testApiKey());
        View apiRow1 = adaptiveButtonRow(aiStudio, pasteKey);
        View apiRow2 = adaptiveButtonRow(saveKey, testKey);
        root.addView(apiRow1);
        root.addView(apiRow2);
        TextView keyNote = text("基於帳號安全，App 不會也不能自動讀取你 Google 帳號裡的 API Key；每台手機貼上後會各自加密記憶。已儲存後此區會自動收折。", 12, false);
        keyNote.setPadding(0, dp(2), 0, dp(8));
        root.addView(keyNote);
        apiKeyViews = new View[]{apiKeyLabel, apiKey, showKey, apiRow1, apiRow2, keyNote};
        apiKeyToggleButton.setOnClickListener(v -> setApiKeyExpanded(!apiKeyExpanded));
        // OEM-safe: do not hide/reparent controls while Activity is still being constructed.
        root.post(() -> {
            try {
                if (!savedKeyForUi.isEmpty()) setApiKeyExpanded(false);
            } catch (Throwable ignored) {}
        });

        root.addView(sectionLabel("轉錄方式"));
        modeSpinner = new Spinner(this);
        String[] modes = {"逐字稿 verbatim", "智慧逐字稿 smart（Gemini 閱讀優先）"};
        modeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        root.addView(modeSpinner, lpMatch());

        languageSpinner = new Spinner(this);
        String[] langs = {"自動偵測語言", "繁體中文／華語・台語", "英文", "日文"};
        languageSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, langs));
        root.addView(languageSpinner, lpMatch());

        diarization = new CheckBox(this);
        diarization.setText("多人講者辨識（需要 Gemini）");
        diarization.setChecked(false);
        root.addView(diarization);
        timestamps = new CheckBox(this);
        timestamps.setText("字詞級時間戳（需要 Gemini；Whisper 仍會提供段落時間）");
        timestamps.setChecked(false);
        root.addView(timestamps);

        modeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean smart = position == 1;
                diarization.setEnabled(!smart);
                timestamps.setEnabled(!smart);
                if (smart) {
                    diarization.setChecked(false);
                    timestamps.setChecked(false);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        startButton = button("開始自動辨識");
        pauseButton = button("暫停並結算目前結果");
        resumeButton = button("繼續");
        cancelButton = button("停止並結算");
        pauseButton.setEnabled(false);
        resumeButton.setEnabled(false);
        cancelButton.setEnabled(false);
        startButton.setOnClickListener(v -> startTranscription());
        pauseButton.setOnClickListener(v -> requestPauseAndSettle());
        resumeButton.setOnClickListener(v -> requestResume());
        cancelButton.setOnClickListener(v -> requestStopAndSettle());
        root.addView(startButton, lpMatch());
        root.addView(adaptiveButtonRow(pauseButton, resumeButton, cancelButton));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, lpMatch());
        progressDetail = text("待命", 12, false);
        progressDetail.setVisibility(View.GONE);
        root.addView(progressDetail);
        stage = text("待命", 14, true);
        stage.setPadding(0, dp(4), 0, dp(8));
        root.addView(stage);

        root.addView(sectionLabel("轉錄結果"));
        result = new EditText(this);
        result.setGravity(Gravity.TOP | Gravity.START);
        result.setTextSize(16);
        result.setMinLines(14);
        // Root-cause fix for the captured Android 16 crash:
        // View.onDrawScrollBars() -> ScrollBarDrawable.mutate() on null.
        // Text remains fully touch-scrollable; only the visual scrollbar is disabled.
        result.setVerticalScrollBarEnabled(false);
        result.setHorizontalScrollBarEnabled(false);
        result.setOverScrollMode(View.OVER_SCROLL_NEVER);
        result.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        result.setHint("完成後會依辨識時間順序顯示逐字稿，可在框內上下完整預覽並直接修正。\n\nWhisper 保留段落時間；Gemini 長音訊會加入時間標記。 ");
        root.addView(result, lpMatch());

        Button topPreview = button("↑ 最前");
        Button bottomPreview = button("↓ 最後");
        topPreview.setOnClickListener(v -> { try { result.setSelection(0); if (pageScroll != null) pageScroll.fullScroll(View.FOCUS_UP); } catch (Throwable ignored) {} });
        bottomPreview.setOnClickListener(v -> { try { result.setSelection(result.length()); if (pageScroll != null) pageScroll.fullScroll(View.FOCUS_DOWN); } catch (Throwable ignored) {} });
        root.addView(adaptiveButtonRow(topPreview, bottomPreview));

        Button saveBundle = button("儲存本次（原音檔＋TXT＋Markdown＋VTT＋KTV HTML）");
        saveBundle.setOnClickListener(v -> saveSessionBundle());
        root.addView(saveBundle, lpMatch());
        ktvButton = button("開啟最近 KTV HTML 核對版");
        ktvButton.setEnabled(false);
        ktvButton.setOnClickListener(v -> openLastKtv());
        root.addView(ktvButton, lpMatch());

        Button saveTxt = button("手動另存 TXT");
        Button saveMd = button("手動另存 Markdown");
        saveTxt.setOnClickListener(v -> createDocument(false));
        saveMd.setOnClickListener(v -> createDocument(true));
        root.addView(adaptiveButtonRow(saveTxt, saveMd));

        saveFolderHint = text("自動存檔：Documents/Junba AI Transcriber/今天日期/日期時間/；原始音檔檔名不更動。", 11, false);
        saveFolderHint.setPadding(0, dp(4), 0, dp(2));
        root.addView(saveFolderHint);

        TextView limits = text("R6 自動規則：Whisper 會盡量轉為 PCM WAV 並以 5 分鐘安全區段背景執行，讓暫停／停止可在區段邊界結算，進度依已完成區段計算；完成後自動產生繁體中文時間軸與 KTV HTML。Gemini 仍採 Files API 整檔上傳，超限才改長音訊模式。", 12, false);
        limits.setPadding(0, dp(10), 0, 0);
        root.addView(limits);
        return scroll;
    }

    private void chooseAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, REQ_AUDIO);
    }

    private void openAiStudio() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_URL)));
            toast("在 AI Studio 建立／複製 Key 後，回到 App 按「從剪貼簿貼上並儲存」");
        } catch (Exception e) {
            toast("無法開啟瀏覽器：" + e.getMessage());
        }
    }

    private void pasteAndSaveKey() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) { toast("剪貼簿目前沒有內容"); return; }
        CharSequence cs = clip.getItemAt(0).coerceToText(this);
        String key = cs == null ? "" : cs.toString().trim();
        if (key.isEmpty()) { toast("剪貼簿內容是空的"); return; }
        apiKey.setText(key);
        saveApiKey();
    }

    private void saveApiKey() {
        try {
            SecureKeyStore.save(this, apiKey.getText().toString().trim());
            toast("API Key 已加密儲存在這一台手機");
            collapseApiKeySoon();
        } catch (Exception e) {
            toast("Key 儲存失敗：" + e.getMessage());
        }
    }

    private void testApiKey() {
        String key = apiKey.getText().toString().trim();
        if (key.isEmpty()) { toast("請先輸入 API Key"); return; }
        setBusy(true);
        setStage("測試 Gemini API Key…", false);
        executor.submit(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models").openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(15_000);
                c.setReadTimeout(20_000);
                c.setRequestProperty("x-goog-api-key", key);
                int code = c.getResponseCode();
                String body = readResponse(c);
                c.disconnect();
                if (code >= 200 && code < 300) {
                    SecureKeyStore.save(this, key);
                    setStage("Gemini API Key 測試成功，已記憶在此手機。", false);
                    collapseApiKeySoon();
                } else {
                    setStage("API Key 測試失敗 HTTP " + code + "：" + shortText(body, 180), true);
                }
            } catch (Exception e) {
                setStage("API Key 測試失敗：" + e.getMessage(), true);
            } finally {
                setBusy(false);
            }
        });
    }

    private void createDocument(boolean markdown) {
        String text = result.getText().toString().trim();
        if (text.isEmpty()) { toast("目前沒有可儲存的逐字稿"); return; }
        lastTranscript = text;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(markdown ? "text/markdown" : "text/plain");
        String stem = audioName.isEmpty() ? "逐字稿" : audioName.replaceFirst("\\.[^.]+$", "");
        intent.putExtra(Intent.EXTRA_TITLE, stem + (markdown ? "_逐字稿.md" : "_逐字稿.txt"));
        startActivityForResult(intent, markdown ? REQ_SAVE_MD : REQ_SAVE_TXT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_AUDIO) {
            audioUri = uri;
            audioName = displayName(uri);
            audioLabel.setText(audioName + "\n" + safeMime(uri));
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        } else if (requestCode == REQ_SAVE_TXT || requestCode == REQ_SAVE_MD) {
            boolean md = requestCode == REQ_SAVE_MD;
            String payload = md ? markdownText(result.getText().toString().trim()) : result.getText().toString().trim() + "\n";
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new Exception("無法開啟輸出檔");
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                toast(md ? "Markdown 已儲存" : "TXT 已儲存");
            } catch (Exception e) {
                toast("儲存失敗：" + e.getMessage());
            }
        }
    }

    private void startTranscription() {
        if (audioUri == null) { toast("請先選擇錄音檔"); return; }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("engine", engineSpinner.getSelectedItemPosition())
                .putInt("model", localModelSpinner.getSelectedItemPosition())
                .apply();
        String key = apiKey.getText().toString().trim();
        int engine = resolveEngine(key);
        if (engine == 0) return;

        cancelFlag.set(false);
        pauseFlag.set(false);
        pauseSettlementDone.set(false);
        failoverUsed.set(false);
        transcriptionJobActive = true;
        whisperModeActive = engine == 1;
        whisperCompletedChunks = 0;
        whisperTotalChunks = 0;
        lastTimelineEstimated = false;
        sessionOriginalSaved = false;
        lastKtvUri = null;
        sessionDate = "";
        sessionStamp = "";
        beginNewSessionStamp();
        requestNotificationPermissionIfNeeded();
        try { TranscriptionKeepAliveService.start(this, engine == 1 ? "Whisper 背景辨識準備中" : "Gemini 轉錄準備中"); } catch (Throwable ignored) {}

        setBusy(true);
        setProgressState("準備辨識…", 3, "準備中", false);
        result.setText("");
        if (engine == 1) startLocalWhisper(); else startGemini(key);
    }

    // 0=不能開始，1=Whisper，2=Gemini
    private int resolveEngine(String key) {
        int choice = engineSpinner.getSelectedItemPosition();
        boolean localReady = supportsLocalWhisper() && localAudioSupported(audioName) && selectedModelFile().isFile();
        boolean geminiNeeded = modeSpinner.getSelectedItemPosition() == 1 || diarization.isChecked() || timestamps.isChecked();

        if (choice == 1) {
            if (!supportsLocalWhisper()) { setStage("此手機 ABI 不支援目前的本機 Whisper AAR；請改用自動或 Gemini。", true); return 0; }
            if (!localAudioSupported(audioName)) { setStage("目前音訊格式不在本機 Whisper 支援清單；請改用自動或 Gemini。", true); return 0; }
            if (!selectedModelFile().isFile()) { setStage("尚未下載本機 Whisper 模型，請先按「下載／準備本機 Whisper 模型」。", true); return 0; }
            if (geminiNeeded) setStage("提示：本機 Whisper 不提供多人講者／字詞級時間戳／smart；將以段落時間逐字稿執行。", true);
            return 1;
        }
        if (choice == 2) {
            if (key.isEmpty()) { setStage("Gemini 模式需要 API Key。請按「前往 AI Studio 取得 API Key」。", true); return 0; }
            return 2;
        }

        // 自動混合：Gemini 專屬功能優先；其餘優先離線 Whisper。
        if (geminiNeeded) {
            if (!key.isEmpty()) return 2;
            if (localReady) {
                setStage("沒有 Gemini Key，已自動改用本機 Whisper；多人講者／字詞級時間戳將略過。", true);
                return 1;
            }
            setStage("目前設定需要 Gemini，但尚未設定 Key；也沒有可用的本機 Whisper 模型。", true);
            return 0;
        }
        if (localReady) return 1;
        if (!key.isEmpty()) {
            if (!localAudioSupported(audioName)) setStage("目前音檔格式不在本機轉碼支援清單，已自動改用 Gemini。", false);
            else if (!selectedModelFile().isFile()) setStage("尚未下載本機 Whisper 模型，已自動改用 Gemini。", false);
            return 2;
        }
        if (!supportsLocalWhisper()) setStage("此手機本機 Whisper 不支援，且尚未設定 Gemini API Key。", true);
        else if (!localAudioSupported(audioName)) setStage("目前格式無法走本機 Whisper；請先設定 Gemini API Key。", true);
        else setStage("請先下載本機 Whisper 模型，或設定 Gemini API Key。", true);
        return 0;
    }

    private void startLocalWhisper() {
        whisperModeActive = true;
        setProgressState("本機 Whisper：準備音檔與安全切段…", 8, "準備中", false);
        executor.submit(() -> {
            File temp = null;
            try {
                temp = copyUriToCache(audioUri);
                currentAudioDurationMs = audioDurationMs(temp);
                if (cancelFlag.get()) throw new InterruptedException("已要求停止");
                whisperInferenceStartedMs = 0L;
                whisperEstimatedTotalMs = estimateWhisperTotalMs(currentAudioDurationMs);
                TranscriptionKeepAliveService.update(this, "Whisper 正在準備音檔／安全切段");

                File source = temp;
                String text = WhisperBatchEngine.transcribeBlocking(
                        this,
                        selectedModelFile().getAbsolutePath(),
                        source.getAbsolutePath(),
                        whisperLanguageCode(),
                        5,
                        new WhisperBatchCallback() {
                            @Override public boolean isStopRequested() { return cancelFlag.get(); }

                            @Override public boolean waitWhilePaused() {
                                while (pauseFlag.get() && !cancelFlag.get()) {
                                    try { Thread.sleep(250L); } catch (InterruptedException e) { return false; }
                                }
                                return !cancelFlag.get();
                            }

                            @Override public void onPlanReady(int totalChunks, long totalAudioMs, boolean chunked) {
                                whisperTotalChunks = Math.max(1, totalChunks);
                                if (totalAudioMs > 0) currentAudioDurationMs = totalAudioMs;
                                whisperInferenceStartedMs = SystemClock.elapsedRealtime();
                                whisperEstimatedTotalMs = estimateWhisperTotalMs(currentAudioDurationMs);
                                String mode = chunked ? "5 分鐘安全區段" : "單段相容模式";
                                setProgressState("Whisper 已開始｜" + mode + "｜共 " + whisperTotalChunks + " 段", 12, "0/" + whisperTotalChunks + " 段", false);
                            }

                            @Override public void onChunkCompleted(int index, int total, String combinedText, long chunkElapsedMs) {
                                whisperCompletedChunks = index;
                                whisperTotalChunks = Math.max(1, total);
                                String formatted = formatTranscriptForOutput(combinedText, currentAudioDurationMs);
                                lastEngineLabel = "Whisper.cpp / " + selectedModelId();
                                lastTranscript = formatted;
                                persistLastResult(lastTranscript);
                                runOnUiThread(() -> {
                                    if (result != null) { result.setText(lastTranscript); result.setSelection(0); }
                                });
                                updateWhisperEstimateFromChunks(index, total);
                                int pct = 12 + (int) Math.round(83.0 * index / Math.max(1, total));
                                setProgressState("Whisper 辨識中｜完成區段 " + index + "/" + total, Math.min(95, pct), index + "/" + total + " 段", false);
                                TranscriptionKeepAliveService.update(FullTranscriberActivity.this, "Whisper " + index + "/" + total + "｜已 " + formatElapsed(SystemClock.elapsedRealtime() - whisperInferenceStartedMs));
                                if (pauseFlag.get() && pauseSettlementDone.compareAndSet(false, true)) {
                                    final String snap = lastTranscript;
                                    settlementExecutor.submit(() -> writeBundleSafely("暫停", snap));
                                }
                            }
                        });

                if (text != null && !text.trim().isEmpty()) {
                    lastEngineLabel = "Whisper.cpp / " + selectedModelId();
                    lastTranscript = formatTranscriptForOutput(text, currentAudioDurationMs);
                    persistLastResult(lastTranscript);
                    runOnUiThread(() -> { if (result != null) { result.setText(lastTranscript); result.setSelection(0); } });
                }
                recordWhisperSpeed();

                if (cancelFlag.get()) {
                    writeBundleSafely("中止", lastTranscript);
                    setStage("已停止並結算目前結果；可稍後重新開始。", true);
                } else {
                    writeBundleSafely("完成", lastTranscript);
                    setProgressState("本機 Whisper 轉錄完成｜已自動存檔", 100, "100%", false);
                }
                setBusy(false);
            } catch (InterruptedException e) {
                writeBundleSafely("中止", lastTranscript);
                setStage("已停止並結算目前結果", true);
                setBusy(false);
            } catch (Throwable e) {
                String key = apiKey == null ? "" : apiKey.getText().toString().trim();
                if (!cancelFlag.get() && engineSpinner.getSelectedItemPosition() == 0 && !key.isEmpty() && failoverUsed.compareAndSet(false, true)) {
                    setStage("本機 Whisper 失敗，已自動改用 Gemini：" + e.getClass().getSimpleName() + "：" + e.getMessage(), true);
                    whisperModeActive = false;
                    startGemini(key);
                } else {
                    writeBundleSafely("錯誤", lastTranscript);
                    setStage("本機 Whisper 失敗：" + e.getClass().getSimpleName() + "：" + e.getMessage(), true);
                    setBusy(false);
                }
            } finally {
                if (temp != null) try { temp.delete(); } catch (Throwable ignored) {}
            }
        });
    }

    private void startGemini(String key) {
        whisperModeActive = false;
        if (key.isEmpty()) { setStage("Gemini 需要 API Key。", true); setBusy(false); return; }
        try { SecureKeyStore.save(this, key); } catch (Exception ignored) {}
        setProgressState("Gemini：準備整份音檔…", 8, "準備中", false);
        executor.submit(() -> {
            File temp = null;
            String uploadedName = null;
            boolean handedOff = false;
            try {
                temp = copyUriToCache(audioUri);
                currentAudioDurationMs = audioDurationMs(temp);
                if (!waitForResumeOrStop()) throw new InterruptedException("已要求停止");
                String mime = safeMime(audioUri);

                setProgressState("上傳整份音訊至 Gemini Files API…", 15, "上傳中", false);
                TranscriptionKeepAliveService.update(this, "Gemini 音訊上傳中");
                JSONObject uploaded = uploadFile(key, temp, mime);
                JSONObject fileObj = uploaded.optJSONObject("file");
                if (fileObj == null) throw new Exception("Files API 沒有回傳 file 資訊：" + uploaded);
                String fileUri = fileObj.optString("uri", "");
                uploadedName = fileObj.optString("name", "");
                if (fileUri.isEmpty()) throw new Exception("Files API 沒有回傳 file URI");
                if (!uploadedName.isEmpty()) waitUntilFileReady(key, uploadedName);
                if (!waitForResumeOrStop()) throw new InterruptedException("已要求停止");

                String text;
                boolean longMode = shouldUseLongAudioMode(currentAudioDurationMs);
                if (!longMode) {
                    try {
                        setProgressState("Gemini 3.5 Transcribe 辨識中…", 60, "AI 處理中｜已執行 " + formatElapsed(SystemClock.elapsedRealtime() - operationStartedMs), false);
                        TranscriptionKeepAliveService.update(this, "Gemini AI 辨識中");
                        JSONObject response = transcribe(key, fileUri, mime);
                        lastRawResponse = response.toString(2);
                        text = extractBestTranscript(response).trim();
                        lastEngineLabel = TRANSCRIBE_MODEL;
                    } catch (Exception first) {
                        if (!isLengthLimitError(first)) throw first;
                        longMode = true;
                        setProgressState("專用 Transcribe 已達長度／token 上限，改用 Gemini 3.8 Flash 長音訊模式…", 60, "切換長音訊模式", true);
                        text = transcribeLongAudio(key, fileUri, mime, currentAudioDurationMs);
                        lastEngineLabel = LONG_AUDIO_MODEL + " / Files API";
                    }
                } else {
                    setProgressState("Gemini 3.8 Flash 長音訊模式：整檔辨識中…", 60, "AI 長音訊處理中", false);
                    TranscriptionKeepAliveService.update(this, "Gemini 長音訊辨識中");
                    text = transcribeLongAudio(key, fileUri, mime, currentAudioDurationMs);
                    lastEngineLabel = LONG_AUDIO_MODEL + " / Files API";
                }

                if (text == null || text.trim().isEmpty()) {
                    text = "【Gemini 沒有回傳可解析的逐字稿】\n\n" + lastRawResponse;
                }
                lastTranscript = formatTranscriptForOutput(text.trim(), currentAudioDurationMs);
                persistLastResult(lastTranscript);
                String finalText = lastTranscript;
                runOnUiThread(() -> { if (result != null) { result.setText(finalText); result.setSelection(0); } });

                if (pauseFlag.get()) {
                    writeBundleSafely("暫停", lastTranscript);
                    pauseSettlementDone.set(true);
                    if (!waitForResumeOrStop()) {
                        writeBundleSafely("中止", lastTranscript);
                        setStage("已停止並結算目前 Gemini 結果", true);
                        return;
                    }
                }
                if (cancelFlag.get()) {
                    writeBundleSafely("中止", lastTranscript);
                    setStage("已停止並結算目前 Gemini 結果", true);
                } else {
                    writeBundleSafely("完成", lastTranscript);
                    setProgressState(longMode ? "Gemini 長音訊轉錄完成｜已自動存檔" : "Gemini 轉錄完成｜已自動存檔", 100, "100%", false);
                }
            } catch (InterruptedException e) {
                writeBundleSafely("中止", lastTranscript);
                setStage("已停止並結算目前結果", true);
            } catch (Exception e) {
                boolean canFallbackLocal = !cancelFlag.get() && engineSpinner.getSelectedItemPosition() == 0 &&
                        supportsLocalWhisper() && localAudioSupported(audioName) && selectedModelFile().isFile() &&
                        failoverUsed.compareAndSet(false, true);
                if (canFallbackLocal) {
                    setStage("Gemini 失敗，已自動改用本機 Whisper（進階 Gemini 功能將略過）：" + e.getMessage(), true);
                    final boolean hadUpload = uploadedName != null && !uploadedName.isEmpty();
                    if (hadUpload) { try { deleteUploaded(key, uploadedName); } catch (Exception ignored) {} }
                    if (temp != null) temp.delete();
                    uploadedName = null;
                    temp = null;
                    handedOff = true;
                    startLocalWhisper();
                    return;
                } else {
                    writeBundleSafely("錯誤", lastTranscript);
                    setStage("Gemini 失敗：" + e.getClass().getSimpleName() + "：" + e.getMessage(), true);
                }
            } finally {
                if (uploadedName != null && !uploadedName.isEmpty()) {
                    try { deleteUploaded(key, uploadedName); } catch (Exception ignored) {}
                }
                if (temp != null) temp.delete();
                if (!handedOff) setBusy(false);
            }
        });
    }

    private boolean shouldUseLongAudioMode(long durationMs) {
        if (durationMs <= 0) return false; // unknown: try dedicated Transcribe first, then fail over automatically
        long safeLimit = (diarization.isChecked() || timestamps.isChecked())
                ? 25L * 60L * 1000L
                : 45L * 60L * 1000L;
        return durationMs > safeLimit;
    }

    private boolean isLengthLimitError(Exception e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return m.contains("98304") || m.contains("token count exceeds") ||
                m.contains("maximum number of tokens") || m.contains("context") ||
                m.contains("too long") || m.contains("audio limit");
    }

    private long audioDurationMs(File file) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            String v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return v == null ? -1L : Long.parseLong(v);
        } catch (Exception ignored) {
            return -1L;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private void waitUntilFileReady(String key, String name) throws Exception {
        String path = name.startsWith("files/") ? name : "files/" + name;
        for (int i = 0; i < 40; i++) {
            if (cancelFlag.get()) throw new InterruptedException("已取消");
            HttpURLConnection c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/" + path).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("x-goog-api-key", key);
            int code = c.getResponseCode();
            String body = readResponse(c);
            c.disconnect();
            if (code < 200 || code >= 300) throw new Exception("查詢 Gemini 檔案狀態失敗 HTTP " + code + "：" + shortText(body, 240));
            JSONObject o = new JSONObject(body);
            String state = o.optString("state", "ACTIVE");
            if ("ACTIVE".equalsIgnoreCase(state) || state.isEmpty()) return;
            if ("FAILED".equalsIgnoreCase(state)) throw new Exception("Gemini 檔案處理失敗：" + shortText(body, 240));
            Thread.sleep(1500L);
        }
        throw new Exception("Gemini 檔案處理逾時，請稍後重試");
    }

    private String transcribeLongAudio(String key, String fileUri, String mime, long durationMs) throws Exception {
        if (durationMs > LONG_AUDIO_RANGE_THRESHOLD_MS) {
            setStage("超長錄音：同一雲端檔案分時段取得逐字稿（不重新切檔／不上傳多份）…", false);
            return transcribeLongAudioRanges(key, fileUri, mime, durationMs);
        }

        JSONObject response = generateLongAudio(key, fileUri, mime, buildLongAudioPrompt(-1L, -1L));
        lastRawResponse = response.toString(2);
        String text = extractGenerateText(response).trim();
        if (isMaxTokensResponse(response) && durationMs > 0) {
            setStage("整檔輸出達上限，改由同一雲端檔案分時段取得並自動合併…", true);
            return transcribeLongAudioRanges(key, fileUri, mime, durationMs);
        }
        return text;
    }

    private String transcribeLongAudioRanges(String key, String fileUri, String mime, long durationMs) throws Exception {
        long total = durationMs > 0 ? durationMs : LONG_AUDIO_RANGE_MS;
        StringBuilder merged = new StringBuilder();
        int part = 0;
        for (long start = 0; start < total; start += LONG_AUDIO_RANGE_MS) {
            if (!waitForResumeOrStop()) break;
            long end = Math.min(total, start + LONG_AUDIO_RANGE_MS);
            part++;
            final int partNo = part;
            int totalParts = (int) Math.max(1L, (total + LONG_AUDIO_RANGE_MS - 1L) / LONG_AUDIO_RANGE_MS);
            int pct = 60 + (int) Math.min(34L, (partNo - 1L) * 34L / totalParts);
            setProgressState("Gemini 長音訊模式：取得第 " + partNo + "/" + totalParts + " 段 " + formatClock(start) + "–" + formatClock(end) + "…", pct, partNo + "/" + totalParts + " 段", false);
            TranscriptionKeepAliveService.update(this, "Gemini 長音訊 " + partNo + "/" + totalParts);
            JSONObject response = generateLongAudio(key, fileUri, mime, buildLongAudioPrompt(start, end));
            lastRawResponse = response.toString(2);
            String text = extractGenerateText(response).trim();
            if (text.isEmpty()) throw new Exception("第 " + partNo + " 段沒有回傳文字");
            if (merged.length() > 0) merged.append("\n\n");
            merged.append(text);

            lastTranscript = formatTranscriptForOutput(merged.toString(), durationMs);
            persistLastResult(lastTranscript);
            String snap = lastTranscript;
            runOnUiThread(() -> { if (result != null) { result.setText(snap); result.setSelection(0); } });

            if (pauseFlag.get() && pauseSettlementDone.compareAndSet(false, true)) {
                settlementExecutor.submit(() -> writeBundleSafely("暫停", snap));
            }
            if (!waitForResumeOrStop()) break;
        }
        return merged.toString();
    }

    private String buildLongAudioPrompt(long startMs, long endMs) {
        boolean smart = modeSpinner.getSelectedItemPosition() == 1;
        StringBuilder p = new StringBuilder();
        p.append("請將這份錄音轉成繁體中文介面的完整逐字稿。中文內容一律使用台灣繁體中文，不要輸出簡體字；保留原本語言，不要摘要、不要漏掉有意義內容。\n");
        if (startMs >= 0 && endMs > startMs) {
            p.append("只處理音訊時間 ").append(formatClock(startMs)).append(" 到 ").append(formatClock(endMs)).append("，不要重複範圍外內容。\n");
        }
        if (smart) {
            p.append("使用智慧逐字稿：可移除明顯贅詞與無意義重複，修正常見口語斷句與標點，但不可改變原意。\n");
        } else {
            p.append("使用 verbatim 逐字稿：盡量保留原話、語助詞與口語表達。\n");
        }
        if (diarization.isChecked()) p.append("若能可靠辨識不同說話者，請以「講者1：」「講者2：」標示；不確定時不要亂猜身分。\n");
        if (timestamps.isChecked()) p.append("長音訊模式請至少在每個自然段前標示 [HH:MM:SS] 時間；不需為每一個字輸出時間，以免輸出超限。\n");
        else p.append("每隔一段內容加入 [HH:MM:SS] 時間標記，方便回聽。\n");
        String lang = geminiLanguageCode();
        if (!lang.isEmpty()) p.append("主要語言提示：").append(lang).append("。若實際錄音有多語切換，仍照實轉錄。\n");
        p.append("直接輸出逐字稿正文，不要加入分析、前言或結語。");
        return p.toString();
    }

    private JSONObject generateLongAudio(String key, String fileUri, String mime, String prompt) throws Exception {
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + LONG_AUDIO_MODEL + ":generateContent");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(600_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("x-goog-api-key", key);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        JSONObject fileData = new JSONObject().put("file_uri", fileUri).put("mime_type", mime);
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("file_data", fileData))
                .put(new JSONObject().put("text", prompt));
        JSONObject content = new JSONObject().put("role", "user").put("parts", parts);
        JSONObject generation = new JSONObject()
                .put("temperature", 0.0)
                .put("maxOutputTokens", 64000)
                .put("thinkingConfig", new JSONObject().put("thinkingLevel", "low"));
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(content))
                .put("generationConfig", generation);

        writeUtf8(conn, body.toString());
        int code = conn.getResponseCode();
        String response = readResponse(conn);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new Exception("Gemini 長音訊模式失敗 HTTP " + code + "：" + response);
        return new JSONObject(response);
    }

    private boolean isMaxTokensResponse(JSONObject root) {
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null) return false;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject c = candidates.optJSONObject(i);
            if (c != null && "MAX_TOKENS".equalsIgnoreCase(c.optString("finishReason", ""))) return true;
        }
        return false;
    }

    private String extractGenerateText(JSONObject root) {
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject c = candidates.optJSONObject(i);
            JSONObject content = c == null ? null : c.optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts == null) continue;
            for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.optJSONObject(j);
                if (part == null) continue;
                String t = part.optString("text", "");
                if (!t.isEmpty()) b.append(t).append('\n');
            }
        }
        return b.toString();
    }

    private static String formatClock(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s);
    }

    private void downloadSelectedModel() {
        if (!supportsLocalWhisper()) {
            setStage("此手機不是 arm64-v8a，本版免費 Whisper AAR 無法使用。", true);
            return;
        }
        String id = selectedModelId();
        File target = modelFile(id);
        if (target.isFile() && target.length() > 20_000_000L) {
            toast("模型已存在：" + target.getName());
            updateModelStatus();
            return;
        }
        cancelFlag.set(false);
        setBusy(true);
        modelButton.setEnabled(false);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        setProgressState("下載 Whisper " + id + " 模型…", 1, "1%", false);
        executor.submit(() -> {
            File part = new File(target.getParentFile(), target.getName() + ".part");
            try {
                if (!target.getParentFile().exists() && !target.getParentFile().mkdirs()) throw new Exception("無法建立模型資料夾");
                URL u = new URL(MODEL_BASE_URL + target.getName());
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(30_000);
                c.setReadTimeout(120_000);
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("下載失敗 HTTP " + code);
                long total = c.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
                    byte[] buf = new byte[256 * 1024];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (cancelFlag.get()) throw new InterruptedException("已取消");
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) Math.min(100, done * 100L / total);
                            setExactProgress(pct, "下載 Whisper " + id + " 模型");
                        }
                    }
                } finally { c.disconnect(); }
                if (!part.renameTo(target)) {
                    try (InputStream in = new FileInputStream(part); OutputStream out = new FileOutputStream(target)) {
                        byte[] b = new byte[128 * 1024]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    }
                    part.delete();
                }
                setProgressState("Whisper " + id + " 模型下載完成，可離線使用。", 100, "100%", false);
            } catch (InterruptedException e) {
                part.delete();
                setStage("模型下載已取消", true);
            } catch (Exception e) {
                part.delete();
                setStage("模型下載失敗：" + e.getMessage(), true);
            } finally {
                runOnUiThread(() -> {
                    progress.setIndeterminate(true);
                    modelButton.setEnabled(true);
                    updateModelStatus();
                });
                setBusy(false);
            }
        });
    }

    private boolean supportsLocalWhisper() {
        return Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a");
    }

    private boolean localAudioSupported(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".wav") || n.endsWith(".mp3") || n.endsWith(".flac") ||
                n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".ogg") ||
                n.endsWith(".opus") || n.endsWith(".mp4");
    }

    private String selectedModelId() {
        int p = localModelSpinner == null ? 0 : localModelSpinner.getSelectedItemPosition();
        if (p == 1) return "tiny";
        if (p == 2) return "base";
        if (p == 3) return "small";
        return recommendedModelId();
    }

    private String recommendedModelId() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long gb = mi.totalMem / (1024L * 1024L * 1024L);
            return gb < 5 ? "tiny" : "base";
        } catch (Exception e) {
            return "base";
        }
    }

    private File modelFile(String id) {
        File dir = getExternalFilesDir("models");
        if (dir == null) dir = new File(getFilesDir(), "models");
        return new File(dir, "ggml-" + id + ".bin");
    }

    private File selectedModelFile() { return modelFile(selectedModelId()); }

    private void updateModelStatus() {
        if (modelStatus == null) return;
        String id = selectedModelId();
        File f = modelFile(id);
        String state = f.isFile() ? String.format(Locale.ROOT, "已下載 %.0f MB", f.length() / 1024.0 / 1024.0) : "尚未下載";
        modelStatus.setText("自動建議：" + recommendedModelId() + "｜目前：" + id + "｜" + state);
    }

    private String deviceSummary() {
        String ram = "?";
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            ram = String.format(Locale.ROOT, "%.1f GB", mi.totalMem / 1024.0 / 1024.0 / 1024.0);
        } catch (Exception ignored) {}
        return "裝置：" + Build.MODEL + "｜RAM " + ram + "｜ABI " + String.join(",", Build.SUPPORTED_ABIS) + "｜本機 Whisper " + (supportsLocalWhisper() ? "可用" : "不可用");
    }

    private File copyUriToCache(Uri uri) throws Exception {
        String ext = ".audio";
        String n = displayName(uri);
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot < n.length() - 1) ext = n.substring(dot);
        File dst = File.createTempFile("jba_", ext, getCacheDir());
        try (InputStream in = new BufferedInputStream(getContentResolver().openInputStream(uri));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            if (in == null) throw new Exception("無法讀取選取的音檔");
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                if (cancelFlag.get()) throw new InterruptedException("已取消");
                out.write(buf, 0, r);
            }
        }
        return dst;
    }

    private JSONObject uploadFile(String key, File file, String mime) throws Exception {
        URL startUrl = new URL("https://generativelanguage.googleapis.com/upload/v1beta/files");
        HttpURLConnection start = (HttpURLConnection) startUrl.openConnection();
        start.setRequestMethod("POST");
        start.setConnectTimeout(30_000);
        start.setReadTimeout(60_000);
        start.setDoOutput(true);
        start.setRequestProperty("x-goog-api-key", key);
        start.setRequestProperty("X-Goog-Upload-Protocol", "resumable");
        start.setRequestProperty("X-Goog-Upload-Command", "start");
        start.setRequestProperty("X-Goog-Upload-Header-Content-Length", String.valueOf(file.length()));
        start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime);
        start.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        JSONObject meta = new JSONObject().put("file", new JSONObject().put("display_name", audioName.isEmpty() ? "audio" : audioName));
        writeUtf8(start, meta.toString());
        int sc = start.getResponseCode();
        if (sc < 200 || sc >= 300) throw new Exception("建立上傳工作失敗 HTTP " + sc + "：" + readResponse(start));
        String uploadUrl = start.getHeaderField("X-Goog-Upload-URL");
        if (uploadUrl == null || uploadUrl.isEmpty()) uploadUrl = start.getHeaderField("x-goog-upload-url");
        start.disconnect();
        if (uploadUrl == null || uploadUrl.isEmpty()) throw new Exception("Gemini 未回傳 upload URL");

        HttpURLConnection up = (HttpURLConnection) new URL(uploadUrl).openConnection();
        up.setRequestMethod("POST");
        up.setConnectTimeout(30_000);
        up.setReadTimeout(180_000);
        up.setDoOutput(true);
        up.setFixedLengthStreamingMode(file.length());
        up.setRequestProperty("Content-Length", String.valueOf(file.length()));
        up.setRequestProperty("X-Goog-Upload-Offset", "0");
        up.setRequestProperty("X-Goog-Upload-Command", "upload, finalize");
        up.setRequestProperty("Content-Type", mime);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file)); OutputStream out = new BufferedOutputStream(up.getOutputStream())) {
            byte[] buf = new byte[256 * 1024];
            int r;
            long sent = 0L;
            long totalBytes = Math.max(1L, file.length());
            while ((r = in.read(buf)) != -1) {
                if (cancelFlag.get()) throw new InterruptedException("已取消");
                out.write(buf, 0, r);
                sent += r;
                int pct = 15 + (int) Math.min(30L, sent * 30L / totalBytes);
                setExactProgress(pct, "Gemini 音訊上傳中");
            }
        }
        int code = up.getResponseCode();
        String body = readResponse(up);
        up.disconnect();
        if (code < 200 || code >= 300) throw new Exception("音訊上傳失敗 HTTP " + code + "：" + body);
        return new JSONObject(body);
    }

    private JSONObject transcribe(String key, String fileUri, String mime) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(240_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("x-goog-api-key", key);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        JSONObject audio = new JSONObject().put("type", "audio").put("uri", fileUri).put("mime_type", mime);
        JSONArray input = new JSONArray().put(audio);
        JSONObject tc = new JSONObject();
        if (modeSpinner.getSelectedItemPosition() == 1) {
            tc.put("mode", "smart");
        } else {
            JSONObject mode = new JSONObject().put("type", "verbatim");
            if (diarization.isChecked()) mode.put("diarization_mode", "speaker");
            if (timestamps.isChecked()) mode.put("timestamp_granularities", new JSONArray().put("word"));
            tc.put("mode", mode);
        }
        String lang = geminiLanguageCode();
        if (!lang.isEmpty()) tc.put("language_codes", new JSONArray().put(lang));

        JSONObject body = new JSONObject()
                .put("model", TRANSCRIBE_MODEL)
                .put("input", input)
                .put("generation_config", new JSONObject().put("transcription_config", tc));
        writeUtf8(conn, body.toString());
        int code = conn.getResponseCode();
        String response = readResponse(conn);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new Exception("Gemini 轉錄失敗 HTTP " + code + "：" + response);
        return new JSONObject(response);
    }

    private void deleteUploaded(String key, String name) throws Exception {
        String path = name.startsWith("files/") ? name : "files/" + name;
        HttpURLConnection c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/" + path).openConnection();
        c.setRequestMethod("DELETE");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("x-goog-api-key", key);
        try { c.getResponseCode(); } finally { c.disconnect(); }
    }

    private static void writeUtf8(HttpURLConnection c, String s) throws Exception {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
    }

    private static String readResponse(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString().trim();
    }

    private String extractOutputText(JSONObject root) {
        String v = root.optString("output_text", "");
        if (!v.isEmpty()) return v;
        JSONArray outputs = root.optJSONArray("outputs");
        if (outputs != null) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < outputs.length(); i++) {
                JSONObject o = outputs.optJSONObject(i);
                if (o != null && "text".equalsIgnoreCase(o.optString("type"))) {
                    String t = o.optString("text", "");
                    if (!t.isEmpty()) b.append(t).append('\n');
                }
            }
            if (b.length() > 0) return b.toString();
        }
        JSONArray steps = root.optJSONArray("steps");
        if (steps != null) {
            for (int i = steps.length() - 1; i >= 0; i--) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                JSONArray content = step.optJSONArray("content");
                if (content == null) continue;
                StringBuilder b = new StringBuilder();
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part != null) {
                        String t = part.optString("text", "");
                        if (!t.isEmpty()) b.append(t).append('\n');
                    }
                }
                if (b.length() > 0) return b.toString();
            }
        }
        return findTextRecursive(root, 0);
    }

    private String extractBestTranscript(JSONObject root) {
        // The dedicated transcription endpoint may expose structured timing on
        // some model revisions, but output_text is the stable cross-version
        // field. R6 always guarantees a visible timeline afterward; when the
        // API does not return timecodes, those rows are explicitly marked 約.
        return extractOutputText(root);
    }

    private String findTextRecursive(Object obj, int depth) {
        if (obj == null || depth > 8) return "";
        if (obj instanceof JSONObject) {
            JSONObject o = (JSONObject) obj;
            String direct = o.optString("text", "");
            if (!direct.isEmpty()) return direct;
            JSONArray names = o.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i);
                    String found = findTextRecursive(o.opt(key), depth + 1);
                    if (!found.isEmpty()) return found;
                }
            }
        } else if (obj instanceof JSONArray) {
            JSONArray a = (JSONArray) obj;
            for (int i = 0; i < a.length(); i++) {
                String found = findTextRecursive(a.opt(i), depth + 1);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private String markdownText(String transcript) {
        String stem = audioName.isEmpty() ? "逐字稿" : audioName.replaceFirst("\\.[^.]+$", "");
        return "# " + stem + "_逐字稿\n\n" +
                "- 來源音檔：" + (audioName.isEmpty() ? "—" : audioName) + "\n" +
                "- 辨識引擎：" + lastEngineLabel + "\n" +
                "- 文字：繁體中文（台灣）\n" +
                (lastTimelineEstimated ? "- 時間：部分／全部為依音訊長度估算\n" : "- 時間：引擎時間軸\n") +
                "\n## 時間軸逐字稿\n\n" + transcript + "\n";
    }

    private String geminiLanguageCode() {
        switch (languageSpinner.getSelectedItemPosition()) {
            case 1: return "zh-TW";
            case 2: return "en-US";
            case 3: return "ja-JP";
            default: return "";
        }
    }

    private String whisperLanguageCode() {
        switch (languageSpinner.getSelectedItemPosition()) {
            case 1: return "zh";
            case 2: return "en";
            case 3: return "ja";
            default: return "";
        }
    }

    private String safeMime(Uri uri) {
        String m = getContentResolver().getType(uri);
        if (m == null || m.trim().isEmpty()) {
            String n = displayName(uri).toLowerCase(Locale.ROOT);
            if (n.endsWith(".m4a")) return "audio/m4a";
            if (n.endsWith(".mp3")) return "audio/mpeg";
            if (n.endsWith(".wav")) return "audio/wav";
            if (n.endsWith(".aac")) return "audio/aac";
            if (n.endsWith(".flac")) return "audio/flac";
            if (n.endsWith(".ogg")) return "audio/ogg";
            if (n.endsWith(".opus")) return "audio/opus";
            if (n.endsWith(".webm")) return "audio/webm";
            if (n.endsWith(".aiff") || n.endsWith(".aif")) return "audio/aiff";
            if (n.endsWith(".mp4")) return "audio/mp4";
            return "application/octet-stream";
        }
        return m;
    }

    private String displayName(Uri uri) {
        String name = "audio";
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        return name == null ? "audio" : name;
    }


    private void setApiKeyExpanded(boolean expanded) {
        apiKeyExpanded = expanded;
        runOnUiThread(() -> {
            if (apiKeyViews != null) {
                for (View v : apiKeyViews) if (v != null) v.setVisibility(expanded ? View.VISIBLE : View.GONE);
            }
            if (apiKeyToggleButton != null) {
                boolean hasKey = apiKey != null && !apiKey.getText().toString().trim().isEmpty();
                apiKeyToggleButton.setText(hasKey
                        ? (expanded ? "Gemini API Key｜已安全記憶 ▲" : "Gemini API Key｜已安全記憶 ▶")
                        : (expanded ? "Gemini API Key｜未設定 ▲" : "Gemini API Key｜未設定 ▶"));
            }
        });
    }

    private void collapseApiKeySoon() {
        runOnUiThread(() -> {
            if (apiKeyToggleButton != null) {
                apiKeyToggleButton.post(() -> {
                    try { setApiKeyExpanded(false); } catch (Throwable ignored) {}
                });
            }
        });
    }

    private static final Pattern TIMELINE_START = Pattern.compile(
            "^\\s*\\[(?:約\\s*|~\\s*)?(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:\\s*[–-]\\s*\\d{1,2}:\\d{2}(?::\\d{2})?)?\\]\\s*(.*)$");

    private String formatTranscriptForOutput(String transcript, long durationMs) {
        String text = transcript == null ? "" : transcript.trim();
        text = TraditionalChineseConverter.toTraditionalTaiwan(text);
        if (text.isEmpty()) return text;
        if (hasTimeline(text)) {
            lastTimelineEstimated = text.contains("[約 ") || text.contains("[~");
            return normalizeTimeline(text);
        }
        lastTimelineEstimated = true;
        return buildEstimatedTimeline(text, durationMs);
    }

    private boolean hasTimeline(String transcript) {
        if (transcript == null) return false;
        for (String line : transcript.replace("\r\n", "\n").replace('\r','\n').split("\n")) {
            if (TIMELINE_START.matcher(line.trim()).matches()) return true;
        }
        return false;
    }

    private String buildEstimatedTimeline(String transcript, long durationMs) {
        String clean = transcript == null ? "" : transcript.trim();
        if (clean.isEmpty()) return clean;
        List<String> parts = new ArrayList<>();
        String[] lines = clean.replace("\r\n","\n").replace('\r','\n').split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (lines.length == 1 && t.length() > 120) {
                String[] sentences = t.split("(?<=[。！？!?；;])\\s*");
                for (String sentence : sentences) if (!sentence.trim().isEmpty()) parts.add(sentence.trim());
            } else {
                parts.add(t);
            }
        }
        if (parts.isEmpty()) parts.add(clean);
        long total = durationMs > 0 ? durationMs : Math.max(5000L, parts.size() * 5000L);
        StringBuilder out = new StringBuilder();
        for (int i=0;i<parts.size();i++) {
            long at = parts.size() == 1 ? 0L : (total * i / parts.size());
            if (out.length() > 0) out.append('\n');
            out.append("[約 ").append(formatClock(at)).append("] ").append(parts.get(i));
        }
        return out.toString();
    }

    private String normalizeTimeline(String transcript) {
        if (transcript == null) return "";
        String[] lines = transcript.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<TimedLine> blocks = new ArrayList<>();
        StringBuilder current = null;
        long currentSeconds = -1L;
        StringBuilder prefix = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (current != null && current.length() > 0) current.append('\n');
                continue;
            }
            Matcher m = TIMELINE_START.matcher(t);
            if (m.matches()) {
                if (current != null) blocks.add(new TimedLine(currentSeconds, current.toString().trim()));
                long a = Long.parseLong(m.group(1));
                long b = Long.parseLong(m.group(2));
                String c = m.group(3);
                currentSeconds = c == null ? a * 60L + b : a * 3600L + b * 60L + Long.parseLong(c);
                current = new StringBuilder(t);
            } else if (current != null) {
                current.append('\n').append(t);
            } else {
                if (prefix.length() > 0) prefix.append('\n');
                prefix.append(t);
            }
        }
        if (current != null) blocks.add(new TimedLine(currentSeconds, current.toString().trim()));
        if (blocks.size() < 2) return transcript.trim();
        Collections.sort(blocks, Comparator.comparingLong(x -> x.seconds));
        StringBuilder out = new StringBuilder();
        if (prefix.length() > 0) out.append(prefix).append("\n\n");
        for (TimedLine block : blocks) {
            if (out.length() > 0 && !out.toString().endsWith("\n\n")) out.append("\n\n");
            out.append(block.line);
        }
        return out.toString().trim();
    }

    private static final class TimedLine {
        final long seconds;
        final String line;
        TimedLine(long seconds, String line) { this.seconds = seconds; this.line = line; }
    }

    private synchronized void beginNewSessionStamp() {
        if (!sessionDate.isEmpty() && !sessionStamp.isEmpty()) return;
        Date now = new Date();
        sessionDate = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now);
        sessionStamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(now);
        runOnUiThread(() -> {
            if (saveFolderHint != null) {
                saveFolderHint.setText("本次資料夾：Documents/Junba AI Transcriber/" + sessionDate + "/" + sessionStamp + "/");
            }
        });
    }

    private void saveSessionBundle() {
        String transcript = result == null ? lastTranscript : result.getText().toString().trim();
        if (transcript.isEmpty()) transcript = lastTranscript;
        if ((transcript == null || transcript.trim().isEmpty()) && !transcriptionJobActive) {
            toast("目前沒有可儲存的逐字稿");
            return;
        }
        final String snapshot = transcript == null ? "" : transcript;
        settlementExecutor.submit(() -> writeBundleSafely("手動", snapshot));
        toast("正在整理原音檔＋逐字稿＋字幕＋KTV HTML…");
    }

    private void writeBundleSafely(String label, String transcript) {
        try {
            beginNewSessionStamp();
            String body = transcript == null ? "" : transcript.trim();
            if (body.isEmpty()) body = "【" + label + "】目前尚未取得可用逐字稿文字；已保留本次工作紀錄與原始音檔。";
            body = formatTranscriptForOutput(body, currentAudioDurationMs);
            lastTranscript = body;
            persistLastResult(body);

            final String base = audioName.isEmpty() ? "逐字稿" : audioName.replaceFirst("\\.[^.]+$", "");
            final String relative = Environment.DIRECTORY_DOCUMENTS + "/Junba AI Transcriber/" + sessionDate + "/" + sessionStamp + "/";
            String tag;
            if ("完成".equals(label)) tag = "";
            else if ("中止".equals(label)) tag = "_中止版";
            else if ("暫停".equals(label)) tag = "_暫停_" + new SimpleDateFormat("HHmmss", Locale.ROOT).format(new Date());
            else tag = "_" + label + "_" + new SimpleDateFormat("HHmmss", Locale.ROOT).format(new Date());
            String stem = base + tag + "_逐字稿";

            if (!sessionOriginalSaved && audioUri != null) {
                runOnUiThread(() -> setStage("整理輸出：複製原始音檔…", false));
                saveOriginalAudio(relative);
                sessionOriginalSaved = true;
            }
            String txt = body + "\n";
            String md = markdownText(body);
            String vtt = KtvHtmlBuilder.toVtt(body);
            String html = KtvHtmlBuilder.build(stem, body, audioName);

            saveTextPublic(relative, stem + ".txt", "text/plain", txt);
            saveTextPublic(relative, stem + ".md", "text/markdown", md);
            saveTextPublic(relative, stem + "_字幕.vtt", "text/vtt", vtt);
            Uri htmlUri = saveTextPublic(relative, stem + "_錄音核對.html", "text/html", html);
            if (htmlUri != null) {
                lastKtvUri = htmlUri;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("r6_last_ktv_uri", htmlUri.toString()).apply();
            }
            final String saved = relative;
            runOnUiThread(() -> {
                if (saveFolderHint != null) saveFolderHint.setText(
                        "已結算：" + saved + "\n原始音檔：" + audioName + "（檔名不更動）\n包含 TXT / Markdown / VTT / KTV HTML");
                if (ktvButton != null) ktvButton.setEnabled(lastKtvUri != null);
            });
            DiagnosticApplication.writeStage(this, "R6:BUNDLE_SAVED label=" + label);
        } catch (Throwable e) {
            DiagnosticApplication.writeCrashReport(this, e, "R6 writeBundleSafely " + label);
            setStage("結算檔案失敗：" + e.getClass().getSimpleName() + "：" + e.getMessage(), true);
        }
    }

    private void saveOriginalAudio(String relative) throws Exception {
        String name = audioName.isEmpty() ? "audio" : audioName;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri dst = createMediaStoreFile(relative, name, safeMime(audioUri));
            try (InputStream in = new BufferedInputStream(getContentResolver().openInputStream(audioUri));
                 OutputStream out = new BufferedOutputStream(getContentResolver().openOutputStream(dst, "w"))) {
                if (in == null || out == null) throw new Exception("無法建立原始音檔副本");
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    "Junba AI Transcriber/" + sessionDate + "/" + sessionStamp);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("無法建立資料夾");
            File dst = new File(dir, name);
            try (InputStream in = new BufferedInputStream(getContentResolver().openInputStream(audioUri));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
                if (in == null) throw new Exception("無法讀取原始音檔");
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    private Uri saveTextPublic(String relative, String name, String mime, String payload) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri dst = createMediaStoreFile(relative, name, mime);
            try (OutputStream out = getContentResolver().openOutputStream(dst, "w")) {
                if (out == null) throw new Exception("無法建立 " + name);
                out.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            return dst;
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    "Junba AI Transcriber/" + sessionDate + "/" + sessionStamp);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("無法建立資料夾");
            try (OutputStream out = new FileOutputStream(new File(dir, name))) {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        }
    }

    private void openLastKtv() {
        Uri uri = lastKtvUri;
        if (uri == null) {
            String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("r6_last_ktv_uri", "");
            if (saved != null && !saved.isEmpty()) try { uri = Uri.parse(saved); } catch (Throwable ignored) {}
        }
        if (uri == null) { toast("尚未產生 KTV HTML；先完成轉錄或按『儲存本次』"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "text/html");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable e) {
            toast("無法開啟 HTML：" + e.getMessage());
        }
    }

    private void persistLastResult(String text) {
        if (text == null) return;
        try (FileOutputStream out = openFileOutput("r6_last_transcript.txt", MODE_PRIVATE)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }

    private void restoreLastResultIfAny() {
        try {
            File f = new File(getFilesDir(), "r6_last_transcript.txt");
            if (f.isFile() && f.length() > 0 && result != null && result.length() == 0) {
                String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                if (!text.trim().isEmpty()) {
                    lastTranscript = text;
                    result.setText(text);
                    result.setSelection(0);
                }
            }
        } catch (Throwable ignored) {}
        try {
            String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("r6_last_ktv_uri", "");
            if (saved != null && !saved.isEmpty()) {
                lastKtvUri = Uri.parse(saved);
                if (ktvButton != null) ktvButton.setEnabled(true);
            }
        } catch (Throwable ignored) {}
    }

    private Uri createMediaStoreFile(String relative, String name, String mime) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,
                mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
        Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
        if (uri == null) throw new Exception("Android 無法在 Documents 建立 " + name);
        return uri;
    }

    private long querySize(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {}
        return -1L;
    }

    private void setBusy(boolean busy) {
        busyState.set(busy);
        runOnUiThread(() -> {
            if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (progressDetail != null) progressDetail.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (startButton != null) startButton.setEnabled(!busy);
            if (cancelButton != null) cancelButton.setEnabled(busy);
            if (modelButton != null) modelButton.setEnabled(!busy);
            if (pauseButton != null) pauseButton.setEnabled(busy && transcriptionJobActive && !pauseFlag.get());
            if (resumeButton != null) resumeButton.setEnabled(busy && transcriptionJobActive && pauseFlag.get());
        });
        if (busy) {
            operationStartedMs = SystemClock.elapsedRealtime();
            startHeartbeat();
        } else {
            heartbeatRunnable = null;
            pauseFlag.set(false);
            whisperModeActive = false;
            if (transcriptionJobActive) {
                transcriptionJobActive = false;
                try { TranscriptionKeepAliveService.stop(this); } catch (Throwable ignored) {}
            }
        }
    }

    private void startHeartbeat() {
        runOnUiThread(() -> {
            if (progressDetail == null) return;
            final Runnable beat = new Runnable() {
                @Override public void run() {
                    if (!busyState.get() || heartbeatRunnable != this) return;
                    long elapsed = SystemClock.elapsedRealtime() - operationStartedMs;
                    if (pauseFlag.get() && transcriptionJobActive) {
                        progressDetail.setText("已要求暫停｜目前結果已／正在結算｜已執行 " + formatElapsed(elapsed));
                    } else if (whisperModeActive && whisperInferenceStartedMs > 0) {
                        long inferElapsed = SystemClock.elapsedRealtime() - whisperInferenceStartedMs;
                        long eta = whisperEstimatedTotalMs > 0 ? Math.max(0L, whisperEstimatedTotalMs - inferElapsed) : -1L;
                        String chunks = whisperTotalChunks > 0 ? ("｜區段 " + whisperCompletedChunks + "/" + whisperTotalChunks) : "";
                        String remain = eta >= 0 ? ("｜預估剩餘 " + formatElapsed(eta)) : "｜正在建立速度估算";
                        progressDetail.setText("Whisper 背景運算" + chunks + "｜已執行 " + formatElapsed(inferElapsed) + remain);
                    } else {
                        String range = progressRangeText == null || progressRangeText.isEmpty() ? "" : "｜" + progressRangeText;
                        progressDetail.setText("仍在執行" + range + "｜已執行 " + formatElapsed(elapsed));
                    }
                    progressDetail.postDelayed(this, 1000L);
                }
            };
            heartbeatRunnable = beat;
            progressDetail.post(beat);
        });
    }

    private void setProgressState(String message, int pct, String rangeText, boolean warn) {
        int p = Math.max(0, Math.min(100, pct));
        progressRangeText = rangeText == null ? "" : rangeText;
        runOnUiThread(() -> {
            if (progress != null) { progress.setIndeterminate(false); progress.setProgress(p); }
            if (stage != null) {
                stage.setText(message);
                stage.setTextColor(warn ? 0xFFB71C1C : 0xFF1565C0);
            }
            if (progressDetail != null && busyState.get() && !whisperModeActive) {
                long elapsed = SystemClock.elapsedRealtime() - operationStartedMs;
                String range = progressRangeText.isEmpty() ? "" : "｜" + progressRangeText;
                progressDetail.setText("仍在執行" + range + "｜已執行 " + formatElapsed(elapsed));
            }
        });
    }

    private void setExactProgress(int pct, String label) {
        int p = Math.max(0, Math.min(100, pct));
        progressRangeText = p + "%";
        runOnUiThread(() -> {
            if (progress != null) { progress.setIndeterminate(false); progress.setProgress(p); }
            if (label != null && !label.isEmpty() && stage != null) stage.setText(label + "…");
        });
    }

    private long estimateWhisperTotalMs(long audioMs) {
        if (audioMs <= 0) return -1L;
        String key = "r6_rtf_" + selectedModelId();
        float fallback;
        switch (selectedModelId()) {
            case "tiny": fallback = 0.35f; break;
            case "small": fallback = 1.10f; break;
            default: fallback = 0.58f; break;
        }
        float rtf = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(key, fallback);
        return Math.max(30_000L, (long) (audioMs * Math.max(0.08f, Math.min(4.0f, rtf))));
    }

    private void updateWhisperEstimateFromChunks(int completed, int total) {
        if (completed <= 0 || total <= 0 || whisperInferenceStartedMs <= 0) return;
        long elapsed = Math.max(1L, SystemClock.elapsedRealtime() - whisperInferenceStartedMs);
        whisperEstimatedTotalMs = Math.max(elapsed, elapsed * total / completed);
    }

    private void recordWhisperSpeed() {
        if (currentAudioDurationMs <= 0 || whisperInferenceStartedMs <= 0) return;
        long elapsed = Math.max(1L, SystemClock.elapsedRealtime() - whisperInferenceStartedMs);
        float observed = Math.max(0.05f, Math.min(5.0f, (float) elapsed / (float) currentAudioDurationMs));
        String key = "r6_rtf_" + selectedModelId();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        float old = p.getFloat(key, observed);
        float blended = old * 0.60f + observed * 0.40f;
        p.edit().putFloat(key, blended).apply();
    }

    private void requestPauseAndSettle() {
        if (!transcriptionJobActive) { toast("目前沒有正在執行的轉錄"); return; }
        pauseFlag.set(true);
        pauseSettlementDone.set(true);
        setStage(whisperModeActive ? "已要求暫停：完成目前 5 分鐘區段後停住；先結算目前已完成內容。" : "已要求暫停：Gemini 會在目前網路／AI 階段完成後停住；先結算目前內容。", false);
        runOnUiThread(() -> {
            if (pauseButton != null) pauseButton.setEnabled(false);
            if (resumeButton != null) resumeButton.setEnabled(true);
        });
        final String snap = lastTranscript == null ? "" : lastTranscript;
        settlementExecutor.submit(() -> writeBundleSafely("暫停", snap));
        try { TranscriptionKeepAliveService.update(this, "轉錄已要求暫停｜正在結算目前結果"); } catch (Throwable ignored) {}
    }

    private void requestResume() {
        if (!transcriptionJobActive) return;
        pauseFlag.set(false);
        pauseSettlementDone.set(false);
        setStage("已繼續轉錄", false);
        runOnUiThread(() -> {
            if (pauseButton != null) pauseButton.setEnabled(true);
            if (resumeButton != null) resumeButton.setEnabled(false);
        });
        try { TranscriptionKeepAliveService.update(this, whisperModeActive ? "Whisper 已繼續背景辨識" : "Gemini 已繼續轉錄"); } catch (Throwable ignored) {}
    }

    private void requestStopAndSettle() {
        cancelFlag.set(true);
        pauseFlag.set(false);
        if (transcriptionJobActive) {
            setStage(whisperModeActive ? "已要求停止：完成目前安全區段後立即結算；已完成文字不會遺失。" : "已要求停止：目前 Gemini 呼叫返回後立即結算。", true);
            String snap = lastTranscript == null ? "" : lastTranscript;
            if (!snap.trim().isEmpty()) settlementExecutor.submit(() -> writeBundleSafely("停止快照", snap));
        } else {
            setStage("已要求取消目前工作", true);
        }
    }

    private boolean waitForResumeOrStop() throws InterruptedException {
        while (pauseFlag.get() && !cancelFlag.get()) Thread.sleep(250L);
        return !cancelFlag.get();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 6006); } catch (Throwable ignored) {}
        }
    }

    private void openBackgroundPowerSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                    return;
                }
            }
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Throwable ignored) { toast("無法開啟省電設定，請到系統設定手動允許背景活動"); }
        }
    }

    private static String formatElapsed(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long sec = total % 60L;
        return h > 0
                ? String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }

    private void setStage(String message, boolean warn) {
        runOnUiThread(() -> {
            if (stage != null) {
                stage.setText(message);
                stage.setTextColor(warn ? 0xFFB71C1C : 0xFF1565C0);
            }
        });
    }

    private TextView sectionLabel(String s) {
        TextView t = text(s, 16, true);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(0xFF17202A);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        b.setMinHeight(dp(46));
        return b;
    }

    private View adaptiveButtonRow(Button... buttons) {
        boolean narrow = getResources().getConfiguration().screenWidthDp < 600;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(2));
        for (Button b : buttons) row.addView(b, narrow ? lpMatch() : lpWeight());
        return row;
    }

    private LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams lpWeight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }
    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }
    private static String shortText(String s, int max) { return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…"); }

    @Override
    public void onBackPressed() {
        if (transcriptionJobActive) {
            moveTaskToBack(true);
            toast("R6 仍在背景轉錄；可從通知或 App 圖示回來查看。");
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        // R6: do not cancel an active foreground transcription merely because
        // the Activity is backgrounded/removed by an OEM. The foreground
        // service + wake lock keeps the process alive until the job settles.
        if (!transcriptionJobActive) {
            cancelFlag.set(true);
            busyState.set(false);
            heartbeatRunnable = null;
            executor.shutdownNow();
            settlementExecutor.shutdown();
        }
        super.onDestroy();
    }
}
