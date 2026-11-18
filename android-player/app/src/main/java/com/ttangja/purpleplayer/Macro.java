package com.ttangja.purpleplayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 퍼플 매크로 JSON(records.rN.events)을 터치 동작 목록으로 바꾼다. */
final class Macro {
    static final int TOUCH = 0, BACK = 1;
    static final long MIN_DUR = 40, MAX_DUR = 55_000;

    static final class Act {
        int kind;
        long t;       // 시작부터 ms
        long dur;     // 누르고 있는 시간
        float[] xs, ys; // 0~1 비율 좌표 (16:9 게임 화면 기준)
        String label;
    }

    String title = "";
    final List<Act> acts = new ArrayList<>();
    long totalMs;
    int repeat = 1;        // -1 = 무한 반복
    long intervalMs;
    int zeroClicks;
    final TreeMap<String, Integer> unmapped = new TreeMap<>();

    /** 파일 안의 기록 이름 목록 */
    static List<String[]> records(String json) throws Exception {
        JSONObject recs = new JSONObject(json).getJSONObject("records");
        List<String[]> out = new ArrayList<>();
        Iterator<String> it = recs.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject r = recs.getJSONObject(k);
            out.add(new String[]{k, r.optString("title", k)});
        }
        return out;
    }

    private static final class Ev {
        long ms; long type; long detail; float x, y;
    }

    private static final class Open {
        long t; List<float[]> pts = new ArrayList<>(); List<Long> ts = new ArrayList<>(); boolean skip;
    }

    static Macro parse(String json, String recKey) throws Exception {
        JSONObject r = new JSONObject(json).getJSONObject("records").getJSONObject(recKey);
        Macro m = new Macro();
        m.title = r.optString("title", recKey);
        double speed = parseD(r.optString("speed", "1"), 1);
        if (speed <= 0) speed = 1;
        m.intervalMs = (long) parseD(r.optString("interval", "0"), 0);
        Object rep = r.opt("repeat");
        JSONObject repO = null;
        if (rep instanceof JSONObject) repO = (JSONObject) rep;
        else if (rep instanceof String) { try { repO = new JSONObject(((String) rep).replace('\'', '"')); } catch (Exception ignored) {} }
        if (repO != null) {
            int c = (int) parseD(repO.optString("count", "1"), 1);
            m.repeat = c < 0 ? -1 : Math.max(1, c);
        }

        JSONArray arr = r.getJSONArray("events");
        List<Ev> evs = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            Ev v = new Ev();
            try {
                v.ms = (long) (parseD(e.optString("ms", "0"), 0) / speed);
                v.type = Long.parseLong(e.optString("type", "0").trim());
                v.detail = Long.parseLong(e.optString("detail", "0").trim());
            } catch (NumberFormatException ex) { continue; }
            JSONObject p = e.optJSONObject("position");
            if (p != null) {
                v.x = (float) parseD(p.optString("x", "0"), 0);
                v.y = (float) parseD(p.optString("y", "0"), 0);
            }
            evs.add(v);
        }
        Collections.sort(evs, (a, b) -> Long.compare(a.ms, b.ms));

        Open mouse = null;
        Map<Long, Long> keyDown = new HashMap<>();
        long last = 0;
        for (Ev e : evs) {
            last = Math.max(last, e.ms);
            if (e.type == 513) {
                mouse = new Open();
                mouse.t = e.ms;
                mouse.skip = (e.x == 0f && e.y == 0f);
                mouse.pts.add(new float[]{e.x, e.y});
                mouse.ts.add(e.ms);
            } else if (e.type == 512) {
                if (mouse != null && !mouse.skip) { mouse.pts.add(new float[]{e.x, e.y}); mouse.ts.add(e.ms); }
            } else if (e.type == 514) {
                if (mouse == null) continue;
                if (mouse.skip) { m.zeroClicks++; mouse = null; continue; }
                mouse.pts.add(new float[]{e.x, e.y});
                mouse.ts.add(e.ms);
                Act a = new Act();
                a.kind = TOUCH; a.t = mouse.t; a.dur = e.ms - mouse.t; a.label = "클릭";
                a.xs = new float[mouse.pts.size()]; a.ys = new float[mouse.pts.size()];
                for (int i = 0; i < mouse.pts.size(); i++) { a.xs[i] = mouse.pts.get(i)[0]; a.ys[i] = mouse.pts.get(i)[1]; }
                m.acts.add(a);
                mouse = null;
            } else if (e.type > 0 && e.type < 256) {
                boolean down = e.detail < 2147483648L;
                if (down) {
                    if (!keyDown.containsKey(e.type)) keyDown.put(e.type, e.ms);
                } else {
                    Long t0 = keyDown.remove(e.type);
                    if (t0 == null) continue;
                    m.addKey((int) e.type, t0, e.ms - t0);
                }
            }
        }
        for (Map.Entry<Long, Long> k : keyDown.entrySet()) m.addKey(k.getKey().intValue(), k.getValue(), 100);
        Collections.sort(m.acts, (a, b) -> Long.compare(a.t, b.t));
        for (Act a : m.acts) a.dur = Math.max(MIN_DUR, Math.min(MAX_DUR, a.dur));
        m.totalMs = last + 500;
        return m;
    }

    private void addKey(int vk, long t, long dur) {
        Act a = new Act();
        a.t = t; a.dur = dur;
        a.label = KeyMap.keyName(vk);
        if (vk == 27) { a.kind = BACK; acts.add(a); return; }
        float[] p = KeyMap.POS.get(vk);
        if (p == null) {
            String n = KeyMap.keyName(vk);
            Integer c = unmapped.get(n);
            unmapped.put(n, c == null ? 1 : c + 1);
            return;
        }
        a.kind = TOUCH;
        a.xs = new float[]{p[0]}; a.ys = new float[]{p[1]};
        acts.add(a);
    }

    private static double parseD(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
