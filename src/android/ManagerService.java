package com.spoon.backgroundfileupload;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.github.pwittchen.reactivenetwork.library.rx2.ReactiveNetwork;
import com.sromku.simple.storage.SimpleStorage;
import com.sromku.simple.storage.Storage;
import com.sromku.simple.storage.helpers.OrderType;

import net.gotev.uploadservice.UploadService;
import net.gotev.uploadservice.UploadServiceConfig;
import net.gotev.uploadservice.data.UploadInfo;
import net.gotev.uploadservice.data.UploadNotificationConfig;
import net.gotev.uploadservice.data.UploadNotificationStatusConfig;
import net.gotev.uploadservice.exceptions.UserCancelledUploadException;
import net.gotev.uploadservice.network.ServerResponse;
import net.gotev.uploadservice.observer.request.GlobalRequestObserver;
import net.gotev.uploadservice.observer.request.RequestObserverDelegate;
import net.gotev.uploadservice.okhttp.OkHttpStack;
import net.gotev.uploadservice.protocols.multipart.MultipartUploadRequest;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class ManagerService extends Service {

    private final IBinder mBinder = new LocalBinder();
    private GlobalRequestObserver requestObserver;
    private Long lastProgressTimestamp = 0L;
    private Activity mainActivity;
    private CallbackContext uploadCallback;
    private Disposable networkObservable;
    private boolean isNetworkAvailable = false;
    private boolean ready = false;
    private String inputTitle = "Default title";
    private String inputContent = "Default content";

    private static final String CHANNEL_ID = "com.spoon.backgroundfileupload.channel";

    private RequestObserverDelegate broadcastReceiver = new RequestObserverDelegate() {
        @Override
        public void onProgress(Context context, UploadInfo uploadInfo) {
            Long currentTimestamp = System.currentTimeMillis() / 1000;
            if (currentTimestamp - lastProgressTimestamp >= 1) {
                lastProgressTimestamp = currentTimestamp;

                JSONObject data = new JSONObject(new HashMap() {{
                    put("id", uploadInfo.getUploadId());
                    put("progress", uploadInfo.getProgressPercent());
                    put("state", "UPLOADING");
                }});

                sendCallback(data);
            }
        }

        @Override
        public void onError(final Context context, final UploadInfo uploadInfo, final Throwable exception) {
            String errorMsg = exception != null ? exception.getMessage() : "";

            JSONObject data = new JSONObject(new HashMap() {{
                put("id", uploadInfo.getUploadId());
                put("state", "FAILED");
                put("error", "upload failed: " + errorMsg);
                put("errorCode", exception instanceof UserCancelledUploadException ? -999 : 0);
            }});

            deletePendingUploadAndSendEvent(data);
        }

        @Override
        public void onSuccess(Context context, UploadInfo uploadInfo, ServerResponse serverResponse) {
            JSONObject data = new JSONObject(new HashMap() {{
                put("id", uploadInfo.getUploadId());
                put("state", "UPLOADED");
                put("serverResponse", serverResponse.getBodyString());
                put("statusCode", serverResponse.getCode());
            }});

            deletePendingUploadAndSendEvent(data);
            logMessage("onSuccess: " + data);
        }

        @Override
        public void onCompleted(Context context, UploadInfo uploadInfo) {
            stopService();
        }

        @Override
        public void onCompletedWhileNotObserving() {
        }
    };

    public void stopService() {
        if (PendingUpload.count(PendingUpload.class) == 0) {
            if (!ready) {
                Intent intent = new Intent(ManagerService.this, ManagerService.class);
                stopService(intent);

                requestObserver.unregister();
                requestObserver = null;
            } else {
                Intent intent = new Intent(ManagerService.this, ManagerService.class);
                stopService(intent);

                foregroundNotification(this.inputTitle, this.inputContent);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        initUploadService();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                JSONObject settings = new JSONObject(intent.getStringExtra("options"));
                this.inputTitle = settings.getString("foregroundTitle");
                this.inputContent = settings.getString("foregroundContent");
            } catch (JSONException error) {
                error.getLocalizedMessage();
            }

            foregroundNotification(inputTitle, inputContent);
        }

        this.requestObserver = new GlobalRequestObserver(this.getApplication(), broadcastReceiver);
        this.requestObserver.register();

        int parallelUploadsLimit = 1;

        try {
            JSONObject settings = new JSONObject(intent.getStringExtra("options"));
            parallelUploadsLimit = settings.getInt("parallelUploadsLimit");
        } catch (JSONException error) {
            ManagerService.logMessage(String.format("eventLabel='Uploader could not read parallelUploadsLimit from config' error='%s'", error.getMessage()));
        }

        UploadServiceConfig.setNotificationHandlerFactory((uploadService) -> new NotificationHandler(uploadService, mainActivity));

        UploadServiceConfig.setHttpStack(new OkHttpStack());
        ExecutorService threadPoolExecutor =
                new ThreadPoolExecutor(
                        parallelUploadsLimit,
                        parallelUploadsLimit,
                        5000,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<Runnable>()
                );
        UploadServiceConfig.setThreadPool((AbstractExecutorService) threadPoolExecutor);

        networkObservable = ReactiveNetwork
                .observeNetworkConnectivity(this)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(connectivity -> {
                    logMessage(String.format("eventLabel='Uploader Network connectivity changed' connectivity_state='%s'", connectivity.state()));
                    isNetworkAvailable = connectivity.state() == NetworkInfo.State.CONNECTED;
                    if (isNetworkAvailable) {
                        uploadPendingList();
                    }
                });

        return START_NOT_STICKY;
    }

    private void foregroundNotification(String inputTitle, String inputContent) {
        Intent notificationIntent = new Intent(this, FileTransferBackground.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(inputTitle)
                .setContentText(inputContent)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentIntent(pendingIntent)
                .build();
        startForeground(1234, notification);
    }

    public void initUploadService() {
        UploadServiceConfig.initialize(
                getApplication(),
                CHANNEL_ID,
                false
        );
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        this.networkObservable.dispose();
        this.networkObservable = null;
    }

    public class LocalBinder extends Binder {
        public ManagerService getServiceInstance() {
            return ManagerService.this;
        }
    }

    public void sendMissingEvents(Activity activity, CallbackContext uploadCallback) {
        this.mainActivity = activity;
        this.uploadCallback = uploadCallback;

        createUploadChannel();
        migrateOldUploads();

        for (UploadEvent event : UploadEvent.all()) {
            logMessage("Uploader send event missing on Start - " + event.getId());
            sendCallback(event.dataRepresentation());
        }
    }

    public void sendCallback(JSONObject obj) {
        if (ready) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, obj);
            result.setKeepCallback(true);
            uploadCallback.sendPluginResult(result);
        }
    }

    public void deletePendingUploadAndSendEvent(JSONObject obj) {
        String id = null;

        try {
            id = obj.getString("id");
        } catch (JSONException error) {
            logMessage(String.format("eventLabel='Uploader could not delete pending upload' error='%s'", error.getMessage()));
        }

        logMessage(String.format("eventLabel='Uploader delete pending upload' uploadId='%s'", id));
        PendingUpload.remove(id);
        createAndSendEvent(obj);
    }

    public void createAndSendEvent(JSONObject obj) {
        UploadEvent event = UploadEvent.create(obj);
        sendCallback(event.dataRepresentation());
    }

    public void createUploadChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "upload channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) ManagerService.this
                    .getApplication()
                    .getApplicationContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    public void migrateOldUploads() {
        Storage storage = SimpleStorage.getInternalStorage(this);
        String uploadDirectoryName = "FileTransferBackground";
        if (storage.isDirectoryExists(uploadDirectoryName)) {
            for (String uploadId : getOldUploadIds()) {
                UploadEvent event = UploadEvent.create(new JSONObject(new HashMap() {{
                    put("id", uploadId);
                    put("state", "FAILED");
                    put("errorCode", 0);
                    put("error", "upload failed");
                }}));
            }
            // remove all old uploads
            storage.deleteDirectory(uploadDirectoryName);
        }
    }

    private ArrayList<String> getOldUploadIds() {
        Storage storage = SimpleStorage.getInternalStorage(this);
        String uploadDirectoryName = "FileTransferBackground";
        ArrayList<String> previousUploads = new ArrayList();
        List<File> files = storage.getFiles(uploadDirectoryName, OrderType.DATE);
        for (File file : files) {
            if (file.getName().endsWith(".json")) {
                String content = storage.readTextFile(uploadDirectoryName, file.getName());
                if (content != null) {
                    try {
                        previousUploads.add(new JSONObject(content).getString("id"));
                    } catch (JSONException exception) {
                        logMessage(String.format("eventLabel='Uploader could not read old uploads' error='%s'", exception.getMessage()));
                    }
                }
            }
        }
        return previousUploads;
    }

    private void uploadPendingList() {
        List<PendingUpload> previousUploads = PendingUpload.all();

        for (PendingUpload upload : previousUploads) {
            JSONObject obj = null;
            try {
                obj = new JSONObject(upload.data);
            } catch (JSONException exception) {
                logMessage(String.format("eventLabel='Uploader could not parse pending upload' uploadId='%s' error='%s'", upload.uploadId, exception.getMessage()));
            }
            if (obj != null) {
                logMessage(String.format("eventLabel='Uploader upload pending list' uploadId='%s'", upload.uploadId));
                this.startUpload(upload.dataHash());
            }
        }
    }

    private void startUpload(HashMap<String, Object> payload) {
        String uploadId = payload.get("id").toString();

        if (UploadService.getTaskList().contains(uploadId)) {
            logMessage(String.format("eventLabel='Uploader upload is already being uploaded. ignoring re-upload start' uploadId='%s'", uploadId));
            return;
        }

        logMessage(String.format("eventLabel='Uploader starting upload' uploadId='%s'", uploadId));

        if (!isNetworkAvailable) {
            logMessage(String.format("eventLabel='Uploader no network available, upload has been queued' uploadId='%s'", uploadId));
            return;
        }

        MultipartUploadRequest request = null;

        try {
            request = new MultipartUploadRequest(this, payload.get("serverUrl").toString())
                    .setUploadID(uploadId)
                    .setMethod("POST")
                    .addFileToUpload(payload.get("filePath").toString(), payload.get("fileKey").toString())
                    .setMaxRetries(0);
        } catch (IllegalArgumentException | FileNotFoundException error) {
            sendAddingUploadError(uploadId, error);
            return;
        }

        try {
            HashMap<String, Object> headers = convertToHashMap((JSONObject) payload.get("headers"));
            for (String key : headers.keySet()) {
                request.addHeader(key, headers.get(key).toString());
            }
        } catch (JSONException exception) {
            logMessage(String.format("eventLabel='could not parse request headers' uploadId='%s' error='%s'", uploadId, exception.getMessage()));
            sendAddingUploadError(uploadId, exception);
            return;
        }

        try {
            HashMap<String, Object> parameters = convertToHashMap((JSONObject) payload.get("parameters"));
            for (String key : parameters.keySet()) {
                request.addParameter(key, parameters.get(key).toString());
            }
        } catch (JSONException exception) {
            logMessage(String.format("eventLabel='could not parse request parameters' uploadId='%s' error='%s'", uploadId, exception.getMessage()));
            sendAddingUploadError(uploadId, exception);
            return;
        }

        String title = payload.get("notificationTitle").toString();
        request.setNotificationConfig((context, id) -> getNotificationConfiguration(title));
        request.startUpload();
    }

    private void sendAddingUploadError(String uploadId, Exception error) {
        deletePendingUploadAndSendEvent(new JSONObject(new HashMap() {{
            put("id", uploadId);
            put("state", "FAILED");
            put("errorCode", 0);
            put("error", error.getMessage());
        }}));
    }

    public static HashMap<String, Object> convertToHashMap(JSONObject jsonObject) throws JSONException {
        HashMap<String, Object> hashMap = new HashMap<>();
        if (jsonObject != null) {
            Iterator<?> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = (String) keys.next();
                Object value = jsonObject.get(key);
                hashMap.put(key, value);
            }
        }
        return hashMap;
    }

    private UploadNotificationConfig getNotificationConfiguration(String title) {
        UploadNotificationConfig config = new UploadNotificationConfig(
                CHANNEL_ID,
                false,
                buildNotificationStatusConfig(title),
                buildNotificationStatusConfig(null),
                buildNotificationStatusConfig(null),
                buildNotificationStatusConfig(null));
        return config;
    }

    private UploadNotificationStatusConfig buildNotificationStatusConfig(String title) {
        Activity mainActivity = this.mainActivity;
        Resources activityRes = mainActivity.getResources();
        int iconId = activityRes.getIdentifier("ic_upload", "drawable", mainActivity.getPackageName());
        Intent intent = new Intent(this, mainActivity.getClass());
        PendingIntent clickIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        return new UploadNotificationStatusConfig(
                title != null ? title : "",
                "",
                iconId,
                Color.parseColor("#396496"),
                null,
                clickIntent,
                new ArrayList<>(0),
                true,
                true
        );
    }

    public void addUpload(JSONObject jsonPayload) {
        HashMap payload = null;
        try {
            payload = convertToHashMap(jsonPayload);
        } catch (JSONException error) {
            logMessage(String.format("eventLabel='Uploader could not read id from payload' error:'%s'", error.getMessage()));
        }
        if (payload == null) return;
        String uploadId = payload.get("id").toString();

        if (PendingUpload.count(PendingUpload.class, "upload_id = ?", new String[]{uploadId}) > 0) {
            logMessage(String.format("eventLabel='Uploader an upload is already pending with this id' uploadId='%s'", uploadId));
            return;
        }

        PendingUpload.create(jsonPayload);
        startUpload(payload);
    }

    public void removeUpload(String uploadId, CallbackContext context) {
        PendingUpload.remove(uploadId);
        UploadService.stopUpload(uploadId);
        PluginResult result = new PluginResult(PluginResult.Status.OK);
        result.setKeepCallback(true);
        context.sendPluginResult(result);
    }

    public void acknowledgeEvent(String eventId, CallbackContext context) {
        UploadEvent.destroy(Long.valueOf(eventId.replaceAll("\\D+", "")).longValue());
        PluginResult result = new PluginResult(PluginResult.Status.OK);
        result.setKeepCallback(true);
        context.sendPluginResult(result);
    }

    public void setReady(boolean state) {
        this.ready = state;
    }

    public boolean getReady() {
        return this.ready;
    }

    public static void logMessage(String message) {
        Log.d("CordovaBackgroundUpload", message);
    }
}