package io.github.atiladpribeiro.universaladshield;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Fullscreen-ad privacy overlay and adaptive accelerator.
 *
 * Important invariants:
 *  - SDK callbacks and their arguments/results are never replaced.
 *  - JavaScript clocks and SDK timers are never forged.
 *  - A close control is used only when the SDK actually exposes it as visible,
 *    enabled and clickable; skip/forfeit controls are never accepted.
 *  - An ad overlay survives onPause and is detached only after the ad Activity
 *    is destroyed, preventing a creative flash while the SDK closes.
 */
public final class UniversalAdShield implements IXposedHookLoadPackage {
    private static final String TAG = "UniversalAdShield";
    private static final String OVERLAY_TAG = TAG + ".OVERLAY";
    private static final String FOOTER_TAG = TAG + ".FOOTER";
    private static final int TICK_MS = 650;
    private static final int TICK_AFTER_DONE_MS = 220;
    private static final int MAX_SDK_CLOSE_PASSES = 48;

    private static volatile Handler MAIN;
    private static final AtomicBoolean CORE_HOOKS_INSTALLED = new AtomicBoolean();
    private static final AtomicInteger ACTIVE_COUNT = new AtomicInteger();
    private static final Map<Activity, Session> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<MediaPlayer, WeakReference<Session>> MEDIA_SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, WeakReference<Session>> KWAI_PLAYER_SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile WeakReference<Activity> CURRENT_KWAI_ACTIVITY;
    private static final Set<MediaPlayer.OnCompletionListener> WRAPPED_LISTENERS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final String[] SDK_MARKERS = {
            "com.google.android.gms.ads", ".adplayer.", "adactivity",
            "adplayer", "vastplayer", "mraid", "interstitial", "rewarded",
            "rewardedvideo", "com.unity3d.ads", "unityads", "applovin",
            "ironsource", "supersonic", "vungle", "liftoff", "chartboost",
            "startapp", "mintegral", "mbridge", "pangle", "bytedance.sdk.openadsdk",
            "com.kwad.", "kwad", "facebook.ads", "audiencenetwork", "inmobi",
            "tapjoy", "fyber", "inneractive", "digitalturbine", "smaato",
            "hyprmx", "ogury", "yandex.mobile.ads", "amazon.device.ads",
            "mobilefuse", "bigo.ads", "verve", "adcolony", "mytarget",
            "appodeal", "tradplus", "topon", "anythink", "mobicare.aa.ads"
    };

    private static final String[] URL_MARKERS = {
            "doubleclick", "googlesyndication", "googleadservices", "adservice",
            "unityads", "unity3d", "applovin", "ironsrc", "vungle", "chartboost",
            "startapp", "mintegral", "mbridge", "pangle", "inmobi", "tapjoy",
            "fyber", "smaato", "ogury", "adcolony", "appodeal", "adsystem",
            "kwad", "kuaishou"
    };

    /* This script touches the creative media only. It deliberately does not
       replace Date, setTimeout, setInterval or requestAnimationFrame because
       those are also used by reward verification and anti-fraud state. */
    private static final String WEB_PROBE_PREFIX =
            "(function(allowClose){try{" +
            "var p='sdk',ended=false,rate=1,closeReady=false;" +
            "var vids=document.querySelectorAll('video');" +
            "for(var i=0;i<vids.length;i++){var v=vids[i];try{" +
            "v.muted=true;v.volume=0;" +
            "if(!v.__uas){v.__uas=1;v.addEventListener('ended',function(){this.__uasEnded=1;});}" +
            "if(!v.ended&&!v.paused){for(var r of [4,3,2]){try{v.playbackRate=r;rate=v.playbackRate;if(rate>1)break;}catch(_){}}}" +
            "if(v.ended||v.__uasEnded)ended=true;" +
            "if(isFinite(v.duration)&&v.duration>0)p=Math.max(0,Math.min(100,Math.round(v.currentTime/v.duration*100)))+'%';" +
            "}catch(_){}}" +
            "var all=document.querySelectorAll('button,[role=button],[aria-label],[data-testid],[id],[class]');" +
            "for(var j=0;j<all.length;j++){var e=all[j],s=((e.innerText||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('data-testid')||'')+' '+(e.id||'')+' '+(typeof e.className==='string'?e.className:'')+' '+(e.title||'')).toLowerCase();" +
            "if(/(close|dismiss|fechar|concluir|continuar|done|xmark)/i.test(s)&&!/(skip|pular)/i.test(s)&&!e.disabled&&e.offsetParent!==null){closeReady=true;if(allowClose&&!e.__uasClicked){e.__uasClicked=1;e.click();}break;}}" +
            "return [ended?'1':'0',p,String(rate),closeReady?'1':'0'].join('|');" +
            "}catch(_){return '0|sdk|1|0';}})(";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (shouldNeverHook(lpparam.packageName)) return;
        try {
            XposedBridge.log(TAG + ": v2.1.0 loaded in " + lpparam.packageName);
            if (CORE_HOOKS_INSTALLED.compareAndSet(false, true)) {
                hookLifecycle();
                hookExternalLaunches();
                hookWebViewNavigation();
                hookAudioAndMedia();
            }
            hookVerifiedCallbacks(lpparam.classLoader);
            hookKnownCompletionSurfaces(lpparam.classLoader);
            hookPangleProgress(lpparam.classLoader);
            hookKwaiPlayer(lpparam.classLoader);
            if ("com.kwai.video".equals(lpparam.packageName)) hookKwaiUi(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failure in " + lpparam.packageName + ": " + t);
        }
    }

    private static boolean shouldNeverHook(String pkg) {
        return pkg == null || pkg.equals("android") || pkg.equals("com.android.systemui")
                || pkg.equals("org.lsposed.manager")
                || pkg.equals("io.github.atiladpribeiro.universaladshield")
                || pkg.equals("com.android.webview") || pkg.equals("com.google.android.webview")
                || pkg.contains(":") || pkg.startsWith("com.android.launcher")
                || pkg.startsWith("app.lawnchair");
    }

