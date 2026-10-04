package tw.junba.transcriber;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground keep-alive for long local transcription.
 * It keeps a partial CPU wake lock while a transcription job is active so
 * screen-off / battery saver is less likely to suspend Whisper mid-run.
 */
public final class TranscriptionKeepAliveService extends Service {
    private static final String CHANNEL_ID = "junba_transcription_r6";
    private static final int NOTIFICATION_ID = 6006;
    private static final String EXTRA_TEXT = "text";
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context, String text) {
        Intent i = new Intent(context, TranscriptionKeepAliveService.class);
        i.putExtra(EXTRA_TEXT, text == null ? "正在轉錄" : text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void update(Context context, String text) {
        start(context, text);
    }

    public static void stop(Context context) {
        try { context.stopService(new Intent(context, TranscriptionKeepAliveService.class)); }
        catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Junba:TranscriptionR6");
                wakeLock.setReferenceCounted(false);
                // Safety cap: Android itself also limits long foreground media jobs.
                wakeLock.acquire(6L * 60L * 60L * 1000L);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent == null ? "正在轉錄" : intent.getStringExtra(EXTRA_TEXT);
        startForeground(NOTIFICATION_ID, buildNotification(text == null ? "正在轉錄" : text));
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, FullTranscriberActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("峻爸 AI Transcriber R6")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "長時間轉錄", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Whisper / Gemini 長時間背景轉錄狀態");
        nm.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
