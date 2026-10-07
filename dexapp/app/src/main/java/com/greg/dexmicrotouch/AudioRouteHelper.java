package com.greg.dexmicrotouch;

import android.content.Context;
import android.content.Intent;
import android.media.MediaRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AudioRouteHelper {
    private AudioRouteHelper() {}

    public static String routeToHdmi(Context context) {
        try {
            MediaRouter router = (MediaRouter) context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
            if (router == null) return "Media router unavailable";

            MediaRouter.RouteInfo best = null;
            int bestScore = Integer.MIN_VALUE;

            for (int i = 0; i < router.getRouteCount(); i++) {
                MediaRouter.RouteInfo route = router.getRouteAt(i);
                if ((route.getSupportedTypes() & MediaRouter.ROUTE_TYPE_LIVE_AUDIO) == 0) continue;

                String name = String.valueOf(route.getName()).toLowerCase(Locale.US);
                int score = 0;

                if (name.contains("hdmi")) score += 100;
                if (name.contains("display")) score += 90;
                if (name.contains("monitor")) score += 80;
                if (name.contains("tv")) score += 70;
                if (name.contains("dex")) score += 60;
                if (name.contains("phone")) score -= 100;
                if (name.contains("speaker")) score -= 40;

                try {
                    if (route.getPresentationDisplay() != null) score += 50;
                } catch (Throwable ignored) {}

                if (score > bestScore) {
                    bestScore = score;
                    best = route;
                }
            }

            if (best != null && bestScore > 0) {
                router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, best);
                return "Selected: " + best.getName();
            }

            openSystemOutputSwitcher(context);
            return "HDMI route not directly exposed; opened Media Output";
        } catch (Throwable t) {
            openSystemOutputSwitcher(context);
            return "Opened Media Output (" + t.getClass().getSimpleName() + ")";
        }
    }

    public static String routeToPhone(Context context) {
        try {
            MediaRouter router = (MediaRouter) context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
            if (router == null) return "Media router unavailable";

            MediaRouter.RouteInfo def = router.getDefaultRoute();
            router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, def);
            return "Selected: " + def.getName();
        } catch (Throwable t) {
            openSystemOutputSwitcher(context);
            return "Opened Media Output (" + t.getClass().getSimpleName() + ")";
        }
    }

    public static String describeRoutes(Context context) {
        try {
            MediaRouter router = (MediaRouter) context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
            if (router == null) return "No media router";

            List<String> names = new ArrayList<>();
            for (int i = 0; i < router.getRouteCount(); i++) {
                MediaRouter.RouteInfo route = router.getRouteAt(i);
                if ((route.getSupportedTypes() & MediaRouter.ROUTE_TYPE_LIVE_AUDIO) != 0) {
                    names.add(String.valueOf(route.getName()));
                }
            }
            return names.isEmpty() ? "No audio routes" : String.join(", ", names);
        } catch (Throwable t) {
            return "Route scan failed";
        }
    }

    public static void openSystemOutputSwitcher(Context context) {
        boolean sent = false;
        try {
            Intent i = new Intent("com.android.systemui.action.LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG");
            i.setPackage("com.android.systemui");
            i.putExtra("package_name", context.getPackageName());
            context.sendBroadcast(i);
            sent = true;
        } catch (Throwable ignored) {}

        if (!sent) {
            try {
                Intent i = new Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG");
                i.setPackage("com.android.systemui");
                i.putExtra("package_name", context.getPackageName());
                context.sendBroadcast(i);
            } catch (Throwable ignored) {}
        }
    }
}
