package com.ttangja.purpleplayer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 접근성 서비스: 매크로 동작을 화면 터치로 재생한다. */
public class PlayerService extends AccessibilityService {
    static PlayerService I;
    static Runnable onState; // 메인 화면 갱신용

    private Handler h;
    private WindowManager wm;
    private LinearLayout bar;
    private TextView barText;
    private View dots;

    private Macro macro;
    private boolean playing;
    private long loopStart; // uptime 기준
    private int idx, loop;
    private float offX, offY, scale = 1f;

    private final Runnable stepR = this::step;
    private final Runnable tickR = this::tick;

    @Override
    protected void onServiceConnected() {
        I = this;
        h = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        loadCalib();
        if (onState != null) onState.run();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        stop();
        hideDots();
        I = null;
        if (onState != null) onState.run();
        super.onDestroy();
    }

    boolean isPlaying() { return playing; }

    void loadCalib() {
        SharedPreferences p = getSharedPreferences("pp", MODE_PRIVATE);
        offX = p.getFloat("offX", 0f);
        offY = p.getFloat("offY", 0f);
        scale = p.getFloat("scale", 100f) / 100f;
        if (scale <= 0.1f) scale = 1f;
    }

    // ───────── 재생 ─────────
    /** startDelayMs 뒤에 처음부터 재생 */
    void start(Macro m, long startDelayMs) {
        stop();
        if (m == null || m.acts.isEmpty()) return;
        loadCalib();
        macro = m;
        playing = true;
        idx = 0;
        loop = 0;
        loopStart = SystemClock.uptimeMillis() + Math.max(0, startDelayMs);
        showBar();
        h.postAtTime(stepR, loopStart + macro.acts.get(0).t);
        h.post(tickR);
        if (onState != null) onState.run();
    }

    void stop() {
        if (h != null) { h.removeCallbacks(stepR); h.removeCallbacks(tickR); }
        playing = false;
        hideBar();
        if (onState != null) onState.run();
    }

