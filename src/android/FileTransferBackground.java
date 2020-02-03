package com.spoon.backgroundFileUpload;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.sromku.simple.storage.SimpleStorage;
import com.sromku.simple.storage.Storage;
import com.sromku.simple.storage.helpers.OrderType;

import net.gotev.uploadservice.MultipartUploadRequest;
import net.gotev.uploadservice.ServerResponse;
import net.gotev.uploadservice.UploadInfo;
import net.gotev.uploadservice.UploadNotificationConfig;
import net.gotev.uploadservice.UploadService;
import net.gotev.uploadservice.UploadServiceBroadcastReceiver;
import net.gotev.uploadservice.okhttp.OkHttpStack;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class FileTransferBackground extends CordovaPlugin {

    private final String uploadDirectoryName = "FileTransferBackground";
    private Storage storage;
    private CallbackContext uploadCallback;
    private NetworkMonitor networkMonitor;
    private Long lastProgressTimestamp = 0L;
    private HashMap<String, CallbackContext> cancelUploadCallbackMap = new HashMap();
    private boolean hasBeenDestroyed = false;

    private UploadServiceBroadcastReceiver broadcastReceiver = new UploadServiceBroadcastReceiver() {
        @Override
        public void onProgress(Context context, UploadInfo uploadInfo) {

            try {
                Long currentTimestamp = System.currentTimeMillis() / 1000;
                if (currentTimestamp - lastProgressTimestamp >= 1) {
                    LogMessage("id:" + uploadInfo.getUploadId() + " progress: " + uploadInfo.getProgressPercent());
                    lastProgressTimestamp = currentTimestamp;

                    if (uploadCallback != null && !hasBeenDestroyed) {
                        JSONObject objResult = new JSONObject();
                        objResult.put("id", uploadInfo.getUploadId());
                        objResult.put("progress", uploadInfo.getProgressPercent());
                        objResult.put("state", "UPLOADING");
                        PluginResult progressUpdate = new PluginResult(PluginResult.Status.OK, objResult);
                        progressUpdate.setKeepCallback(true);
                        uploadCallback.sendPluginResult(progressUpdate);
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onError(final Context context, final UploadInfo uploadInfo, final ServerResponse serverResponse, final Exception exception) {
            LogMessage("App onError: " + exception);

            try {
                updateStateForUpload(uploadInfo.getUploadId(), UploadState.FAILED, null);

                if (uploadCallback != null && !hasBeenDestroyed) {
                    JSONObject errorObj = new JSONObject();
                    errorObj.put("id", uploadInfo.getUploadId());
                    errorObj.put("error", "upload failed: " + exception != null ? exception.getMessage() : "");
                    errorObj.put("state", "FAILED");
                    PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, errorObj);
                    errorResult.setKeepCallback(true);
                    uploadCallback.sendPluginResult(errorResult);
                }


            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onCompleted(Context context, UploadInfo uploadInfo, ServerResponse serverResponse) {

            try {
                LogMessage("server response : " + serverResponse.getBodyAsString() + " for " + uploadInfo.getUploadId());
                updateStateForUpload(uploadInfo.getUploadId(), UploadState.UPLOADED, serverResponse.getBodyAsString());
                if (uploadCallback != null && !hasBeenDestroyed) {
                    JSONObject objResult = new JSONObject();
                    objResult.put("id", uploadInfo.getUploadId());
                    objResult.put("completed", true);
                    objResult.put("serverResponse", serverResponse.getBodyAsString());
                    objResult.put("state", "UPLOADED");
                    objResult.put("statusCode", serverResponse.getHttpCode());
                    PluginResult completedUpdate = new PluginResult(PluginResult.Status.OK, objResult);
                    completedUpdate.setKeepCallback(true);
                    uploadCallback.sendPluginResult(completedUpdate);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onCancelled(Context context, UploadInfo uploadInfo) {
            try {
                LogMessage("upload cancelled " + uploadInfo.getUploadId());
                if (hasBeenDestroyed) {
                    //most likely the upload service was killed by the system
                    updateStateForUpload(uploadInfo.getUploadId(), UploadState.FAILED, null);
                    return;
                }
                removeUploadInfoFile(uploadInfo.getUploadId());
                PluginResult result = new PluginResult(PluginResult.Status.OK);
                result.setKeepCallback(true);
                CallbackContext cancelCallback = cancelUploadCallbackMap.get(uploadInfo.getUploadId());
                if (cancelCallback != null)
                    cancelCallback.sendPluginResult(result);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) {

        try {
            if (action.equalsIgnoreCase("initManager")) {
                uploadCallback = callbackContext;
                this.initManager(args.length() > 0 ? args.get(0).toString() : null, callbackContext);
            } else if (action.equalsIgnoreCase("removeUpload")) {
                this.removeUpload(args.length() > 0 ? args.get(0).toString() : null, callbackContext);
            } else {
                uploadCallback = callbackContext;
                upload(args.length() > 0 ? (JSONObject) args.get(0) : null, uploadCallback);
            }
        } catch (Exception ex) {
            try {
                JSONObject errorObj = new JSONObject();
                errorObj.put("error", ex.getMessage());
                PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, errorObj);
                errorResult.setKeepCallback(true);
                callbackContext.sendPluginResult(errorResult);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    private void upload(JSONObject jsonPayload, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    final FileTransferSettings payload = new FileTransferSettings(jsonPayload.toString());
                    if (UploadService.getTaskList().contains(payload.id)) {
                        FileTransferBackground.this.LogMessage("upload with id " + payload.id + " is already being uploaded. ignoring re-upload request");
                        return;
                    }

                    ArrayList<JSONObject> existingUploads = getUploadHistory();
                    for (JSONObject upload : existingUploads) {
                        String id = upload.getString("id");
                        if (id.equalsIgnoreCase(payload.id)) {
                            LogMessage("upload with id " + payload.id + " is already exists in upload queue. ignoring re-upload request");
                            return;
                        }
                    }

                    LogMessage("adding upload " + payload.id);
                    FileTransferBackground.this.createUploadInfoFile(payload.id, jsonPayload);
                    if (NetworkMonitor.isConnected) {

                        MultipartUploadRequest request = new MultipartUploadRequest(FileTransferBackground.this.cordova.getActivity().getApplicationContext(), payload.id, payload.serverUrl)
                                .addFileToUpload(payload.filePath, payload.fileKey)
                                .setMaxRetries(0);

                        if (payload.showNotification) {
                            UploadNotificationConfig config = new UploadNotificationConfig();
                            config.getCompleted().autoClear = true;
                            config.getCancelled().autoClear = true;
                            config.getError().autoClear = true;
                            config.setClearOnActionForAllStatuses(true);
                            Intent intent = new Intent(cordova.getContext(), cordova.getActivity().getClass());
                            PendingIntent pendingIntent = PendingIntent.getActivity(cordova.getContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
                            config.setClickIntentForAllStatuses(pendingIntent);
                            if (payload.notificationTitle != null)
                                config.getProgress().title = payload.notificationTitle;
                            request.setNotificationConfig(config);
                        }
                        for (String key : payload.parameters.keySet()) {
                            request.addParameter(key, payload.parameters.get(key));
                        }

                        for (String key : payload.headers.keySet()) {
                            request.addHeader(key, payload.headers.get(key));
                        }

                        request.startUpload();

                    } else {
                        LogMessage("Upload failed. Image added to pending list");
                        updateStateForUpload(payload.id, UploadState.FAILED, null);
                    }
                } catch (Exception ex) {
                    PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, ex.toString());
                    errorResult.setKeepCallback(true);
                    callbackContext.sendPluginResult(errorResult);
                }
            }
        });
    }

    private void LogMessage(String message) {
        Log.d("FileTransferBG", message);
    }

    private void removeUpload(String fileId, CallbackContext callbackContext) {
        try {
            if (fileId == null)
                throw new Exception("missing upload id");
            if (!UploadService.getTaskList().contains(fileId)) {
                LogMessage("cancel upload: " + fileId + " which is not in progress, ignoring request");
                PluginResult result = new PluginResult(PluginResult.Status.OK);
                result.setKeepCallback(true);
                callbackContext.sendPluginResult(result);
                return;
            }
            LogMessage("cancel upload " + fileId);
            cancelUploadCallbackMap.put(fileId, callbackContext);
            UploadService.stopUpload(fileId);
        } catch (Exception e) {
            e.printStackTrace();
            PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, e.toString());
            errorResult.setKeepCallback(true);
            callbackContext.sendPluginResult(errorResult);
        }
    }

    private void createUploadInfoFile(String fileId, JSONObject upload) {
        try {
            upload.put("createdDate", System.currentTimeMillis() / 1000);
            upload.put("state", UploadState.STARTED);
            storage.createFile(uploadDirectoryName, fileId + ".json", upload.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateStateForUpload(String fileId, String state, String serverResponse) {
        try {
            String fileName = fileId + ".json";
            if (!storage.isFileExist(uploadDirectoryName, fileName)) {
                LogMessage("could not find " + fileName + " for updating upload info");
                return;
            }
            String content = storage.readTextFile(uploadDirectoryName, fileName);
            if (content != null) {
                JSONObject uploadJson = new JSONObject(content);
                uploadJson.put("state", state);
                if (state == UploadState.UPLOADED) {
                    uploadJson.put("serverResponse", serverResponse != null ? serverResponse : "");
                }
                //delete old file
                removeUploadInfoFile(fileId);
                //write updated file
                storage.createFile(uploadDirectoryName, fileName, uploadJson.toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void removeUploadInfoFile(String fileId) {
        storage.deleteFile(uploadDirectoryName, fileId + ".json");
    }

    private ArrayList<JSONObject> getUploadHistory() {
        ArrayList<JSONObject> previousUploads = new ArrayList<JSONObject>();
        try {
            List<File> files = storage.getFiles(uploadDirectoryName, OrderType.DATE);
            for (File file : files) {
                if (file.getName().endsWith(".json")) {
                    String content = storage.readTextFile(uploadDirectoryName, file.getName());
                    if (content != null) {
                        JSONObject uploadJson = new JSONObject(content);
                        previousUploads.add(uploadJson);
                    }

                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return previousUploads;
    }

    private void initManager(String options, final CallbackContext callbackContext) {
        try {

            UploadService.HTTP_STACK = new OkHttpStack();
            UploadService.UPLOAD_POOL_SIZE = 1;
            UploadService.NAMESPACE = cordova.getContext().getPackageName();
            storage = SimpleStorage.getInternalStorage(this.cordova.getActivity().getApplicationContext());
            storage.createDirectory(uploadDirectoryName);
            LogMessage("created FileTransfer working directory ");

            cordova.getActivity().getApplicationContext().registerReceiver(broadcastReceiver, new IntentFilter(UploadService.NAMESPACE + ".uploadservice.broadcast.status"));

            if (options != null) {
                //initialised global configuration parameters here
                //JSONObject settings = new JSONObject(options);
            }

            ArrayList<JSONObject> previousUploads = getUploadHistory();
            for (JSONObject upload : previousUploads) {
                String state = upload.getString("state");
                String id = upload.getString("id");

                if (state.equalsIgnoreCase(UploadState.UPLOADED)) {
                    JSONObject objResult = new JSONObject();
                    objResult.put("id", id);
                    objResult.put("completed", true);
                    objResult.put("serverResponse", upload.getString("serverResponse"));
                    PluginResult progressUpdate = new PluginResult(PluginResult.Status.OK, objResult);
                    progressUpdate.setKeepCallback(true);
                    callbackContext.sendPluginResult(progressUpdate);

                } else if (state.equalsIgnoreCase(UploadState.FAILED) || state.equalsIgnoreCase(UploadState.STARTED)) {
                    //if the state is STARTED, it means app was killed before the upload was completed
                    JSONObject errorObj = new JSONObject();
                    errorObj.put("id", id);
                    errorObj.put("error", "upload failed");
                    PluginResult errorResult = new PluginResult(PluginResult.Status.ERROR, errorObj);
                    errorResult.setKeepCallback(true);
                    callbackContext.sendPluginResult(errorResult);
                }
                //delete upload info on disk
                removeUploadInfoFile(id);
            }

            networkMonitor = new NetworkMonitor(webView.getContext(), new ConnectionStatusListener() {
                @Override
                public void connectionDidChange(Boolean isConnected, String networkType) {
                    LogMessage("detected network change, Connected:" + isConnected);
                    uploadPendingList();
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void uploadPendingList() {
        ArrayList<JSONObject> previousUploads = getUploadHistory();
        for (JSONObject upload : previousUploads) {
            try {
                String state = upload.getString("state");
                if (state.equalsIgnoreCase(UploadState.FAILED) || state.equalsIgnoreCase(UploadState.STARTED)) {
                    this.upload(upload, uploadCallback);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void onDestroy() {
        LogMessage("plugin onDestroy, unsubscribing all callbacks");
        hasBeenDestroyed = true;
        if (networkMonitor != null)
            networkMonitor.stopMonitoring();
        //broadcastReceiver.unregister(cordova.getActivity().getApplicationContext());
    }


}
