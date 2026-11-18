package com.ttangja.purpleplayer;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** 불러온 매크로 파일을 앱 안에 저장해 두고, 다시 켜졌을 때 읽는다. */
final class Store {
    private static File file(Context c) { return new File(c.getFilesDir(), "current.json"); }

    static void save(Context c, String json, String recKey, String name) {
        try (FileOutputStream o = new FileOutputStream(file(c))) {
            o.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        Guard.prefs(c).edit().putString("recKey", recKey).putString("fileName", name).apply();
    }

    static String loadJson(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static Macro loadMacro(Context c) {
        try {
            String j = loadJson(c);
            String k = Guard.prefs(c).getString("recKey", null);
            if (j == null || k == null) return null;
            return Macro.parse(j, k);
        } catch (Exception e) {
            return null;
        }
    }

    private Store() {}
}