    private void step() {
        if (!playing) return;
        long now = SystemClock.uptimeMillis();
        List<Macro.Act> acts = macro.acts;
        if (idx >= acts.size()) {
            long end = loopStart + macro.totalMs;
            if (now < end - 5) { h.postAtTime(stepR, end); return; }
            loop++;
            if (macro.repeat != -1 && loop >= macro.repeat) { stop(); return; }
            loopStart = now + macro.intervalMs;
            idx = 0;
            h.postAtTime(stepR, loopStart + acts.get(0).t);
            return;
        }
        Macro.Act a = acts.get(idx);
        long due = loopStart + a.t;
        if (now < due - 5) { h.postAtTime(stepR, due); return; }

        if (a.kind == Macro.BACK) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            idx++;
        } else {
            // 겹치는 터치는 한 번에(멀티터치) 보낸다
            List<Macro.Act> cl = new ArrayList<>();
            long cStart = a.t, cEnd = a.t + a.dur;
            int j = idx;
            while (j < acts.size() && cl.size() < 10) {
                Macro.Act b = acts.get(j);
                if (b.kind != Macro.TOUCH || b.t >= cEnd) break;
                if (b.t + b.dur - cStart > 58_000) break;
                cl.add(b);
                cEnd = Math.max(cEnd, b.t + b.dur);
                j++;
            }
            if (cl.isEmpty()) { cl.add(a); j = idx + 1; }
            dispatch(cl, cStart);
            idx = j;
        }
        if (idx < acts.size()) h.postAtTime(stepR, loopStart + acts.get(idx).t);
        else h.postAtTime(stepR, loopStart + macro.totalMs);
    }

    private void dispatch(List<Macro.Act> cl, long cStart) {
        try {
            Rect g = gameRect();
            GestureDescription.Builder gb = new GestureDescription.Builder();
            for (Macro.Act b : cl) {
                Path p = new Path();
                float[] q = map(g, b.xs[0], b.ys[0]);
                p.moveTo(q[0], q[1]);
                for (int i = 1; i < b.xs.length; i++) {
                    float[] r = map(g, b.xs[i], b.ys[i]);
                    p.lineTo(r[0], r[1]);
                }
                gb.addStroke(new GestureDescription.StrokeDescription(p, b.t - cStart, b.dur));
            }
            dispatchGesture(gb.build(), null, null);
        } catch (Exception ignored) {
        }
    }

    /** 실제 화면 안에서 16:9 게임 영역 (위아래 검은 띠 제외) */
    private Rect gameRect() {
        int w, hh;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            w = b.width(); hh = b.height();
        } else {
            Point pt = new Point();
            wm.getDefaultDisplay().getRealSize(pt);
            w = pt.x; hh = pt.y;
        }
        int W = Math.max(w, hh), H = Math.min(w, hh); // 게임은 가로 화면
        float gw = Math.min(W, H * 16f / 9f), gh = gw * 9f / 16f;
        int ox = Math.round((W - gw) / 2f), oy = Math.round((H - gh) / 2f);
        return new Rect(ox, oy, ox + Math.round(gw), oy + Math.round(gh));
    }

    private float[] map(Rect g, float nx, float ny) {
        float x = g.left + g.width() * (0.5f + (nx - 0.5f) * scale) + offX;
        float y = g.top + g.height() * (0.5f + (ny - 0.5f) * scale) + offY;
        int W = g.left * 2 + g.width(), H = g.top * 2 + g.height();
        x = Math.max(1, Math.min(W - 2, x));
        y = Math.max(1, Math.min(H - 2, y));
        return new float[]{x, y};
    }

    // ───────── 떠 있는 막대 (맨 위 가운데, 검은 띠 위) ─────────
    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void showBar() {
        if (bar != null) return;
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6182120);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFF2C7C76);
        bar.setBackground(bg);
        bar.setPadding(dp(10), 0, dp(4), 0);
        bar.setKeepScreenOn(true);

        barText = new TextView(this);
        barText.setTextColor(0xFFEEF3F2);
        barText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bar.addView(barText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView stopB = new TextView(this);
        stopB.setText("■ 정지");
        stopB.setTextColor(0xFF0B1F1D);
        stopB.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        stopB.setGravity(Gravity.CENTER);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0xFF5FBFB5);
        sb.setCornerRadius(dp(10));
        stopB.setBackground(sb);
        stopB.setPadding(dp(10), dp(3), dp(10), dp(3));
        stopB.setOnClickListener(v -> stop());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(8);
        bar.addView(stopB, slp);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(30),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(2);
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        // 글자 부분을 끌어서 옮길 수 있음
        barText.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int lx, ly;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: sx = e.getRawX(); sy = e.getRawY(); lx = lp.x; ly = lp.y; return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = lx + Math.round(e.getRawX() - sx);
                        lp.y = ly + Math.round(e.getRawY() - sy);
                        try { wm.updateViewLayout(bar, lp); } catch (Exception ignored) {}
                        return true;
                }
                return false;
            }
        });
        try { wm.addView(bar, lp); } catch (Exception e) { bar = null; }
    }

    private void hideBar() {
        if (bar != null) { try { wm.removeView(bar); } catch (Exception ignored) {} }
        bar = null;
        barText = null;
    }

    private void tick() {
        if (!playing || barText == null) return;
        long now = SystemClock.uptimeMillis();
        String s;
        if (now < loopStart) {
            s = "시작까지 " + Macro.clock(loopStart - now + 999);
        } else {
            String rep = macro.repeat == -1 ? " · " + (loop + 1) + "회차" : (macro.repeat > 1 ? " · " + (loop + 1) + "/" + macro.repeat : "");
            s = "▶ " + Macro.clock(now - loopStart) + " / " + Macro.clock(macro.totalMs) + rep;
        }
        barText.setText(s);
        h.postDelayed(tickR, 1000);
    }

    // ───────── 좌표 점 확인 ─────────
    void showDots(long ms) {
        hideDots();
        loadCalib();
        final Rect g = gameRect();
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint frame = new Paint();
        fill.setColor(0xCC5FBFB5);
        ring.setColor(Color.WHITE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(1.5f));
        txt.setColor(Color.WHITE);
        txt.setTextSize(dp(11));
        txt.setShadowLayer(dp(2), 0, 0, Color.BLACK);
        frame.setColor(0xFFFFC857);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(dp(2));
        dots = new View(this) {
            @Override protected void onDraw(Canvas c) {
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                c.translate(-loc[0], -loc[1]);
                c.drawRect(g, frame);
                float r = dp(7);
                for (Map.Entry<Integer, float[]> e : KeyMap.POS.entrySet()) {
                    String n = KeyMap.NAME.get(e.getKey());
                    if (n == null || n.isEmpty()) continue;
                    float[] q = map(g, e.getValue()[0], e.getValue()[1]);
                    c.drawCircle(q[0], q[1], r, fill);
                    c.drawCircle(q[0], q[1], r, ring);
                    c.drawText(KeyMap.keyName(e.getKey()) + " " + n, q[0] + r + dp(3), q[1] + dp(4), txt);
                }
            }
        };
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try { wm.addView(dots, lp); } catch (Exception e) { dots = null; return; }
        h.postDelayed(this::hideDots, ms);
    }

    void hideDots() {
        if (dots != null) { try { wm.removeView(dots); } catch (Exception ignored) {} }
        dots = null;
    }
}
