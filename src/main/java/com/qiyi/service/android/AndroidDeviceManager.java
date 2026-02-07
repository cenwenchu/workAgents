package com.qiyi.service.android;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import com.qiyi.util.AppLog;

/**
 * Android设备管理器
 * <p>
 * 用于管理连接的Android设备，执行ADB命令。
 * </p>
 */
public class AndroidDeviceManager {
    private static final AndroidDeviceManager INSTANCE = new AndroidDeviceManager();
    private String adbPath = "adb"; // Assume in PATH

    private AndroidDeviceManager() {
    }

    /**
     * 获取单例实例
     * @return AndroidDeviceManager实例
     */
    public static AndroidDeviceManager getInstance() {
        return INSTANCE;
    }

    /**
     * 获取连接的设备列表
     * @return 设备序列号列表
     * @throws IOException IO异常
     */
    public List<String> getDevices() throws IOException {
        List<String> devices = new ArrayList<>();
        Process process = new ProcessBuilder(adbPath, "devices").start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.endsWith("device")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length > 0) {
                        devices.add(parts[0]);
                    }
                }
            }
        }
        return devices;
    }
    
    /**
     * 执行Shell命令
     * @param serial 设备序列号，如果为null则不指定设备
     * @param command Shell命令字符串
     * @return 命令输出结果
     * @throws IOException IO异常
     */
    public String executeShell(String serial, String command) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(adbPath);
        if (serial != null && !serial.isEmpty()) {
            cmd.add("-s");
            cmd.add(serial);
        }
        cmd.add("shell");
        
        // Simple command parsing: split by space. 
        // For more complex commands, we might need a better parser.
        // But for "am start" and "monkey", this is sufficient.
        String[] parts = command.split("\\s+");
        for (String part : parts) {
            cmd.add(part);
        }

        return executeProcess(cmd);
    }

    /**
     * 执行Shell命令（参数列表方式）
     * @param serial 设备序列号，如果为null则不指定设备
     * @param commandArgs Shell命令参数列表
     * @return 命令输出结果
     * @throws IOException IO异常
     */
    public String executeShell(String serial, List<String> commandArgs) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(adbPath);
        if (serial != null && !serial.isEmpty()) {
            cmd.add("-s");
            cmd.add(serial);
        }
        cmd.add("shell");
        cmd.addAll(commandArgs);

        return executeProcess(cmd);
    }

    private String executeProcess(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        return output.toString();
    }

    /**
     * 模拟点击
     * @param serial 设备序列号
     * @param x X坐标
     * @param y Y坐标
     * @throws IOException IO异常
     */
    public void tap(String serial, int x, int y) throws IOException {
        executeShell(serial, "input tap " + x + " " + y);
    }

    /**
     * 输入文本
     * @param serial 设备序列号
     * @param text 要输入的文本（仅支持ASCII）
     * @throws IOException IO异常
     */
    public void inputText(String serial, String text) throws IOException {
        // ADB input text does not support non-ASCII characters natively.
        // We warn if we detect them.
        boolean hasNonAscii = false;
        for (char c : text.toCharArray()) {
            if (c > 127) {
                hasNonAscii = true;
                break;
            }
        }
        if (hasNonAscii) {
            AppLog.error("Warning: Input text contains non-ASCII characters ('" + text + "'). " +
                    "Standard ADB input usually fails to type these. Consider using Pinyin or English.");
        }

        List<String> args = new ArrayList<>();
        args.add("input");
        args.add("text");
        args.add(text);
        executeShell(serial, args);
    }

    public void pullFile(String serial, String remote, String local) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(adbPath);
        if (serial != null && !serial.isEmpty()) {
            cmd.add("-s");
            cmd.add(serial);
        }
        cmd.add("pull");
        cmd.add(remote);
        cmd.add(local);
        
        executeProcess(cmd);
    }

    public String dumpWindowHierarchy(String serial) throws IOException {
        String remotePath = "/sdcard/window_dump.xml";
        executeShell(serial, "uiautomator dump " + remotePath);
        return remotePath;
    }

    public void screencap(String serial, String remotePath) throws IOException {
        executeShell(serial, "screencap -p " + remotePath);
    }

    public void swipe(String serial, int x1, int y1, int x2, int y2, int durationMs) throws IOException {
        executeShell(serial, "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + durationMs);
    }
}
