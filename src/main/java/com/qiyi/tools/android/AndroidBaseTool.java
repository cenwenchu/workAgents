package com.qiyi.tools.android;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.service.android.AndroidDeviceManager;
import com.qiyi.tools.Tool;
import java.util.List;

public abstract class AndroidBaseTool implements Tool {

    protected String getDeviceSerial(JSONObject params) throws Exception {
        String serial = null;
        
        if (params != null)
            serial = params.getString("serial");
        
        if (serial != null && !serial.isEmpty()) {
            return serial;
        }
        
        // If no serial provided, get the first connected device
        List<String> devices = AndroidDeviceManager.getInstance().getDevices();
        if (devices.isEmpty()) {
            throw new Exception("No Android devices connected.");
        }
        return devices.get(0);
    }
}
