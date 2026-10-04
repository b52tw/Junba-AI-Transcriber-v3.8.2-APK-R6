package tw.junba.transcriber;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * R6 crash/stage recorder.  File-based diagnostics are authoritative because
 * Android SharedPreferences are not a reliable cross-process live transport.
 */
public final class DiagnosticApplication extends Application {
    public static final String PREFS = "junba_r6_diag";
    public static final String KEY_LAST_CRASH = "last_crash";
    public static final String KEY_LAST_CRASH_AT = "last_crash_at";
    public static final String CRASH_FILE = "r6_last_crash.txt";
    public static final String STAGE_FILE = "r6_last_stage.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        writeStage(this, "APPLICATION:onCreate pid=" + Process.myPid());
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashReport(this, throwable, "UNCAUGHT thread=" + thread.getName());
                writeStage(this, "UNCAUGHT:" + throwable.getClass().getName());
            } catch (Throwable ignored) {
                // Never mask the original crash.
            }

            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    public static void writeStage(Context context, String stage) {
        if (context == null) return;
        try {
            String line = now() + "\n" +
                    "pid=" + Process.myPid() + "\n" +
                    "processStage=" + String.valueOf(stage) + "\n" +
                    "device=" + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                    "android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT + "\n";
            writeFile(context, STAGE_FILE, line);
        } catch (Throwable ignored) {}
    }

    public static void writeCrashReport(Context context, Throwable throwable, String source) {
        if (context == null || throwable == null) return;
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("Junba R6 Diagnostic crash report");
            pw.println("time=" + now());
            pw.println("source=" + String.valueOf(source));
            pw.println("pid=" + Process.myPid() + " thread=" + Thread.currentThread().getName());
            pw.println("device=" + Build.MANUFACTURER + " " + Build.MODEL);
            pw.println("android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT);
            pw.println("abis=" + String.join(",", Build.SUPPORTED_ABIS));
            pw.println();
            throwable.printStackTrace(pw);
            pw.flush();
            String report = sw.toString();

            // File is the primary cross-process transport.
            writeFile(context, CRASH_FILE, report);

            // Keep preferences as a secondary fallback only.
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_LAST_CRASH, report)
                    .putLong(KEY_LAST_CRASH_AT, System.currentTimeMillis())
                    .commit();
        } catch (Throwable ignored) {}
    }

    public static String readDiagnosticFile(Context context, String fileName) {
        if (context == null) return "";
        File f = new File(context.getFilesDir(), fileName);
        if (!f.isFile()) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
            return b.toString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static void clearDiagnostics(Context context) {
        if (context == null) return;
        try { new File(context.getFilesDir(), CRASH_FILE).delete(); } catch (Throwable ignored) {}
        try { new File(context.getFilesDir(), STAGE_FILE).delete(); } catch (Throwable ignored) {}
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        } catch (Throwable ignored) {}
    }

    private static void writeFile(Context context, String name, String text) throws Exception {
        File f = new File(context.getFilesDir(), name);
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
    }
}
