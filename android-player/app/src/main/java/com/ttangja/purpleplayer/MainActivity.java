package com.ttangja.purpleplayer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int PICK = 7;
    private static final int C_ACC = 0xFF2C7C76, C_FG = 0xFF1E2124, C_MUTED = 0xFF5F656B, C_BG = 0xFFF3F7F6;

    private static String json;          // 불러온 파일 내용
    private static List<String[]> recs;  // [key, title]
    static Macro macro;
    private static String fileName = "";

    private TextView accState, info, fileInfo, guardState, logView;
    private Spinner recSpin;
    private EditText startAt, offX, offY, scale;
    private Button startB, stopB, accB;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("pp", MODE_PRIVATE);
        getWindow().setStatusBarColor(C_BG);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        sv.addView(root);

        TextView title = text("퍼플매크로 플레이어", 22, C_FG, true);
        root.addView(title);
        root.addView(text("제작기에서 받은 매크로 파일을 이 태블릿에서 터치로 재생해요.", 13, C_MUTED, false));

        // 1. 권한
        LinearLayout c1 = card(root, "1. 접근성 권한");
        accState = text("", 14, C_FG, false);
        c1.addView(accState);
        accB = button("접근성 설정 열기", false);
        accB.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        c1.addView(accB);

        // 2. 파일
        LinearLayout c2 = card(root, "2. 매크로 파일");
        Button pick = button("파일 열기 (.json)", true);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, PICK);
        });
        c2.addView(pick);
        fileInfo = text("", 13, C_MUTED, false);
        c2.addView(fileInfo);
        recSpin = new Spinner(this);
        recSpin.setVisibility(View.GONE);
        c2.addView(recSpin);
        info = text("", 14, C_FG, false);
        info.setLineSpacing(0, 1.25f);
        c2.addView(info);

        // 3. 시작
        LinearLayout c3 = card(root, "3. 재생");
        c3.addView(text("시작 시각 (예 08:44). 비워 두면 10초 뒤에 시작해요.", 13, C_MUTED, false));
        startAt = edit("08:44", InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        c3.addView(startAt);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        startB = button("▶ 재생 시작", true);
        stopB = button("■ 정지", false);
        row.addView(startB, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        sl.leftMargin = dp(10);
        row.addView(stopB, sl);
        c3.addView(row);
        c3.addView(text("시작하면 이 앱은 뒤로 가고 게임 화면이 나와요. 게임 맨 위 가운데의 작은 막대로 진행 시간을 보고 정지할 수 있어요.", 12, C_MUTED, false));
        startB.setOnClickListener(v -> onStart_());
        stopB.setOnClickListener(v -> {
            if (PlayerService.I != null) PlayerService.I.stop();
            Guard.prefs(this).edit().putBoolean("playing", false)
                    .putString("stopReason", "정지 버튼으로 멈췄어요").putLong("stopAt", System.currentTimeMillis()).apply();
            Guard.disarm(this);
            refresh();
        });

        // 4. 좌표 보정
        LinearLayout c4 = card(root, "4. 좌표 확인 · 보정");
        c4.addView(text("점이 게임 버튼 위에 맞는지 확인해요. 누른 뒤 20초 동안 게임 화면에 점이 표시돼요. 어긋나면 아래 값을 바꿔요(px, %).", 13, C_MUTED, false));
        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        offX = edit("가로 이동", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        offY = edit("세로 이동", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        scale = edit("크기 %", InputType.TYPE_CLASS_NUMBER);
        offX.setText(fmt(prefs.getFloat("offX", 0)));
        offY.setText(fmt(prefs.getFloat("offY", 0)));
        scale.setText(fmt(prefs.getFloat("scale", 100)));
        r2.addView(labeled("가로 이동", offX), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        r2.addView(labeled("세로 이동", offY), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        r2.addView(labeled("크기 %", scale), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        c4.addView(r2);
        Button dotsB = button("좌표 점 보기 (20초)", false);
        dotsB.setOnClickListener(v -> {
            saveCalib();
            if (PlayerService.I == null) { toast("먼저 접근성 권한을 켜 주세요."); return; }
            PlayerService.I.showDots(20_000);
            moveTaskToBack(true);
        });
        c4.addView(dotsB);

        // 5. 자동 복구
        LinearLayout c5 = card(root, "5. 권한 자동 복구 · 이어하기");
        guardState = text("", 14, C_FG, false);
        c5.addView(guardState);
        c5.addView(text("권한이 꺼지면 앱이 스스로 다시 켜고, 시작 시각 기준으로 하던 위치부터 이어서 재생해요. 처음 한 번 PC에서 아래 명령을 실행해야 해요.", 13, C_MUTED, false));
        TextView cmd = text(Guard.ADB_CMD, 12, C_FG, false);
        cmd.setTypeface(Typeface.MONOSPACE);
        cmd.setTextIsSelectable(true);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0xFFEEF3F2);
        cbg.setCornerRadius(dp(8));
        cmd.setBackground(cbg);
        cmd.setPadding(dp(10), dp(8), dp(10), dp(8));
        c5.addView(cmd);
        Button copyB = button("명령 복사", false);
        copyB.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", Guard.ADB_CMD));
                toast("복사했어요.");
            } catch (Exception e) { toast("복사하지 못했어요."); }
        });
        c5.addView(copyB);
        logView = text("", 13, C_MUTED, false);
        c5.addView(logView);

        setContentView(sv);
        PlayerService.onState = () -> runOnUiThread(this::refresh);
        if (macro == null) {
            macro = Store.loadMacro(this);
            fileName = prefs.getString("fileName", "");
        }
        if (macro != null) showMacro();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        PlayerService.onState = null;
        super.onDestroy();
    }

    private void refresh() {
        boolean on = PlayerService.I != null;
        accState.setText(on ? "✓ 켜져 있어요" : "꺼져 있어요. 설정 → 설치된 앱 → 퍼플매크로 플레이어를 켜 주세요.");
        accState.setTextColor(on ? C_ACC : 0xFFB3261E);
        accB.setVisibility(on ? View.GONE : View.VISIBLE);
        boolean playing = on && PlayerService.I.isPlaying();
        boolean want = Guard.wantPlaying(this);
        startB.setEnabled(macro != null && on && !playing);
        stopB.setEnabled(playing || want);
        boolean cw = Guard.canWrite(this);
        guardState.setText(cw ? "✓ 자동 복구 사용 가능" : "아직 꺼져 있어요 (PC 명령 1회 필요). 이어하기는 권한을 직접 다시 켜면 동작해요.");
        guardState.setTextColor(cw ? C_ACC : 0xFFB3261E);
        SharedPreferences p = prefs;
        StringBuilder lg = new StringBuilder();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA);
        if (want) lg.append(playing ? "지금 재생 중" : "재생 대기 중 (권한이 켜지면 이어서 재생)");
        long sa = p.getLong("stopAt", 0);
        if (!want && sa > 0) lg.append("마지막 정지: ").append(f.format(new java.util.Date(sa))).append(" · ").append(p.getString("stopReason", ""));
        long da = p.getLong("dropAt", 0);
        if (da > 0) lg.append("\n권한이 마지막으로 꺼진 때: ").append(f.format(new java.util.Date(da)));
        int rc = p.getInt("recoverCount", 0), ec = p.getInt("resumeCount", 0);
        if (rc > 0) lg.append("\n권한 자동 복구 ").append(rc).append("번 (마지막 ").append(f.format(new java.util.Date(p.getLong("recoverAt", 0)))).append(")");
        if (ec > 0) lg.append("\n이어하기 ").append(ec).append("번 (마지막 ").append(f.format(new java.util.Date(p.getLong("resumeAt", 0)))).append(")");
        logView.setText(lg.toString());
        startB.setAlpha(startB.isEnabled() ? 1f : 0.45f);
        stopB.setAlpha(stopB.isEnabled() ? 1f : 0.45f);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try (InputStream in = getContentResolver().openInputStream(u)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String s = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            if (s.startsWith("﻿")) s = s.substring(1);
            List<String[]> rs = Macro.records(s);
            if (rs.isEmpty()) throw new Exception("기록이 없어요");
            json = s;
            recs = rs;
            fileName = u.getLastPathSegment() == null ? "" : u.getLastPathSegment();
            loadRec(0);
        } catch (Exception e) {
            toast("파일을 읽지 못했어요: " + e.getMessage());
        }
    }

    private void loadRec(int i) {
        try {
            macro = Macro.parse(json, recs.get(i)[0]);
            Store.save(this, json, recs.get(i)[0], fileName);
            showMacro();
            Matcher m = Pattern.compile("(\\d{1,2}:\\d{2})\\s*시작").matcher(macro.title);
            if (m.find()) startAt.setText(m.group(1));
        } catch (Exception e) {
            toast("매크로를 읽지 못했어요: " + e.getMessage());
        }
        refresh();
    }

    private void showMacro() {
        fileInfo.setText(fileName);
        if (recs != null && recs.size() > 1) {
            List<String> names = new ArrayList<>();
            for (String[] r : recs) names.add(r[1]);
            recSpin.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
            recSpin.setVisibility(View.VISIBLE);
            recSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                boolean first = true;
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { if (first) { first = false; return; } loadRec(pos); }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });
        } else recSpin.setVisibility(View.GONE);

        int back = 0, touch = 0;
        for (Macro.Act a : macro.acts) if (a.kind == Macro.BACK) back++; else touch++;
        StringBuilder sb = new StringBuilder();
        sb.append(macro.title).append('\n');
        sb.append("길이 ").append(Macro.clock(macro.totalMs));
        sb.append(" · 반복 ").append(macro.repeat == -1 ? "무한" : macro.repeat + "회").append('\n');
        sb.append("터치 ").append(touch).append("번 · 뒤로 가기(ESC) ").append(back).append("번");
        if (!macro.unmapped.isEmpty()) {
            sb.append("\n⚠ 위치가 없는 키라서 건너뛰어요: ");
            boolean f = true;
            for (Map.Entry<String, Integer> e : macro.unmapped.entrySet()) {
                if (!f) sb.append(", ");
                sb.append(e.getKey()).append(" ").append(e.getValue()).append("번");
                f = false;
            }
        }
        info.setText(sb.toString());
    }

    private void onStart_() {
        if (PlayerService.I == null) { toast("먼저 접근성 권한을 켜 주세요."); return; }
        if (macro == null) { toast("먼저 매크로 파일을 열어 주세요."); return; }
        saveCalib();
        long delay = 10_000;
        String t = startAt.getText().toString().trim();
        if (!t.isEmpty()) {
            Matcher m = Pattern.compile("^(\\d{1,2})[:시 ]\\s*(\\d{1,2})").matcher(t);
            if (!m.find()) { toast("시작 시각은 08:44처럼 적어 주세요."); return; }
            int hh = Integer.parseInt(m.group(1)), mm = Integer.parseInt(m.group(2));
            if (hh > 23 || mm > 59) { toast("시작 시각이 올바르지 않아요."); return; }
            Calendar c = Calendar.getInstance();
            long now = c.getTimeInMillis();
            c.set(Calendar.HOUR_OF_DAY, hh);
            c.set(Calendar.MINUTE, mm);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() < now - 30_000) c.add(Calendar.DAY_OF_MONTH, 1);
            delay = Math.max(3_000, c.getTimeInMillis() - now);
            toast((delay > 12 * 3600_000L ? "내일 " : "") + String.format(java.util.Locale.US, "%02d:%02d", hh, mm) + "에 시작해요. 게임 화면으로 돌아가 주세요.");
        } else {
            toast("10초 뒤에 시작해요. 게임 화면으로 돌아가 주세요.");
        }
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9);
        PlayerService.I.start(macro, delay);
        refresh();
        moveTaskToBack(true);
    }

    private void saveCalib() {
        prefs.edit()
                .putFloat("offX", num(offX, 0))
                .putFloat("offY", num(offY, 0))
                .putFloat("scale", num(scale, 100))
                .apply();
        if (PlayerService.I != null) PlayerService.I.loadCalib();
    }

    // ───────── 작은 UI 도우미 ─────────
    private static float num(EditText e, float def) {
        try { return Float.parseFloat(e.getText().toString().trim()); } catch (Exception x) { return def; }
    }

    private static String fmt(float v) {
        return v == (long) v ? String.valueOf((long) v) : String.valueOf(v);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private LinearLayout card(LinearLayout root, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFFDDE3E2);
        c.setBackground(bg);
        c.setPadding(dp(16), dp(12), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        root.addView(c, lp);
        c.addView(text(title, 16, C_FG, true));
        return c;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(primary ? 0xFFFFFFFF : C_ACC);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? C_ACC : 0xFFE5F3F1);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }

    private EditText edit(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setTextColor(C_FG);
        e.setSingleLine(true);
        return e;
    }

    private LinearLayout labeled(String label, EditText e) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, 0, dp(10), 0);
        l.addView(text(label, 12, C_MUTED, false));
        l.addView(e);
        l.setGravity(Gravity.START);
        return l;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
