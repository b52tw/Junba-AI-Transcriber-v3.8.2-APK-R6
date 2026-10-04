package tw.junba.transcriber;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * R6 safe shell.
 *
 * It deliberately contains no ScrollView/EditText scrollbar, Whisper, Gemini,
 * MediaCodec, or native-engine reference. The real transcriber runs in a
 * separate process. On normal startup this shell immediately opens it. If that
 * process crashes, the shell remains alive and exposes the captured diagnostic.
 */
public final class MainActivity extends Activity {
    private TextView status;
    private TextView detail;
    private boolean launchAttempted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshStatus();
        getWindow().getDecorView().postDelayed(() -> {
            if (!isFinishing() && !launchAttempted) openFull(true);
        }, 350L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) refreshStatus();
    }

    private LinearLayout buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP);
        root.setPadding(pad, dp(24), pad, dp(24));

        root.addView(text("峻爸 AI Transcriber v3.8.2 R6", 22, true), match());
        TextView intro = text(
                "正在啟動完整轉錄功能。R6 已針對 Android 16 / vivo 的 ScrollBarDrawable 啟動閃退修正，並加入背景續跑／繁體時間軸／KTV HTML。\n\n" +
                "正常情況下這一頁只會短暫出現；若完整功能異常返回，這裡會保留診斷資訊。",
                14, false);
        intro.setPadding(0, dp(8), 0, dp(12));
        root.addView(intro, match());

        status = text("準備啟動…", 17, true);
        status.setTextColor(0xFF1565C0);
        root.addView(status, match());

        detail = text("", 12, false);
        detail.setTextIsSelectable(true);
        detail.setPadding(0, dp(8), 0, dp(12));
        root.addView(detail, match());

        Button retry = button("重新開啟完整轉錄功能");
        retry.setOnClickListener(v -> openFull(false));
        root.addView(retry, match());

        Button copy = button("複製最近診斷");
        copy.setOnClickListener(v -> copyDiagnostic());
        root.addView(copy, match());

        TextView device = text(
                "裝置：" + Build.MANUFACTURER + " " + Build.MODEL +
                "｜Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")" +
                "｜ABI " + String.join(",", Build.SUPPORTED_ABIS),
                11, false);
        device.setPadding(0, dp(12), 0, 0);
        root.addView(device, match());
        return root;
    }

    private void openFull(boolean automatic) {
        launchAttempted = true;
        try {
            DiagnosticApplication.clearDiagnostics(this);
            DiagnosticApplication.writeStage(this, automatic ? "R6_SHELL:auto launch full" : "R6_SHELL:manual launch full");
            status.setText("正在開啟完整轉錄功能…");
            status.setTextColor(0xFF1565C0);
            detail.setText("");

            Intent intent = new Intent();
            intent.setClassName(getPackageName(), "tw.junba.transcriber.FullTranscriberActivity");
            startActivity(intent);
        } catch (Throwable t) {
            DiagnosticApplication.writeCrashReport(this, t, "R6 shell start FullTranscriberActivity");
            refreshStatus();
        }
    }

    private void refreshStatus() {
        String crash = DiagnosticApplication.readDiagnosticFile(this, DiagnosticApplication.CRASH_FILE);
        String stage = DiagnosticApplication.readDiagnosticFile(this, DiagnosticApplication.STAGE_FILE);
        if (!crash.isEmpty()) {
            status.setText("完整轉錄功能發生錯誤；已保留診斷");
            status.setTextColor(0xFFB71C1C);
            detail.setText(shorten(crash, 4200));
        } else if (stage.contains("FULL:READY")) {
            status.setText("完整轉錄功能已正常啟動");
            status.setTextColor(0xFF0B7A34);
            detail.setText("");
        } else if (!stage.isEmpty()) {
            status.setText("最近啟動階段：" + oneLineStage(stage));
            status.setTextColor(0xFF1565C0);
            detail.setText("");
        } else {
            status.setText("R6 安全啟動層：正常");
            status.setTextColor(0xFF0B7A34);
            detail.setText("");
        }
    }

    private void copyDiagnostic() {
        String stage = DiagnosticApplication.readDiagnosticFile(this, DiagnosticApplication.STAGE_FILE);
        String crash = DiagnosticApplication.readDiagnosticFile(this, DiagnosticApplication.CRASH_FILE);
        String s = "=== LAST STAGE ===\n" + stage + "\n\n=== LAST CRASH ===\n" + crash;
        if (stage.isEmpty() && crash.isEmpty()) {
            toast("目前沒有診斷內容");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Junba R6 diagnostic", s));
            toast("診斷內容已複製");
        }
    }

    private static String oneLineStage(String s) {
        if (s == null) return "";
        for (String line : s.split("\\R")) {
            if (line.startsWith("processStage=")) return line.substring("processStage=".length());
        }
        return s.replace('\n', ' ').trim();
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFF17202A);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        return b;
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
