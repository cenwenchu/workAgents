package com.qiyi.android;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class AndroidDeviceManager {
    private static final AndroidDeviceManager INSTANCE = new AndroidDeviceManager();
    private String adbPath = "adb"; // Assume in PATH

    private AndroidDeviceManager() {
    }

    public static AndroidDeviceManager getInstance() {
        return INSTANCE;
    }

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

    public void tap(String serial, int x, int y) throws IOException {
        executeShell(serial, "input tap " + x + " " + y);
    }

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
            System.err.println("Warning: Input text contains non-ASCII characters ('" + text + "'). " +
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
