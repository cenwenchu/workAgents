package com.qiyi.tools.android;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.android.AndroidDeviceManager;
import com.qiyi.util.DingTalkUtil;

import java.util.ArrayList;
import java.util.List;

public class OpenAppTool extends AndroidBaseTool {

    @Override
    public String getName() {
        return "open_android_app";
    }

    @Override
    public String getDescription() {
        return "Open an Android app on a connected device. Parameters: packageName (required, e.g. com.android.settings), activityName (optional, full class name), serial (optional, device serial number).";
    }

    @Override
    public String execute(JSONObject params, String senderId, List<String> atUserIds) {
        try {
            String packageName = params.getString("packageName");
            if (packageName == null || packageName.isEmpty()) {
                return "Error: packageName is required.";
            }
            String activityName = params.getString("activityName");
            
            String serial = getDeviceSerial(params);
            
            String command;
            if (activityName != null && !activityName.isEmpty()) {
                if (!activityName.contains(".")) {
                    activityName = "." + activityName;
                }
                if (activityName.startsWith(".")) {
                    activityName = packageName + activityName;
                }
                command = "am start -n " + packageName + "/" + activityName;
            } else {
                command = "monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1";
            }
            
            String output = AndroidDeviceManager.getInstance().executeShell(serial, command);
            
            // Check output for common error indicators from am start
            String result;
            if (output != null && (output.toLowerCase().contains("error") || output.toLowerCase().contains("exception"))) {
                 result = "打开应用失败: " + output;
            } else {
                 result = "成功打开应用";
            }
            
            if (senderId != null && !senderId.isEmpty()) {
                List<String> users = new ArrayList<>();
                users.add(senderId);
                try {
                    DingTalkUtil.sendTextMessageToEmployees(users, result);
                } catch (Exception e) {
                    System.err.println("OpenAppTool: Failed to send message: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            return result;
            
        } catch (Exception e) {
            e.printStackTrace();
            String errorMsg = "打开应用失败: " + e.getMessage();
            
            if (senderId != null && !senderId.isEmpty()) {
                List<String> users = new ArrayList<>();
                users.add(senderId);
                try {
                    DingTalkUtil.sendTextMessageToEmployees(users, errorMsg);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            
            return errorMsg;
        }
    }
}
