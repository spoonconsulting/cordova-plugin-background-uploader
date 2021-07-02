package com.spoon.backgroundfileupload;

import com.orm.SugarRecord;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;

public class UploadEvent extends SugarRecord {
    private String data;

    public UploadEvent() {}

    public UploadEvent(JSONObject payload) {
        data = payload.toString();
    }

    public JSONObject dataRepresentation() {
        try {
            JSONObject parseData = new JSONObject(this.data);
            parseData.put("eventId", this.getId());
            return parseData;
        } catch (JSONException e) {
            return null;
        }
    }

    public static UploadEvent create(JSONObject payload) {
        UploadEvent event = new UploadEvent(payload);
        event.save();
        return event;
    }

    public static void destroy(Long eventId) {
        UploadEvent event = UploadEvent.findById(UploadEvent.class, eventId);
        if (event != null)
            event.delete();
    }

    public static List<UploadEvent> all() {
        return UploadEvent.listAll(UploadEvent.class);
    }
}