    private static void hookLifecycle() {
        XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity a = (Activity) param.thisObject;
                Session s = SESSIONS.get(a);
                if (s != null) s.paused = false;
                inspect(a);
                handleKwaiUi(a);
                main().postDelayed(() -> inspect(a), 160);
                main().postDelayed(() -> handleKwaiUi(a), 220);
                main().postDelayed(() -> handleKwaiUi(a), 900);
                main().postDelayed(() -> handleKwaiUi(a), 1800);
                main().postDelayed(() -> handleKwaiUi(a), 3500);
                main().postDelayed(() -> handleKwaiUi(a), 6500);
                main().postDelayed(() -> handleKwaiUi(a), 10000);
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Activity a = (Activity) param.thisObject;
                Session s = SESSIONS.get(a);
                if (s != null) {
                    s.paused = true;
                    s.status = "Tela do anúncio encerrando; proteção mantida até o fechamento • sem som";
                    updateFooter(s);
                }
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                detach((Activity) param.thisObject, "Activity encerrada");
            }
        });
    }

    private static void inspect(Activity activity) {
        if (!usable(activity)) return;
        try {
            Window w = activity.getWindow();
            if (w == null || !(w.getDecorView() instanceof ViewGroup)) return;
            ViewGroup decor = (ViewGroup) w.getDecorView();
            AppConfig config = AppConfig.load(activity, activity.getPackageName());
            if (!config.enabled) {
                detach(activity, "proteção desativada para o app");
                return;
            }
            Detection d = detect(activity, decor);
            if (!d.ad) {
                if (SESSIONS.containsKey(activity)) detach(activity, "tela deixou de ser anúncio");
                return;
            }
            Session session;
            synchronized (SESSIONS) {
                session = SESSIONS.get(activity);
                if (session == null) {
                    session = new Session(activity, d.platform, d.surface, config);
                    SESSIONS.put(activity, session);
                    ACTIVE_COUNT.incrementAndGet();
                }
            }
            session.config = config;
            session.platform = d.platform;
            session.surface = d.surface;
            if (config.overlay) installOverlay(session, decor);
            if (!session.started) {
                session.started = true;
                main().post(session);
                XposedBridge.log(TAG + ": protected " + activity.getPackageName() + "/"
                        + activity.getClass().getName() + " as " + d.platform);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": inspect failure: " + t);
        }
    }

    private static Detection detect(Activity activity, View root) {
        String cls = lower(activity.getClass().getName());
        String platform = platformFrom(cls, root);
        // Host mediation wrappers launch the real SDK Activity and wait for its
        // result. Covering/finishing an empty wrapper can strand that result
        // flow forever, so only protect it when it actually owns a creative.
        if (cls.endsWith(".aarewardedvideoactivity")) {
            WebView wrapperWeb = findLargestWebView(root);
            long wrapperArea = Math.max(1L, area(root));
            if (wrapperWeb == null || area(wrapperWeb) < (long) (wrapperArea * 0.86f))
                return Detection.NO;
        }
        if (cls.contains("kwairnactivity") || cls.contains("overseawebactivity")
                || cls.contains("homeactivity")) return Detection.NO;
        if (containsAny(cls, SDK_MARKERS)) {
            return new Detection(true, platform, findLargestWebView(root) != null ? "WebView" : "nativo");
        }
        WebView web = findLargestWebView(root);
        if (web == null) return Detection.NO;
        long rootArea = Math.max(1L, area(root));
        if (area(web) < (long) (rootArea * 0.86f)) return Detection.NO;
        String url = lower(web.getUrl());
        boolean sdkStack = stackLooksLikeAd();
        return containsAny(url, URL_MARKERS) || sdkStack
                ? new Detection(true, platform, "WebView") : Detection.NO;
    }

    @SuppressLint("SetTextI18n")
    private static void installOverlay(Session s, ViewGroup decor) {
        View old = decor.findViewWithTag(OVERLAY_TAG);
        if (old != null) {
            s.overlay = new WeakReference<>(old);
            old.bringToFront();
            return;
        }
        BlockingOverlay overlay = new BlockingOverlay(s.activity.get());
        overlay.setTag(OVERLAY_TAG);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);
        overlay.setContentDescription("Pulando anúncio...");

        TextView title = new TextView(s.activity.get());
        title.setText("Pulando anúncio...");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        overlay.addView(title, new FrameLayout.LayoutParams(-1, -1));

        TextView footer = new TextView(s.activity.get());
        footer.setTag(FOOTER_TAG);
        footer.setTextColor(Color.rgb(175, 175, 175));
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(20, 10, 20, 24);
        overlay.addView(footer, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        decor.addView(overlay, new ViewGroup.LayoutParams(-1, -1));
        s.overlay = new WeakReference<>(overlay);
        updateFooter(s);
        overlay.bringToFront();
        overlay.requestFocus();
    }

    private static void detach(Activity activity, String reason) {
        Session s;
        synchronized (SESSIONS) { s = SESSIONS.remove(activity); }
        if (s != null) {
            s.active = false;
            ACTIVE_COUNT.updateAndGet(v -> Math.max(0, v - 1));
        }
        try {
            Window w = activity.getWindow();
            if (w != null && w.getDecorView() instanceof ViewGroup) {
                ViewGroup root = (ViewGroup) w.getDecorView();
                View overlay = root.findViewWithTag(OVERLAY_TAG);
                if (overlay != null && overlay.getParent() instanceof ViewGroup) {
                    ((ViewGroup) overlay.getParent()).removeView(overlay);
                }
            }
        } catch (Throwable ignored) { }
        XposedBridge.log(TAG + ": detached overlay: " + reason);
    }

    private static void hookExternalLaunches() {
        XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (ACTIVE_COUNT.get() <= 0) return;
                Session s = newestSession();
                if (s == null || !s.config.blockExternal) return;
                Intent intent = null;
                for (Object arg : param.args) if (arg instanceof Intent) intent = (Intent) arg;
                if (isExternalIntent(intent)) {
                    XposedBridge.log(TAG + ": blocked external launch " + intent);
                    param.setResult(null);
                }
            }
        });
    }

    private static boolean isExternalIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        Uri data = intent.getData();
        String scheme = data == null ? "" : lower(data.getScheme());
        return Intent.ACTION_VIEW.equals(action) || Intent.ACTION_DIAL.equals(action)
                || Intent.ACTION_SEND.equals(action) || Intent.ACTION_SENDTO.equals(action)
                || scheme.equals("http") || scheme.equals("https") || scheme.equals("market")
                || scheme.equals("intent") || scheme.equals("tel") || scheme.equals("mailto");
    }

    private static void hookWebViewNavigation() {
        XposedBridge.hookAllConstructors(WebView.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof WebView) configureKwaiWebView((WebView) param.thisObject);
            }
        });
        XposedBridge.hookAllMethods(WebView.class, "loadUrl", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof WebView) configureKwaiWebView((WebView) param.thisObject);
                Session s = newestSession();
                if (ACTIVE_COUNT.get() <= 0 || s == null || !s.config.blockExternal
                        || param.args.length == 0
                        || !(param.args[0] instanceof String)) return;
                String u = lower((String) param.args[0]);
                if (u.startsWith("intent:") || u.startsWith("market:") || u.startsWith("tel:")
                        || u.startsWith("mailto:")) param.setResult(null);
            }
        });
        XposedBridge.hookAllMethods(WebViewClient.class, "onReceivedSslError", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length < 2 || !(param.args[0] instanceof WebView)
                        || !(param.args[1] instanceof SslErrorHandler)) return;
                WebView web = (WebView) param.args[0];
                if (!isKwaiGoldWebView(web)) return;
                try {
                    ((SslErrorHandler) param.args[1]).proceed();
                    XposedBridge.log(TAG + ": Kwai Gold WebView SSL fallback applied");
                    param.setResult(null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void hookAudioAndMedia() {
        XposedBridge.hookAllMethods(AudioTrack.class, "play", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (shouldMuteNow()) try { ((AudioTrack) p.thisObject).setVolume(0f); }
                catch (Throwable ignored) { }
            }
        });
        XposedBridge.hookAllMethods(AudioTrack.class, "setVolume", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (shouldMuteNow() && p.args.length > 0) p.args[0] = 0f;
            }
        });
        XposedBridge.hookAllMethods(AudioTrack.class, "setStereoVolume", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (shouldMuteNow() && p.args.length >= 2) { p.args[0] = 0f; p.args[1] = 0f; }
            }
        });
        XposedBridge.hookAllMethods(MediaPlayer.class, "setVolume", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (shouldMuteNow() && p.args.length >= 2) { p.args[0] = 0f; p.args[1] = 0f; }
            }
        });
        XposedBridge.hookAllMethods(MediaPlayer.class, "start", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Session s = newestSession();
                if (s == null) {
                    handleKwaiShortPlayer(p.thisObject);
                    return;
                }
                MediaPlayer player = (MediaPlayer) p.thisObject;
                MEDIA_SESSIONS.put(player, new WeakReference<>(s));
                if (s.config.muteAds) try { player.setVolume(0f, 0f); } catch (Throwable ignored) { }
                float speed = accelerateMediaPlayer(player);
                s.playerType = "MediaPlayer";
                s.nativePlayer = true;
                s.speed = speed;
                s.status = speed > 1f ? "Aceleração confirmada em " + speedText(speed) + " • sem som"
                        : "Este player não permite aceleração segura • sem som";
                updateFooterAsync(s);
            }
        });
        XposedBridge.hookAllMethods(MediaPlayer.class, "setOnCompletionListener", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length == 0 || !(p.args[0] instanceof MediaPlayer.OnCompletionListener)) return;
                MediaPlayer.OnCompletionListener original = (MediaPlayer.OnCompletionListener) p.args[0];
                synchronized (WRAPPED_LISTENERS) { if (WRAPPED_LISTENERS.contains(original)) return; }
                MediaPlayer.OnCompletionListener wrapper = player -> {
                    try { original.onCompletion(player); }
                    finally { onMediaCompleted(player); }
                };
                synchronized (WRAPPED_LISTENERS) { WRAPPED_LISTENERS.add(wrapper); }
                p.args[0] = wrapper;
            }
        });
        XposedBridge.hookAllMethods(SoundPool.class, "play", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (shouldMuteNow() && p.args.length >= 3) { p.args[1] = 0f; p.args[2] = 0f; }
            }
        });
    }

    private static float accelerateMediaPlayer(MediaPlayer player) {
        Session s = newestSession();
        if (s == null || !s.config.accelerate || s.config.maxSpeed <= 1) return 1f;
        try {
            Object pp = MediaPlayer.class.getMethod("getPlaybackParams").invoke(player);
            Method setSpeed = pp.getClass().getMethod("setSpeed", float.class);
            Method apply = MediaPlayer.class.getMethod("setPlaybackParams", pp.getClass());
            // 4x is the highest broadly stable rate for hardware decoders. 16x
            // caused codec/GPU aborts and invalid reward results on this device.
            for (float candidate : speedCandidates(s.config.maxSpeed, 4)) {
                try {
                    Object changed = setSpeed.invoke(pp, candidate);
                    apply.invoke(player, changed == null ? pp : changed);
                    return candidate;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return 1f;
    }

    private static void onMediaCompleted(MediaPlayer player) {
        WeakReference<Session> ref = MEDIA_SESSIONS.get(player);
        Session s = ref == null ? null : ref.get();
        if (s == null) return;
        if (s.rewarded || "Unity Ads".equals(s.platform)) {
            s.mediaEnded = true;
            s.progress = "mídia concluída";
            s.status = "Vídeo concluído; aguardando o controle real de fechar/continuar • sem som";
            s.evidence = "fim da mídia; anúncio multietapas ainda ativo";
            updateFooterAsync(s);
            return;
        }
        complete(s, "fim real do MediaPlayer");
    }

    private static void hookVerifiedCallbacks(ClassLoader loader) {
        if (loader == null) return;
        Class<?> pangle = XposedHelpers.findClassIfExists(
                "com.bytedance.sdk.openadsdk.component.reward.tm", loader);
        if (pangle != null) {
            try {
                XposedHelpers.findAndHookMethod(pangle, "sw", boolean.class, int.class,
                        String.class, int.class, String.class, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                if (p.args.length == 5 && Boolean.TRUE.equals(p.args[0]))
                                    completeNewest("recompensa confirmada pelo Pangle");
                            }
                        });
            } catch (Throwable t) { XposedBridge.log(TAG + ": Pangle hook unavailable: " + t); }
        }
        Class<?> unity = XposedHelpers.findClassIfExists(
                "com.unity3d.ads.adplayer.Invocation", loader);
        if (unity != null) {
            try {
                XposedHelpers.findAndHookConstructor(unity, String.class, Object[].class,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                if (p.args.length == 0 || !(p.args[0] instanceof String)) return;
                                String location = (String) p.args[0];
                                if ("com.unity3d.services.ads.api.AdViewer.receivedReward".equals(location))
                                    completeNewest("recompensa real liberada pelo Unity Ads");
                                else if ("com.unity3d.services.ads.api.AdViewer.completed".equals(location))
                                    completeNewest("conclusão confirmada pelo Unity Ads");
                            }
                        });
            } catch (Throwable t) { XposedBridge.log(TAG + ": Unity hook unavailable: " + t); }
        }
        hookRewardMethods(loader, "com.yxcorp.gifshow.commercialization.feature.reward.RewardedAdKwaiActivity",
                "Kwai");
        hookRewardMethods(loader, "com.kuaishou.overseas.ads.reward.RewardedAdActivity", "Kwai");
    }

    private static void hookRewardMethods(ClassLoader loader, String className, String platform) {
        Class<?> cls = XposedHelpers.findClassIfExists(className, loader);
        if (cls == null) return;
        String[] rewardNames = {
                "onRewardEarned", "onRewardFinish", "onRewardedVideoCompleted",
                "onRewardVideoCompleted", "onRewardVerify", "onReward"
        };
        for (String method : rewardNames) {
            try {
                XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        completeNewest("recompensa confirmada pelo " + platform + " em " + method);
                    }
                });
            } catch (Throwable ignored) {
            }
        }
        String[] mediaNames = {"onRewardPlayComplete", "onVideoComplete", "onPlayCompleted"};
        for (String method : mediaNames) {
            try {
                XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Session s = newestSession();
                        if (s == null) return;
                        s.mediaEnded = true;
                        s.progress = "mídia concluída";
                        s.status = "Vídeo concluído; aguardando confirmação real de recompensa • sem som";
                        s.evidence = "fim do player informado pelo " + platform;
                        updateFooterAsync(s);
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    /** Completion methods from host-side ad aggregators. These hooks observe
     * the method after the SDK has executed it and never alter parameters or
     * return values. */
    private static void hookKnownCompletionSurfaces(ClassLoader loader) {
        if (loader == null) return;
        Class<?> vast = XposedHelpers.findClassIfExists(
                "br.com.mobicare.aa.ads.rv.modules.vast.AAVastPlayerActivity", loader);
        if (vast != null) {
            try {
                XposedBridge.hookAllMethods(vast, "D0", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Activity a = p.thisObject instanceof Activity ? (Activity) p.thisObject : null;
                        Session s = a == null ? null : SESSIONS.get(a);
                        if (s != null) complete(s, "ALL_ADS_COMPLETED confirmado pelo VAST/IMA");
                    }
                });
            } catch (Throwable t) { XposedBridge.log(TAG + ": VAST completion hook unavailable: " + t); }
        }
    }

    /** Pangle keeps some video/playable creatives inside a cross-origin frame,
     * where a document-level JavaScript probe cannot see their media. Its
     * Activity still receives the SDK's real current/duration callback. */
    private static void hookPangleProgress(ClassLoader loader) {
        if (loader == null) return;
        hookPangleNativeAccelerator(loader);
        String[] activities = {
                "com.bytedance.sdk.openadsdk.activity.single.TTRewardExpressVideoActivity",
                "com.bytedance.sdk.openadsdk.activity.TTRewardExpressVideoActivity"
        };
        for (String name : activities) {
            Class<?> cls = XposedHelpers.findClassIfExists(name, loader);
            if (cls == null) continue;
            try {
                XposedHelpers.findAndHookMethod(cls, "sw", long.class, long.class,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                Activity a = p.thisObject instanceof Activity
                                        ? (Activity) p.thisObject : null;
                                Session s = a == null ? null : SESSIONS.get(a);
                                if (s == null || p.args.length < 2) return;
                                long current = ((Number) p.args[0]).longValue();
                                long duration = ((Number) p.args[1]).longValue();
                                s.playerType = "Pangle SDK Player";
                                s.nativePlayer = true;
                                s.nativeProgress = true;
            if (duration > 0) {
                updatePangleObservedSpeed(s, current);
                s.progress = Math.max(0, Math.min(100,
                        current * 100 / duration)) + "%";
                if (current >= duration) {
                    s.mediaEnded = true;
                    if (!s.rewarded) complete(s, "fim real confirmado pelo progresso nativo do Pangle");
                    else {
                        s.status = "Pangle terminou a mídia; aguardando recompensa confirmada pelo SDK • sem som";
                        s.evidence = "fim da mídia nativa Pangle";
                    }
                }
            }
                                updateFooterAsync(s);
                            }
                        });
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Pangle progress hook unavailable for "
                        + name + ": " + t);
            }
        }
    }

    /** Uses Pangle's own speedVideoOrTimer implementation. The same public
     * controller method is called by the SDK's JS bridge with {switch,speed};
     * this changes the real player/timer and leaves reward callbacks untouched. */
    private static void hookPangleNativeAccelerator(ClassLoader loader) {
        Class<?> controller = XposedHelpers.findClassIfExists(
                "com.bytedance.sdk.openadsdk.component.reward.sw.iz", loader);
        Class<?> startInfo = XposedHelpers.findClassIfExists(
                "com.bytedance.sdk.openadsdk.component.reward.rox.rox", loader);
        if (controller == null || startInfo == null) return;
        try {
            XposedHelpers.findAndHookMethod(controller, "sw", long.class, boolean.class,
                    Map.class, startInfo, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Session s = newestSession();
                            if (s == null) {
                                main().postDelayed(() -> applyPangleSpeed(newestSession(), p.thisObject), 180);
                            } else {
                                applyPangleSpeed(s, p.thisObject);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Pangle native accelerator unavailable: " + t);
        }
    }

    private static void applyPangleSpeed(Session s, Object controller) {
        if (s == null || controller == null || !s.active || s.pangleSpeedApplied
                || !s.config.accelerate || s.pangleSpeedAttempts++ >= 8) return;
        try {
            JSONObject command = new JSONObject();
            command.put("switch", 1);
            command.put("speed", (double) Math.min(4, s.config.maxSpeed));
            Object accepted = controller.getClass().getMethod("sw", JSONObject.class)
                    .invoke(controller, command);
            if (Boolean.TRUE.equals(accepted)) {
                s.pangleSpeedApplied = true;
                s.speed = Math.min(4, s.config.maxSpeed);
                s.status = "Aceleração nativa aceita pelo Pangle em " + speedText(s.speed)
                        + "; validando progresso • sem som";
                updateFooterAsync(s);
                XposedBridge.log(TAG + ": Pangle speedVideoOrTimer accepted at 4x");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Pangle speed attempt failed: " + t);
        }
    }

    private static void updatePangleObservedSpeed(Session s, long current) {
        long now = SystemClock.uptimeMillis();
        if (s.pangleSampleWall == 0 || current < s.pangleSamplePosition) {
            s.pangleSampleWall = now;
            s.pangleSamplePosition = current;
            return;
        }
        long wallDelta = now - s.pangleSampleWall;
        long mediaDelta = current - s.pangleSamplePosition;
        if (wallDelta < 1400 || mediaDelta <= 0) return;
        float observed = Math.min(16f, mediaDelta / (float) wallDelta);
        if (observed >= 1.35f) {
            s.status = "Aceleração real confirmada pelo progresso Pangle (~"
                    + String.format(Locale.ROOT, "%.1f", observed) + "x) • sem som";
        } else if (s.pangleSpeedApplied) {
            s.status = "Pangle aceitou 4x, mas este criativo limita o avanço • sem som";
        } else {
            s.status = "Progresso nativo monitorado; aceleração indisponível neste criativo • sem som";
        }
    }

    /** Adapter for the player shipped in Kwai's split_basis APK. It uses the
     * player's own public controls and only observes its real completion
     * dispatch; no reward callback or result is synthesized. */
    private static void hookKwaiPlayer(ClassLoader loader) {
        if (loader == null) return;
        Class<?> base = XposedHelpers.findClassIfExists(
                "com.kwai.video.player.AbstractMediaPlayer", loader);
        if (base != null) {
            try {
                XposedBridge.hookAllMethods(base, "notifyOnCompletion", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Session s = sessionForKwaiPlayer(p.thisObject);
                        if (s != null) {
                            s.mediaEnded = true;
                            s.progress = "mídia concluída";
                            if (!s.rewarded) complete(s, "fim real confirmado pelo player nativo do Kwai");
                            else {
                                s.status = "Player do Kwai terminou; aguardando recompensa confirmada pelo SDK • sem som";
                                s.evidence = "fim do player nativo do Kwai";
                                updateFooterAsync(s);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Kwai completion hook unavailable: " + t);
            }
        }
        String[] players = {
                "com.kwai.video.player.KsMediaPlayerImpl",
                "com.kwai.video.player.KsMediaPlayerAemonImpl",
                "com.kwai.video.player.kwai_player.KwaiMediaPlayer",
                "com.kwai.video.player.kwai_player.KwaiSystemMediaPlayer",
                "com.kwai.video.aemonplayer.AemonMediaPlayer",
                "com.kwai.video.aemonplayer.AemonMediaPlayerAdapter"
        };
        for (String name : players) {
            Class<?> cls = XposedHelpers.findClassIfExists(name, loader);
            if (cls == null) continue;
            try {
                XposedBridge.hookAllMethods(cls, "start", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Session s = newestSession();
                        if (s == null) {
                            handleKwaiShortPlayer(p.thisObject);
                            return;
                        }
                        Object player = p.thisObject;
                        KWAI_PLAYER_SESSIONS.put(player, new WeakReference<>(s));
                        configureKwaiPlayer(s, player);
                    }
                });
            } catch (Throwable ignored) { }
        }
    }

    private static Session sessionForKwaiPlayer(Object player) {
        WeakReference<Session> ref = KWAI_PLAYER_SESSIONS.get(player);
        Session s = ref == null ? null : ref.get();
        return s != null && s.active ? s : newestSession();
    }

    private static void configureKwaiPlayer(Session s, Object player) {
        if (s == null || player == null || !s.active) return;
        if (s.config.muteAds) try {
            player.getClass().getMethod("setVolume", float.class, float.class)
                    .invoke(player, 0f, 0f);
        } catch (Throwable ignored) { }
        float confirmed = 1f;
        if (s.config.accelerate) for (float candidate : speedCandidates(s.config.maxSpeed, 4)) {
            try {
                player.getClass().getMethod("setSpeed", float.class).invoke(player, candidate);
                confirmed = candidate;
                break;
            } catch (Throwable ignored) { }
        }
        s.playerType = "Kwai Native Player";
        s.nativePlayer = true;
        s.speed = confirmed;
        s.status = confirmed > 1f
                ? "Aceleração nativa confirmada em " + speedText(confirmed) + " • sem som"
                : "Player do Kwai protegido; aceleração recusada • sem som";
        updateKwaiProgress(s, player);
        updateFooterAsync(s);
        XposedBridge.log(TAG + ": Kwai player configured at " + speedText(confirmed));
    }

    private static void updateKwaiProgress(Session s, Object player) {
        try {
            long pos = ((Number) player.getClass().getMethod("getCurrentPosition")
                    .invoke(player)).longValue();
            long dur = ((Number) player.getClass().getMethod("getDuration")
                    .invoke(player)).longValue();
            if (dur > 0) s.progress = Math.max(0, Math.min(100, pos * 100 / dur)) + "%";
        } catch (Throwable ignored) { }
    }

    private static void inspectKwaiPlayers(Session s) {
        synchronized (KWAI_PLAYER_SESSIONS) {
            for (Map.Entry<Object, WeakReference<Session>> e : KWAI_PLAYER_SESSIONS.entrySet()) {
                Session owner = e.getValue() == null ? null : e.getValue().get();
                if (owner != s) continue;
                Object player = e.getKey();
                if (s.config.muteAds) try {
                    player.getClass().getMethod("setVolume", float.class, float.class)
                            .invoke(player, 0f, 0f);
                } catch (Throwable ignored) { }
                updateKwaiProgress(s, player);
            }
        }
    }

    private static void completeNewest(String evidence) {
        Session s = newestSession();
        if (s != null) complete(s, evidence);
    }

    private static void complete(Session s, String evidence) {
        if (!s.active || s.completed) return;
        s.completed = true;
        s.evidence = evidence;
        s.progress = "100%";
        s.status = "Conclusão confirmada; fechando pelo controle do anúncio • sem som";
        updateFooterAsync(s);
        main().post(s);
        XposedBridge.log(TAG + ": completion evidence: " + evidence);
    }

    private static final class Session implements Runnable {
        final WeakReference<Activity> activity;
        final long createdAt = SystemClock.uptimeMillis();
        WeakReference<View> overlay;
        volatile String platform;
        volatile String surface;
        volatile String playerType = "detectando";
        volatile String progress = "gerenciado pelo SDK";
        volatile String status = "Analisando aceleração disponível • sem som garantido";
        volatile String evidence = "aguardando conclusão real";
        volatile float speed = 1f;
        volatile AppConfig config;
        volatile boolean active = true;
        volatile boolean started;
        volatile boolean completed;
        volatile boolean mediaEnded;
        volatile boolean nativePlayer;
        volatile boolean nativeProgress;
        volatile boolean pangleSpeedApplied;
        volatile boolean paused;
        volatile boolean closeActionSent;
        volatile boolean rewarded;
        int pangleSpeedAttempts;
        long pangleSampleWall;
        long pangleSamplePosition;
        int closePasses;
        int releasedClosePasses;
        int closeRetryPasses;
        int playableTapCount;
        long lastPlayableTap;

        Session(Activity a, String platform, String surface, AppConfig config) {
            activity = new WeakReference<>(a);
            this.platform = platform;
            this.surface = surface;
            this.config = config == null ? AppConfig.defaults() : config;
            this.rewarded = lower(a.getClass().getName()).contains("reward");
        }

        @Override public void run() {
            Activity a = activity.get();
            if (!active || !usable(a) || SESSIONS.get(a) != this) return;
            try {
                View root = a.getWindow().getDecorView();
                if (config.overlay && root instanceof ViewGroup && root.findViewWithTag(OVERLAY_TAG) == null) {
                    // Some SDKs replace their Fragment/content after onPostResume.
                    // Reattach the same session instead of losing the shield.
                    installOverlay(this, (ViewGroup) root);
                }
                View ov = root.findViewWithTag(OVERLAY_TAG);
                if (ov != null) ov.bringToFront();
                scanTree(this, root);
                inspectMediaPlayers(this);
                inspectKwaiPlayers(this);
                if (config.playableHelper && !completed && !nativePlayer && !nativeProgress
                        && "sdk".equals(progress)
                        && SystemClock.uptimeMillis() - createdAt >= 4000
                        && playableTapCount < 16
                        && SystemClock.uptimeMillis() - lastPlayableTap >= 2500) {
                    if (nudgePlayable(root, playableTapCount)) {
                        playableTapCount++;
                        lastPlayableTap = SystemClock.uptimeMillis();
                        playerType = "WebView/Playable protegido";
                        status = "Playable avançando automaticamente; links bloqueados • sem som";
                    }
                }
                boolean completionControlClicked = false;
                if (config.autoClose && !completed && mediaEnded && "Unity Ads".equals(platform)) {
                    completionControlClicked = clickNativeClose(root);
                    if (completionControlClicked) {
                        complete(this, "fim real da mídia e controle de conclusão liberado pelo Unity Ads");
                    }
                }
                if (config.autoClose && !completed && mediaEnded && !rewarded) {
                    boolean released = hasNativeClose(root) || hasAccessibilityClose(root);
                    releasedClosePasses = released ? releasedClosePasses + 1 : 0;
                    if (releasedClosePasses >= 2
                            && (!closeActionSent || ++closeRetryPasses >= 8)) {
                        if (clickNativeClose(root) || clickAccessibilityClose(root)) {
                            closeActionSent = true;
                            closeRetryPasses = 0;
                            evidence = "controle real de fechar liberado após fim da mídia";
                            status = "Controle real acionado; aguardando a tela do anúncio encerrar • sem som";
                            XposedBridge.log(TAG + ": released native close control clicked");
                        }
                    }
                }
                if (config.autoClose && completed) {
                    boolean clicked = completionControlClicked || clickNativeClose(root) || clickAccessibilityClose(root);
                    if (clicked) {
                        status = "Controle de fechar acionado após conclusão • sem som";
                    } else if (++closePasses >= MAX_SDK_CLOSE_PASSES) {
                        status = "Conclusão confirmada; aguardando controle real do SDK • sem som";
                        updateFooter(this);
                    }
                }
                updateFooter(this);
            } catch (Throwable t) {
                status = "Proteção ativa; player gerenciado pelo SDK • sem som";
                XposedBridge.log(TAG + ": session pass failure: " + t);
            }
            if (active) main().postDelayed(this, paused ? 1500 : (completed ? TICK_AFTER_DONE_MS : TICK_MS));
        }
    }

    private static void scanTree(Session s, View view) {
        String cls = lower(view.getClass().getName());
        if (cls.contains("media3.ui.playerview") || cls.contains("exoplayer2.ui.playerview")) {
            inspectExoPlayer(s, view, cls.contains("media3") ? "Media3/ExoPlayer" : "ExoPlayer");
        }
        if (view instanceof WebView) inspectWeb(s, (WebView) view);
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View child = g.getChildAt(i);
                if (!OVERLAY_TAG.equals(child.getTag())) scanTree(s, child);
            }
        }
    }

    private static void inspectExoPlayer(Session s, View playerView, String type) {
        try {
            Object player = playerView.getClass().getMethod("getPlayer").invoke(playerView);
            if (player == null) return;
            s.playerType = type;
            s.nativePlayer = true;
            if (s.config.muteAds) try { player.getClass().getMethod("setVolume", float.class).invoke(player, 0f); }
            catch (Throwable ignored) { }
            int state = readState(player);
            s.progress = playerProgress(player);
            if (state == 4) {
                s.mediaEnded = true;
                if (!s.rewarded) complete(s, "fim real do " + type);
                else {
                    s.status = "Vídeo concluído; aguardando recompensa confirmada pelo SDK • sem som";
                    s.evidence = "fim real do " + type;
                    updateFooterAsync(s);
                }
                return;
            }
            if (s.speed <= 1f) {
                s.speed = setPlayerSpeed(player);
                s.status = s.speed > 1f
                        ? "Aceleração confirmada em " + speedText(s.speed) + " • sem som"
                        : "Player protegido, mas o SDK não permite acelerar • sem som";
            }
        } catch (Throwable ignored) {
            s.playerType = type;
            s.status = "Player protegido; aceleração não confirmada pelo SDK • sem som";
        }
    }

    private static float setPlayerSpeed(Object player) {
        Session s = newestSession();
        if (s == null || !s.config.accelerate || s.config.maxSpeed <= 1) return 1f;
        for (float candidate : speedCandidates(s.config.maxSpeed, 8)) {
            try {
                player.getClass().getMethod("setPlaybackSpeed", float.class).invoke(player, candidate);
                return candidate;
            } catch (NoSuchMethodException e) { break; }
            catch (Throwable ignored) { }
        }
        // R8 commonly renames get/setPlaybackParameters. Resolve the immutable
        // PlaybackParameters object structurally instead of depending on names.
        try {
            for (Method getter : player.getClass().getMethods()) {
                if (getter.getParameterTypes().length != 0) continue;
                Class<?> parameterType = getter.getReturnType();
                if (!parameterType.getName().startsWith("androidx.media3.common.")) continue;
                Object current;
                try { current = getter.invoke(player); } catch (Throwable ignored) { continue; }
                if (current == null) continue;
                for (Method withSpeed : parameterType.getMethods()) {
                    if (withSpeed.getParameterTypes().length != 1
                            || withSpeed.getParameterTypes()[0] != float.class
                            || withSpeed.getReturnType() != parameterType) continue;
                    for (float candidate : speedCandidates(s.config.maxSpeed, 8)) {
                        Object changed;
                        try { changed = withSpeed.invoke(current, candidate); }
                        catch (Throwable ignored) { continue; }
                        for (Method setter : player.getClass().getMethods()) {
                            if (setter.getParameterTypes().length == 1
                                    && setter.getParameterTypes()[0] == parameterType
                                    && setter.getReturnType() == void.class) {
                                try {
                                    setter.invoke(player, changed);
                                    XposedBridge.log(TAG + ": obfuscated Media3 player accelerated to "
                                            + candidate + "x");
                                    return candidate;
                                } catch (Throwable ignored) { }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return 1f;
    }

    private static int readState(Object player) {
        try { return (Integer) player.getClass().getMethod("getPlaybackState").invoke(player); }
        catch (Throwable ignored) { }
        try {
            int v = (Integer) player.getClass().getMethod("f0").invoke(player);
            return v >= 1 && v <= 4 ? v : -1;
        } catch (Throwable ignored) { return -1; }
    }

    private static String playerProgress(Object player) {
        try {
            long pos = ((Number) player.getClass().getMethod("getCurrentPosition").invoke(player)).longValue();
            long dur = ((Number) player.getClass().getMethod("getDuration").invoke(player)).longValue();
            if (dur > 0) return Math.max(0, Math.min(100, pos * 100 / dur)) + "%";
        } catch (Throwable ignored) { }
        return "gerenciado pelo SDK";
    }

    private static void inspectWeb(Session s, WebView web) {
        if (!s.nativePlayer && "detectando".equals(s.playerType)) s.playerType = "WebView/HTML5";
        try {
            web.evaluateJavascript(WEB_PROBE_PREFIX
                    + ((s.config.autoClose && (s.completed || s.closeActionSent
                    || (s.mediaEnded && !s.rewarded))) ? "true" : "false")
                    + ");", value -> {
                if (!s.active) return;
                String result = cleanJs(value);
                String[] parts = result.split("\\|", -1);
                if (parts.length >= 4) {
                    if (!s.nativePlayer && !s.nativeProgress) s.progress = parts[1];
                    if (!s.nativePlayer && !s.nativeProgress) {
                        try { s.speed = Float.parseFloat(parts[2]); } catch (Throwable ignored) { }
                    }
                    if ("1".equals(parts[0])) {
                        s.mediaEnded = true;
                        if (!s.rewarded) complete(s, "fim real do vídeo HTML5");
                        else {
                            s.progress = "mídia concluída";
                            s.status = "Vídeo HTML5 concluído; aguardando recompensa confirmada pelo SDK • sem som";
                            s.evidence = "fim real do vídeo HTML5";
                        }
                    }
                    if (!s.completed && s.mediaEnded && "Unity Ads".equals(s.platform)
                            && "1".equals(parts[3])) {
                        complete(s, "fim real da mídia e controle Web de conclusão liberado pelo Unity Ads");
                    }
                    if (s.config.autoClose && !s.completed && s.mediaEnded && !s.rewarded
                            && "1".equals(parts[3])) {
                        s.releasedClosePasses++;
                        if (s.releasedClosePasses >= 2 && !s.closeActionSent) {
                            s.closeActionSent = true;
                            s.evidence = "controle Web real de fechar liberado pelo SDK";
                            s.status = "Controle Web liberado; acionando e aguardando a tela encerrar • sem som";
                            updateFooterAsync(s);
                        }
                    }
                    if (!s.completed && !s.nativePlayer && !s.nativeProgress) s.status = s.speed > 1f
                            ? "Aceleração HTML5 confirmada em " + speedText(s.speed) + " • sem som"
                            : "WebView protegido; temporização gerenciada pelo SDK • sem som";
                }
            });
        } catch (Throwable ignored) {
            s.status = "WebView protegido; aceleração bloqueada pelo SDK • sem som";
        }
    }

    private static void inspectMediaPlayers(Session s) {
        synchronized (MEDIA_SESSIONS) {
            for (Map.Entry<MediaPlayer, WeakReference<Session>> e : MEDIA_SESSIONS.entrySet()) {
                Session owner = e.getValue() == null ? null : e.getValue().get();
                if (owner != s) continue;
                MediaPlayer player = e.getKey();
                if (s.config.muteAds) try { player.setVolume(0f, 0f); } catch (Throwable ignored) { }
                try {
                    int duration = player.getDuration();
                    int position = player.getCurrentPosition();
                    if (duration > 0) s.progress = Math.max(0,
                            Math.min(100, position * 100L / duration)) + "%";
                } catch (Throwable ignored) { }
            }
        }
    }

    private static boolean clickNativeClose(View view) {
        if (isCloseControl(view) && view.isShown() && view.isEnabled() && view.isClickable()
                && view.getAlpha() > 0.1f && view.getWidth() > 0 && view.getHeight() > 0) {
            try { return view.performClick(); } catch (Throwable ignored) { }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View child = g.getChildAt(i);
                if (!OVERLAY_TAG.equals(child.getTag()) && clickNativeClose(child)) return true;
            }
        }
        return false;
    }

    private static boolean hasNativeClose(View view) {
        if (isCloseControl(view) && view.isShown() && view.isEnabled() && view.isClickable()
                && view.getAlpha() > 0.1f && view.getWidth() > 0 && view.getHeight() > 0) return true;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View child = g.getChildAt(i);
                if (!OVERLAY_TAG.equals(child.getTag()) && hasNativeClose(child)) return true;
            }
        }
        return false;
    }

    private static boolean hasAccessibilityClose(View root) {
        AccessibilityNodeInfo node = null;
        try {
            node = root.createAccessibilityNodeInfo();
            return findAccessibilityClose(node, false);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (node != null) try { node.recycle(); } catch (Throwable ignored) { }
        }
    }

    private static boolean nudgePlayable(View root, int pass) {
        WebView web = findLargestWebView(root);
        if (web == null || !web.isShown() || web.getWidth() < 40 || web.getHeight() < 40) return false;
        // Cycle inside the central safe area.  Edges normally contain Close,
        // Privacy and install CTAs; external navigation remains blocked by the
        // Instrumentation/WebView hooks even if a creative misbehaves.
        final float[][] points = {
                {0.50f, 0.50f}, {0.50f, 0.64f}, {0.38f, 0.52f}, {0.62f, 0.52f},
                {0.50f, 0.38f}, {0.42f, 0.68f}, {0.58f, 0.68f}, {0.50f, 0.56f}
        };
        float[] point = points[Math.abs(pass) % points.length];
        float x = web.getWidth() * point[0];
        float y = web.getHeight() * point[1];
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 45, MotionEvent.ACTION_UP, x, y, 0);
        try {
            boolean accepted = web.dispatchTouchEvent(down);
            accepted |= web.dispatchTouchEvent(up);
            if (accepted) XposedBridge.log(TAG + ": protected playable nudge " + (pass + 1));
            return accepted;
        } catch (Throwable ignored) {
            return false;
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static boolean clickAccessibilityClose(View root) {
        AccessibilityNodeInfo node = null;
        try {
            node = root.createAccessibilityNodeInfo();
            return findAccessibilityClose(node, true);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (node != null) try { node.recycle(); } catch (Throwable ignored) { }
        }
    }

    private static boolean findAccessibilityClose(AccessibilityNodeInfo node, boolean click) {
        if (node == null) return false;
        String label = lower(String.valueOf(node.getText()) + " "
                + String.valueOf(node.getContentDescription()) + " "
                + String.valueOf(node.getViewIdResourceName()) + " "
                + String.valueOf(node.getClassName()));
        boolean skip = label.contains("skip") || label.contains("pular");
        boolean close = label.contains("close") || label.contains("dismiss")
                || label.contains("fechar") || label.contains("concluir")
                || label.contains("continuar") || label.contains("done")
                || label.contains("xmark");
        if (!skip && close && node.isVisibleToUser() && node.isEnabled()
                && (node.isClickable() || node.isFocusable())) {
            if (!click) return true;
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        int count = Math.min(node.getChildCount(), 200);
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                if (child != null && findAccessibilityClose(child, click)) return true;
            } catch (Throwable ignored) {
            } finally {
                if (child != null) try { child.recycle(); } catch (Throwable ignored) { }
            }
        }
        return false;
    }

    private static boolean isCloseControl(View v) {
        StringBuilder b = new StringBuilder(lower(v.getClass().getName())).append(' ');
        CharSequence d = v.getContentDescription();
        if (d != null) b.append(d).append(' ');
        if (v instanceof TextView) b.append(((TextView) v).getText()).append(' ');
        try {
            int id = v.getId();
            if (id != View.NO_ID) b.append(v.getResources().getResourceEntryName(id));
        } catch (Throwable ignored) { }
        String x = lower(b.toString());
        if (x.contains("skip") || x.contains("pular")) return false;
        return x.contains("close") || x.contains("dismiss") || x.contains("fechar")
                || x.contains("concluir") || x.contains("continuar") || x.contains("xmark");
    }

    private static Session newestSession() {
        Session best = null;
        synchronized (SESSIONS) {
            for (Session s : SESSIONS.values()) {
                Activity a = s == null ? null : s.activity.get();
                if (s != null && s.active && usable(a)
                        && (best == null || s.createdAt > best.createdAt)) best = s;
            }
        }
        return best;
    }

    @SuppressLint("SetTextI18n")
    private static void updateFooter(Session s) {
        Activity a = s.activity.get();
        if (!usable(a)) return;
        try {
            View f = a.getWindow().getDecorView().findViewWithTag(FOOTER_TAG);
            if (f instanceof TextView) {
                ((TextView) f).setText("Plataforma: " + s.platform + "  •  Superfície: " + s.surface
                        + "  •  Player: " + s.playerType + "\nStatus: " + s.status
                        + "  •  Progresso: " + s.progress + "  •  Evidência: " + s.evidence);
            }
        } catch (Throwable ignored) { }
    }

    private static void updateFooterAsync(Session s) { main().post(() -> updateFooter(s)); }

    private static String platformFrom(String activityClass, View root) {
        String all = activityClass + " " + treeClassNames(root, new StringBuilder(), 0);
        if (all.contains("kwad")) return "Kwai/Kwad Ads";
        if (all.contains("bytedance") || all.contains("pangle")) return "Pangle";
        if (all.contains("applovin")) return "AppLovin MAX";
        if (all.contains("unity3d")) return "Unity Ads";
        if (all.contains("google.android.gms.ads")) return "Google Ads";
        if (all.contains("ironsource")) return "ironSource";
        if (all.contains("vungle")) return "Vungle/Liftoff";
        if (all.contains("mbridge") || all.contains("mintegral")) return "Mintegral";
        if (all.contains("vast") || all.contains("mobicare.aa.ads")) return "VAST/Agregador";
        return "SDK detectado";
    }

    private static String treeClassNames(View v, StringBuilder b, int count) {
        if (v == null || count > 160) return b.toString();
        b.append(' ').append(lower(v.getClass().getName()));
        int next = count + 1;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && next <= 160; i++, next++)
                treeClassNames(g.getChildAt(i), b, next);
        }
        return b.toString();
    }

    private static final class Detection {
        static final Detection NO = new Detection(false, "", "");
        final boolean ad;
        final String platform;
        final String surface;
        Detection(boolean ad, String platform, String surface) {
            this.ad = ad; this.platform = platform; this.surface = surface;
        }
    }

    private static final class BlockingOverlay extends FrameLayout {
        BlockingOverlay(Context c) { super(c); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }

    private static boolean usable(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    private static WebView findLargestWebView(View root) {
        WebView best = root instanceof WebView ? (WebView) root : null;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                WebView c = findLargestWebView(g.getChildAt(i));
                if (c != null && (best == null || area(c) > area(best))) best = c;
            }
        }
        return best;
    }

    private static long area(View v) { return (long) v.getWidth() * v.getHeight(); }

    private static boolean stackLooksLikeAd() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace())
            if (containsAny(lower(e.getClassName()), SDK_MARKERS)) return true;
        return false;
    }

    private static boolean containsAny(String value, String[] needles) {
        if (value == null) return false;
        for (String n : needles) if (value.contains(n)) return true;
        return false;
    }

    private static String cleanJs(String v) {
        if (v == null || v.equals("null")) return "0|sdk|1|0";
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"')
            v = v.substring(1, v.length() - 1);
        return v.replace("\\\"", "\"").replace("\\n", " ");
    }

    private static String speedText(float speed) {
        return speed == (int) speed ? ((int) speed) + "x" : speed + "x";
    }

    private static float[] speedCandidates(int configured, int hardMax) {
        int max = Math.max(1, Math.min(configured, hardMax));
        if (max >= 8) return new float[]{8f, 6f, 4f, 3f, 2f};
        if (max >= 6) return new float[]{6f, 4f, 3f, 2f};
        if (max >= 4) return new float[]{4f, 3f, 2f};
        if (max >= 3) return new float[]{3f, 2f};
        if (max >= 2) return new float[]{2f};
        return new float[0];
    }

    private static boolean shouldMuteNow() {
        Session s = newestSession();
        if (s != null) return s.config.muteAds;
        Activity kwai = CURRENT_KWAI_ACTIVITY == null ? null : CURRENT_KWAI_ACTIVITY.get();
        if (kwai == null) return false;
        AppConfig config = AppConfig.load(kwai, kwai.getPackageName());
        return config.muteKwaiShorts && kwaiShortFeedVisible(kwai);
    }

    private static void hookKwaiUi(ClassLoader loader) {
        Class<?> view = XposedHelpers.findClassIfExists("android.view.View", loader);
        if (view != null) {
            try {
                XposedBridge.hookAllMethods(view, "performClick", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        Activity a = CURRENT_KWAI_ACTIVITY == null ? null : CURRENT_KWAI_ACTIVITY.get();
                        if (!usable(a)) return;
                        AppConfig config = AppConfig.load(a, a.getPackageName());
                        if (config.blockKwaiShorts && isResourceName((View) p.thisObject,
                                "id_home_bottom_tab_home")) {
                            p.setResult(false);
                            forceKwaiGames(a, config);
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Kwai UI click hook unavailable: " + t);
            }
        }
    }

    private static void handleKwaiUi(Activity a) {
        if (!usable(a) || !"com.kwai.video".equals(a.getPackageName())) return;
        CURRENT_KWAI_ACTIVITY = new WeakReference<>(a);
        AppConfig config = AppConfig.load(a, a.getPackageName());
        if (config.repairKwaiGoldTouch) repairKwaiGoldTouch(a);
        if (config.forceKwaiGames || config.blockKwaiShorts) forceKwaiGames(a, config);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void configureKwaiWebView(WebView web) {
        try {
            if (web == null || web.getContext() == null
                    || !"com.kwai.video".equals(web.getContext().getPackageName())) return;
            AppConfig config = AppConfig.load(web.getContext(), "com.kwai.video");
            if (!config.repairKwaiWebNetwork) return;
            WebSettings settings = web.getSettings();
            if (settings != null) {
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                settings.setJavaScriptEnabled(true);
                settings.setLoadsImagesAutomatically(true);
                settings.setBlockNetworkLoads(false);
                settings.setBlockNetworkImage(false);
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            }
            try {
                CookieManager manager = CookieManager.getInstance();
                manager.setAcceptCookie(true);
                manager.setAcceptThirdPartyCookies(web, true);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Kwai WebView compatibility failed: " + t);
        }
    }

    private static boolean isKwaiGoldWebView(WebView web) {
        try {
            if (web == null || web.getContext() == null
                    || !"com.kwai.video".equals(web.getContext().getPackageName())) return false;
            AppConfig config = AppConfig.load(web.getContext(), "com.kwai.video");
            if (!config.repairKwaiWebNetwork) return false;
            String url = lower(web.getUrl());
            if (url.contains("incentive.kwai") || url.contains("ug-center")
                    || url.contains("ugcenter") || url.contains("gold")) return true;
            Activity a = CURRENT_KWAI_ACTIVITY == null ? null : CURRENT_KWAI_ACTIVITY.get();
            String cls = a == null ? "" : lower(a.getClass().getName());
            return cls.contains("kwairnactivity") || cls.contains("overseawebactivity");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void repairKwaiGoldTouch(Activity a) {
        String cls = lower(a.getClass().getName());
        if (!cls.contains("kwairnactivity") && !cls.contains("overseawebactivity")) return;
        try {
            Window w = a.getWindow();
            if (w != null) {
                w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                View decor = w.getDecorView();
                if (decor != null) {
                    decor.setEnabled(true);
                    if (decor instanceof ViewGroup) removeStaleOverlay((ViewGroup) decor);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Kwai Gold touch repair failed: " + t);
        }
    }

    private static void forceKwaiGames(Activity a, AppConfig config) {
        if (!usable(a)) return;
        View root = a.getWindow() == null ? null : a.getWindow().getDecorView();
        if (!(root instanceof ViewGroup)) return;
        if (config.forceKwaiGames && gameCenterVisible(root)) return;
        View game = findByResourceName(root, "id_bottom_tab_explore");
        View home = findByResourceName(root, "id_home_bottom_tab_home");
        if (config.blockKwaiShorts && home != null) {
            home.setEnabled(false);
            home.setClickable(false);
            home.setAlpha(Math.min(home.getAlpha(), 0.45f));
        }
        if (!config.forceKwaiGames) return;
        if (game == null) {
            // O TinyLaunchActivity de algumas versoes renderiza a barra sem IDs
            // Android. A aba Jogo ocupa o segundo item da barra inferior.
            boolean accepted = tapThroughActivity(a, root.getWidth() * 0.30f,
                    root.getHeight() * 0.981f);
            XposedBridge.log(TAG + ": Kwai Games fallback tap, accepted=" + accepted);
            return;
        }
        if (game != null && game.isShown() && game.isEnabled()) {
            try {
                boolean accepted = game.performClick();
                // Algumas versoes do Kwai retornam true em performClick(), mas o
                // listener interno ignora cliques sinteticos no filho. Despachar
                // pelo Activity percorre a mesma hierarquia de um toque real.
                accepted |= tapThroughActivity(a, game);
                XposedBridge.log(TAG + ": Kwai forced to Games tab, accepted=" + accepted);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Kwai Games click failed: " + t);
            }
        }
    }

    private static boolean tapThroughActivity(Activity activity, View target) {
        if (!usable(activity) || target == null || target.getWidth() <= 0 || target.getHeight() <= 0)
            return false;
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) return tapCenter(target);
        int[] targetPosition = new int[2];
        int[] decorPosition = new int[2];
        target.getLocationOnScreen(targetPosition);
        decor.getLocationOnScreen(decorPosition);
        float x = targetPosition[0] - decorPosition[0] + target.getWidth() / 2f;
        float y = targetPosition[1] - decorPosition[1] + target.getHeight() / 2f;
        return tapThroughActivity(activity, x, y);
    }

    private static boolean tapThroughActivity(Activity activity, float x, float y) {
        if (!usable(activity) || x < 0 || y < 0) return false;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 55, MotionEvent.ACTION_UP, x, y, 0);
        try {
            boolean accepted = activity.dispatchTouchEvent(down);
            accepted |= activity.dispatchTouchEvent(up);
            return accepted;
        } catch (Throwable ignored) {
            return false;
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static boolean tapCenter(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        long now = SystemClock.uptimeMillis();
        float x = view.getWidth() / 2f;
        float y = view.getHeight() / 2f;
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 55, MotionEvent.ACTION_UP, x, y, 0);
        try {
            boolean accepted = view.dispatchTouchEvent(down);
            accepted |= view.dispatchTouchEvent(up);
            return accepted;
        } catch (Throwable ignored) {
            return false;
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static void handleKwaiShortPlayer(Object player) {
        Activity a = CURRENT_KWAI_ACTIVITY == null ? null : CURRENT_KWAI_ACTIVITY.get();
        if (!usable(a) || !"com.kwai.video".equals(a.getPackageName())) return;
        AppConfig config = AppConfig.load(a, a.getPackageName());
        if (!config.muteKwaiShorts || !kwaiShortFeedVisible(a)) return;
        try {
            player.getClass().getMethod("setVolume", float.class, float.class).invoke(player, 0f, 0f);
        } catch (Throwable ignored) {
        }
        try {
            player.getClass().getMethod("setVolume", float.class).invoke(player, 0f);
        } catch (Throwable ignored) {
        }
    }

    private static boolean kwaiShortFeedVisible(Activity a) {
        View root = a.getWindow() == null ? null : a.getWindow().getDecorView();
        if (root == null) return false;
        return findByResourceName(root, "detail_player_view") != null
                || findByResourceName(root, "slide_play_root_layout") != null;
    }

    private static boolean gameCenterVisible(View root) {
        return treeClassNames(root, new StringBuilder(), 0).contains("krnreactrootview")
                && (findByResourceName(root, "id_bottom_tab_explore") != null);
    }

    private static void removeStaleOverlay(ViewGroup root) {
        View overlay = root.findViewWithTag(OVERLAY_TAG);
        if (overlay != null && overlay.getParent() instanceof ViewGroup)
            ((ViewGroup) overlay.getParent()).removeView(overlay);
    }

    private static View findByResourceName(View root, String entryName) {
        if (isResourceName(root, entryName)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findByResourceName(g.getChildAt(i), entryName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isResourceName(View v, String entryName) {
        try {
            int id = v.getId();
            return id != View.NO_ID && entryName.equals(v.getResources().getResourceEntryName(id));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static Handler main() {
        Handler h = MAIN;
        if (h != null) return h;
        synchronized (UniversalAdShield.class) {
            if (MAIN == null) MAIN = new Handler(Looper.getMainLooper());
            return MAIN;
        }
    }
}
