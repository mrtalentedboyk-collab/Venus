package com.venus.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_ALL = 1, REQ_FILE = 2, REQ_ONE = 3;
    private static final String HOST = "appassets.androidplatform.net";

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private boolean permsDone = false;
    private String pendingKey = "";
    private boolean pendingOverlay = false;

    // speech
    private final Handler h = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private boolean listening = false, continuous = false;
    private String lang = "en-US";
    private boolean paused = false, hold = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        web.addJavascriptInterface(new Bridge(), "VenusAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> req.grant(req.getResources()));
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, false);
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("https://" + HOST + "/assets/venus.html");
        askAllPermissions();
    }

    // ---------- permissions ----------
    private String[] mediaPerms() {
        return Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}
                : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void askAllPermissions() {
        List<String> want = new ArrayList<>();
        want.add(Manifest.permission.RECORD_AUDIO);
        want.add(Manifest.permission.CAMERA);
        want.add(Manifest.permission.READ_CONTACTS);
        want.add(Manifest.permission.CALL_PHONE);
        want.add(Manifest.permission.ACCESS_FINE_LOCATION);
        want.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        for (String m : mediaPerms()) want.add(m);
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS);

        List<String> need = new ArrayList<>();
        for (String p : want) if (!has(p)) need.add(p);
        if (need.isEmpty()) {
            permsDone = true;
            startVoiceService();
        } else {
            requestPermissions(need.toArray(new String[0]), REQ_ALL);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_ALL) {
            permsDone = true;
            startVoiceService();
        } else if (code == REQ_ONE) {
            boolean ok = res.length > 0;
            for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            js("window.__permDone&&__permDone(" + JSONObject.quote(pendingKey) + "," + ok + ")");
        }
    }

    private void startVoiceService() {
        if (!has(Manifest.permission.RECORD_AUDIO)) return;
        startForegroundService(new Intent(this, VoiceService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingOverlay) {
            pendingOverlay = false;
            js("window.__permDone&&__permDone('overlay'," + Settings.canDrawOverlays(this) + ")");
            return;
        }
        if (!permsDone) return;
        SharedPreferences sp = getSharedPreferences("v", MODE_PRIVATE);
        if (!Settings.canDrawOverlays(this) && !sp.getBoolean("ov", false)) {
            sp.edit().putBoolean("ov", true).apply();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName()) && !sp.getBoolean("bat", false)) {
            sp.edit().putBoolean("bat", true).apply();
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    // ---------- external intents ----------
    private void openExternal(Uri u) {
        try {
            String url = u.toString();
            Intent i;
            if (url.startsWith("intent:")) i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            else if ("tel".equals(u.getScheme()) && has(Manifest.permission.CALL_PHONE))
                i = new Intent(Intent.ACTION_CALL, u);
            else i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "App not found", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not open", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        }
    }

    /** Back button keeps Venus running in the background. */
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        listening = false;
        if (sr != null) { sr.destroy(); sr = null; }
        beepMute(false);
        super.onDestroy();
    }

    // ---------- native speech recognition (WebView has none) ----------
    private void js(final String code) {
        if (web != null) web.post(() -> web.evaluateJavascript(code, null));
    }

    /** Hides the Google "ding" by muting audio for a moment. */
    private void beepMute(boolean m) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int[] streams = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION};
        for (int st : streams) {
            try {
                am.adjustStreamVolume(st, m ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            } catch (Exception ignored) {}
        }
    }

    private void listenOnce() {
        if (!listening || paused || hold) return;
        if (sr != null) { sr.destroy(); sr = null; }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listening = false;
            js("__venusError('audio-capture')");
            return;
        }
        sr = SpeechRecognizer.createSpeechRecognizer(this);
        sr.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle p) {}
            public void onBeginningOfSpeech() {}
            public void onRmsChanged(float v) {}
            public void onBufferReceived(byte[] b) {}
            public void onEndOfSpeech() { beepMute(true); h.postDelayed(() -> beepMute(false), 900); }
            public void onPartialResults(Bundle p) {}
            public void onEvent(int t, Bundle p) {}

            public void onResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (l != null && !l.isEmpty() && !l.get(0).trim().isEmpty())
                    js("__venusResult(" + JSONObject.quote(l.get(0)) + ")");
                next(250);
            }

            public void onError(int e) {
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    listening = false;
                    js("__venusError('not-allowed')");
                    return;
                }
                next(e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1000 : 300);
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        if (Build.VERSION.SDK_INT >= 33)
            i.putExtra(RecognizerIntent.EXTRA_ADDITIONAL_LANGUAGES, new String[]{"bn-BD", "en-US", "hi-IN"});
        beepMute(true);
        sr.startListening(i);
        h.postDelayed(() -> beepMute(false), 700);
    }

    private void next(long delay) {
        if (!listening) return;
        if (continuous) h.postDelayed(this::listenOnce, delay);
        else { listening = false; js("__venusEnd()"); }
    }

    // ---------- JS bridge ----------
    private class Bridge {
        @JavascriptInterface
        public void startListen(final String l, final boolean cont) {
            runOnUiThread(() -> {
                lang = (l == null || l.isEmpty()) ? "en-US" : l;
                continuous = cont;
                listening = true;
                listenOnce();
            });
        }

        @JavascriptInterface
        public void stopListen() {
            runOnUiThread(() -> {
                listening = false;
                h.removeCallbacksAndMessages(null);
                if (sr != null) { sr.destroy(); sr = null; }
                beepMute(false);
            });
        }

        /** Called while Venus is speaking, so it does not hear itself. */
        @JavascriptInterface
        public void pauseListen(final boolean p) {
            runOnUiThread(() -> {
                paused = p;
                h.removeCallbacksAndMessages(null);
                if (sr != null) { sr.destroy(); sr = null; }
                beepMute(false);
                if (p) h.postDelayed(() -> { paused = false; listenOnce(); }, 45000);
                else listenOnce();
            });
        }

        /** Called while a phone task is running. */
        @JavascriptInterface
        public void holdListen(final boolean x) {
            runOnUiThread(() -> {
                hold = x;
                h.removeCallbacksAndMessages(null);
                if (sr != null) { sr.destroy(); sr = null; }
                beepMute(false);
                if (!x) listenOnce();
            });
        }

        @JavascriptInterface
        public boolean a11yReady() {
            return VenusAccessibility.instance != null;
        }

        @JavascriptInterface
        public String screen() {
            VenusAccessibility a = VenusAccessibility.instance;
            return a == null ? "(accessibility is off)" : a.screen();
        }

        @JavascriptInterface
        public String act(String act, int id, String text, String dir) {
            VenusAccessibility a = VenusAccessibility.instance;
            return a == null ? "accessibility is off" : a.act(act, id, text, dir);
        }

        @JavascriptInterface
        public boolean hasPerm(String key) {
            if ("overlay".equals(key)) return Settings.canDrawOverlays(MainActivity.this);
            if ("access".equals(key)) return VenusAccessibility.instance != null;
            if ("contacts".equals(key)) return has(Manifest.permission.READ_CONTACTS);
            if ("gallery".equals(key)) {
                for (String m : mediaPerms()) if (!has(m)) return false;
                return true;
            }
            return false;
        }

        @JavascriptInterface
        public void requestPerm(final String key) {
            runOnUiThread(() -> {
                if ("overlay".equals(key)) {
                    pendingOverlay = true;
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                    return;
                }
                pendingKey = key;
                String[] p = "contacts".equals(key)
                        ? new String[]{Manifest.permission.READ_CONTACTS}
                        : mediaPerms();
                requestPermissions(p, REQ_ONE);
            });
        }

        /** "call Rahim" -> phone number, or "" */
        @JavascriptInterface
        public String findContact(String name) {
            if (name == null || name.trim().isEmpty()) return "";
            if (!has(Manifest.permission.READ_CONTACTS)) return "";
            try (Cursor c = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + name.trim() + "%"}, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            } catch (Exception ignored) {}
            return "";
        }
    }
}
