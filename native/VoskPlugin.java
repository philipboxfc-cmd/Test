package com.philipbox.akkordy;

import android.Manifest;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

/** Офлайн-распознавание речи (Vosk), не зависит от сервисов Google/Huawei. */
@CapacitorPlugin(
    name = "Vosk",
    permissions = { @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "microphone") }
)
public class VoskPlugin extends Plugin {

    private Model model;
    private String modelError;
    private boolean loading;
    private final List<Runnable> waiting = new ArrayList<>();
    private SpeechService service;
    private Recognizer recognizer;

    @Override
    public void load() {
        loadModel();
    }

    // Колбэки StorageService приходят в главный поток
    private void loadModel() {
        if (model != null || loading) return;
        loading = true;
        modelError = null;
        StorageService.unpack(
            getContext(),
            "model-ru",
            "model",
            m -> {
                model = m;
                loading = false;
                flush();
            },
            e -> {
                modelError = e.getMessage();
                loading = false;
                flush();
            }
        );
    }

    private void flush() {
        List<Runnable> list = new ArrayList<>(waiting);
        waiting.clear();
        for (Runnable r : list) r.run();
    }

    private void whenReady(Runnable r) {
        getActivity().runOnUiThread(() -> {
            if (model != null) {
                r.run();
                return;
            }
            waiting.add(r);
            if (!loading) loadModel();
        });
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "micPermission");
            return;
        }
        begin(call);
    }

    @PermissionCallback
    private void micPermission(PluginCall call) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) begin(call);
        else call.reject("Нет разрешения на микрофон");
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            stopService();
            call.resolve();
        });
    }

    private void begin(PluginCall call) {
        whenReady(() -> {
            if (model == null) {
                call.reject("Модель речи не загружена: " + modelError);
                return;
            }
            try {
                stopService();
                String grammar = call.getString("grammar", null);
                recognizer = grammar != null ? new Recognizer(model, 16000f, grammar) : new Recognizer(model, 16000f);
                service = new SpeechService(recognizer, 16000f);
                service.startListening(new Listener(call), call.getInt("timeout", 8000));
            } catch (Exception e) {
                stopService();
                call.reject(e.getMessage());
            }
        });
    }

    private void stopService() {
        if (service != null) {
            service.stop();
            service.shutdown();
            service = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
    }

    private static String text(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "").replace("[unk]", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private class Listener implements RecognitionListener {

        private final PluginCall call;
        private String last = "";
        private boolean done;

        Listener(PluginCall call) {
            this.call = call;
        }

        private void finish(String t) {
            if (done) return;
            done = true;
            stopService();
            JSArray matches = new JSArray();
            if (t != null && !t.isEmpty()) matches.put(t);
            JSObject r = new JSObject();
            r.put("matches", matches);
            call.resolve(r);
        }

        @Override
        public void onPartialResult(String h) {
            String t = text(h, "partial");
            if (!t.isEmpty()) last = t;
        }

        @Override
        public void onResult(String h) {
            String t = text(h, "text");
            if (!t.isEmpty()) finish(t);
        }

        @Override
        public void onFinalResult(String h) {
            String t = text(h, "text");
            finish(t.isEmpty() ? last : t);
        }

        @Override
        public void onError(Exception e) {
            if (done) return;
            done = true;
            stopService();
            call.reject(e.getMessage());
        }

        @Override
        public void onTimeout() {
            finish(last);
        }
    }
}
