package app.fireremote.tvhelper;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs on the Fire TV through app_process (as the shell user), with scrcpy-server on the same classpath.
 * Prints one line per launchable app:  package TAB b|i TAB base64 PNG   (b = 16:9 TV banner, i = icon),
 * then FR_ICONS_END. The PackageManager comes from scrcpy-server's own setup, the one its list_apps uses.
 */
public final class IconDump {
    public static void main(String[] args) throws Exception {
        int width = args.length > 0 ? Integer.parseInt(args[0]) : 240;
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8");
        try {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        } catch (Throwable ignored) {
        }
        try {
            Class.forName("com.genymobile.scrcpy.Workarounds").getMethod("apply").invoke(null);
        } catch (Throwable t) {
            System.err.println("workarounds: " + t);
        }
        Class<?> fake = Class.forName("com.genymobile.scrcpy.FakeContext");
        Object context = fake.getMethod("get").invoke(null);
        PackageManager pm = (PackageManager) fake.getMethod("getPackageManager").invoke(context);

        Map<String, ActivityInfo> apps = new LinkedHashMap<>();
        for (String category : new String[] {"android.intent.category.LEANBACK_LAUNCHER", Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
            for (ResolveInfo ri : found) {
                if (ri.activityInfo != null && !apps.containsKey(ri.activityInfo.packageName)) {
                    apps.put(ri.activityInfo.packageName, ri.activityInfo);
                }
            }
        }

        int bannerHeight = width * 9 / 16;
        for (Map.Entry<String, ActivityInfo> e : apps.entrySet()) {
            try {
                ActivityInfo ai = e.getValue();
                Drawable d = ai.loadBanner(pm);
                boolean banner = d != null;
                if (d == null) d = ai.loadIcon(pm);
                if (d == null) continue;
                int w = banner ? width : bannerHeight;
                int h = bannerHeight;
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                d.setBounds(0, 0, w, h);
                d.draw(canvas);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, png);
                bmp.recycle();
                out.print(e.getKey());
                out.print('\t');
                out.print(banner ? 'b' : 'i');
                out.print('\t');
                out.println(Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP));
            } catch (Throwable t) {
                System.err.println(e.getKey() + ": " + t);
            }
        }
        out.println("FR_ICONS_END");
        out.flush();
        System.exit(0);
    }
}
