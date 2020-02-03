package com.spoon.backgroundFileUpload;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;


public class FileTransferSettings {

  String filePath = "";
  String serverUrl = "";
  String id = "";
  String fileKey = "file";
  boolean showNotification = true;
  String notificationTitle;

  HashMap<String, String> headers = new HashMap<String, String>();
  HashMap<String, String> parameters = new HashMap<String, String>();


  public FileTransferSettings(String jsonSettings) throws Exception {
    try {
      JSONObject settings = new JSONObject(jsonSettings);

      filePath = settings.getString("filePath");
      serverUrl = settings.getString("serverUrl");
      id = settings.getString("id");
      fileKey = settings.getString("fileKey");
      if (settings.has("showNotification"))
        showNotification = settings.getBoolean("showNotification");
      if (settings.has("notificationTitle"))
        notificationTitle = settings.getString("notificationTitle");

      if (settings.has("headers")) {
        JSONObject headersObject = settings.getJSONObject("headers");
        if (headersObject != null) {

          Iterator<?> keys = headersObject.keys();
          while (keys.hasNext()) {
            String key = (String) keys.next();
            String value = headersObject.getString(key);
            headers.put(key, value);
          }

        }
      }

      if (settings.has("parameters")) {
        JSONObject parametersObject = settings.getJSONObject("parameters");
        if (parametersObject != null) {

          Iterator<?> keys = parametersObject.keys();
          while (keys.hasNext()) {
            String key = (String) keys.next();
            String value = parametersObject.getString(key);
            parameters.put(key, value);
          }

        }
      }

    } catch (Exception e) {
      throw e;
    }
  }
}
