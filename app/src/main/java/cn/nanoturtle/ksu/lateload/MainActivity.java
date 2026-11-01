package cn.nanoturtle.ksu.lateload;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * KSU LateLoad：已有临时 root（CVE-2026-43499 helper 守护进程在跑）时，
 * 通过 Shizuku 获得 shell(uid 2000) 权限执行 /data/local/tmp/rms24/helper --late-load。
 */
public class MainActivity extends Activity {

    private static final int SHIZUKU_REQ = 0x5352;
    private static final String HELPER = "/data/local/tmp/rms24/helper";

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView log;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_REQ) {
                    boolean ok = grantResult == PackageManager.PERMISSION_GRANTED;
                    appendLog("Shizuku 授权结果: " + (ok ? "已授权 ✓" : "被拒绝 ✗"));
                    ui.post(this::refreshStatus);
                }
            };

    private final Shizuku.OnBinderReceivedListener binderListener = () -> {
        appendLog("Shizuku binder 已连接");
        maybeRequestPermission();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        // 注意：addBinderReceivedListenerSticky 在 binder 已就绪时会「立即回调」，
        // 所以必须放在 setContentView 之后，否则 log TextView 还是 null（曾导致启动崩溃）。
        Shizuku.addRequestPermissionResultListener(permListener);
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        maybeRequestPermission();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener);
        Shizuku.removeBinderReceivedListener(binderListener);
        pool.shutdownNow();
        super.onDestroy();
    }

    // ---------------- Shizuku 基础 ----------------

    private boolean isRunning() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    private boolean isGranted() {
        try {
            return isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    /** 等 binder 就绪（最多 timeoutMillis），就绪后自动申请权限。 */
    private void maybeRequestPermission() {
        pool.execute(() -> {
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (SystemClock.elapsedRealtime() < deadline && !isRunning()) {
                sleep(100);
            }
            if (!isRunning()) {
                appendLog("Shizuku 未运行：请先在手机上启动 Shizuku（无线调试或 root 启动）");
                refreshStatus();
                return;
            }
            if (isGranted()) {
                appendLog("Shizuku 已授权 ✓");
                refreshStatus();
                return;
            }
            try {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    appendLog("Shizuku 权限被永久拒绝：请到 Shizuku App → 授权应用里手动允许本应用");
                    return;
                }
                appendLog("正在申请 Shizuku 权限（手机应弹出授权框）…");
                // Shizuku 的授权对话框由管理器 Activity 弹出：必须从主线程发起，
                // 否则请求可能被自动处理、不进入管理器的「授权应用」列表。
                ui.post(() -> {
                    try {
                        Shizuku.requestPermission(SHIZUKU_REQ);
                    } catch (Throwable t) {
                        appendLog("申请权限异常: " + t);
                    }
                });
            } catch (Throwable t) {
                appendLog("申请权限异常: " + t);
            }
        });
    }

    private IShizukuService service() {
        return IShizukuService.Stub.asInterface(Shizuku.getBinder());
    }

    /** 以 shell 权限执行命令，返回 "输出 + 退出码"。 */
    private String run(String cmd) {
        StringBuilder out = new StringBuilder();
        out.append("$ ").append(cmd.length() > 180 ? cmd.substring(0, 180) + "…" : cmd).append('\n');
        IRemoteProcess p = null;
        try {
            p = service().newProcess(new String[]{"/system/bin/sh", "-c", cmd}, null, null);
            final InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(p.getInputStream());
            final InputStream err = new ParcelFileDescriptor.AutoCloseInputStream(p.getErrorStream());
            final StringBuilder sb = new StringBuilder(), eb = new StringBuilder();
            Thread t1 = new Thread(() -> sb.append(readAll(in, "out")));
            Thread t2 = new Thread(() -> eb.append(readAll(err, "err")));
            t1.start(); t2.start();
            t1.join(); t2.join();
            int rc = p.waitFor();
            out.append(sb).append(eb);
            if (rc != 0) out.append("(exit ").append(rc).append(")\n");
        } catch (Throwable t) {
            out.append("执行异常: ").append(t).append('\n');
        } finally {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
        }
        return out.toString();
    }

    private static String readAll(InputStream is, String tag) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append("err".equals(tag) ? "[err] " : "").append(line).append('\n');
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    // ---------------- 动作 ----------------

    private void refreshStatus() {
        pool.execute(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Shizuku ===\n").append(isRunning() ? "binder ✓" : "binder ✗")
              .append(isGranted() ? " / 已授权 ✓\n" : " / 未授权 ✗\n");
            if (isGranted()) {
                sb.append("\n=== Shizuku shell 身份 ===\n").append(run("id"));
                sb.append("\n=== helper / root ===\n").append(run(HELPER + " -c 'id; getenforce'"));
                sb.append("\n=== KernelSU ===\n").append(run("cat /proc/modules | grep -i kernelsu || echo not-loaded"));
                sb.append("\n=== 文件 ===\n").append(run("ls -l " + HELPER + " /data/local/tmp/rms24/ksud /data/local/tmp/.ksud-stage 2>&1"));
            }
            final String out = sb.toString();
            ui.post(() -> {
                status.setText(isGranted() ? "Shizuku ✓ 已授权（可 late-load）" : "Shizuku ✗ 未就绪（见日志）");
                appendLog(out);
            });
        });
    }

    private void doLateLoad() {
        pool.execute(() -> {
            if (!isGranted()) {
                appendLog("Shizuku 未授权，先点“申请 Shizuku 权限”。");
                return;
            }
            appendLog("=== 开始 late-load ===");
            String probe = run(HELPER + " -c 'id' 2>&1");
            appendLog(probe);
            if (!probe.contains("uid=0")) {
                appendLog("\n❌ 临时 root 未就绪：请在电脑上用 43499 流程提权（或重启后重跑一次 exploit）\n"
                        + "   helper 报错通常是 'su: connect daemon: Permission denied'，\n"
                        + "   说明当前这次开机还没有 root 守护进程。");
                ui.post(() -> status.setText("late-load 未执行：临时 root 未就绪"));
                return;
            }
            appendLog(run("cp -f /data/local/tmp/rms24/ksud /data/local/tmp/.ksud-stage 2>&1; chmod 755 /data/local/tmp/.ksud-stage 2>&1; echo staged"));
            appendLog(run(HELPER + " --late-load 2>&1"));
            appendLog("=== 验证 ===");
            appendLog(run("cat /proc/modules | grep -i kernelsu; echo selinux=$(getenforce); getenforce"));
            ui.post(() -> status.setText("late-load 已执行，见日志"));
        });
    }

    // ---------------- UI ----------------

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("KSU LateLoad");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("前提：Shizuku 运行中 + 43499 临时 root 就绪（/data/local/tmp/rms24/helper）");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(Color.GRAY);
        root.addView(sub);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button check = new Button(this);
        check.setText("检查状态");
        check.setOnClickListener(v -> refreshStatus());
        buttons.addView(check, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button late = new Button(this);
        late.setText("Late-load KernelSU");
        late.setOnClickListener(v -> doLateLoad());
        buttons.addView(late, new LinearLayout.LayoutParams(0, dp(48), 1.2f));
        root.addView(buttons);

        Button grant = new Button(this);
        grant.setText("申请 Shizuku 权限");
        grant.setOnClickListener(v -> maybeRequestPermission());
        root.addView(grant, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        ScrollView scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        log.setTypeface(Typeface.MONOSPACE);
        scroll.addView(log);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        root.addView(scroll, lp);
        // UI 已就绪，flush 之前缓存的日志
        if (pendingLog.length() > 0) {
            log.append(pendingLog);
            pendingLog.setLength(0);
        }
        return root;
    }

    private final StringBuilder pendingLog = new StringBuilder();

    private void appendLog(String s) {
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post(() -> appendLog(s)); return; }
        if (log == null) { // UI 还没建好：先缓存，setContentView 后 flush
            pendingLog.append(s);
            return;
        }
        if (pendingLog.length() > 0) { log.append(pendingLog); pendingLog.setLength(0); }
        log.append(s);
        if (log.length() > 200_000) log.setText(log.getText().subSequence(log.length() - 120_000, log.length()));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }
}
