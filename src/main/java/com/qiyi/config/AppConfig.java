package com.qiyi.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AppConfig {
    private static final AppConfig INSTANCE = new AppConfig();
    private final Properties properties = new Properties();

    // Configuration Keys
    public static final String KEY_GEMINI_API_KEY = "GEMINI_API_KEY";
    public static final String KEY_DEEPSEEK_API_KEY = "deepseek.api-key";
    public static final String KEY_DINGTALK_TOKEN = "dingtalk.robot.token";
    public static final String KEY_DINGTALK_SECRET = "dingtalk.robot.secret";
    public static final String KEY_DINGTALK_CLIENT_ID = "dingtalk.robot.client.id";
    public static final String KEY_DINGTALK_CLIENT_SECRET = "dingtalk.robot.client.secret";
    public static final String KEY_DINGTALK_CODE = "dingtalk.robot.code";
    public static final String KEY_PODCAST_PUBLISH_DIR = "podcast.publish.dir";
    public static final String KEY_PODCAST_DOWNLOAD_DIR = "podcast.download.dir";
    public static final String KEY_ADMIN_USERS = "podcast.admin.users";
    public static final String KEY_DINGTALK_AGENT_ID = "dingtalk.agent.id";
    public static final String KEY_CHROME_DEBUG_PORT = "chrome.debug.port";

    // Default Values
    public static final String DEFAULT_DOWNLOAD_DIR = "/tmp/podCastItems/";
    public static final int DEFAULT_CHROME_DEBUG_PORT = 9222;

    private AppConfig() {
        loadProperties();
    }

    public static AppConfig getInstance() {
        return INSTANCE;
    }

    private void loadProperties() {
        try (InputStream input = AppConfig.class.getClassLoader().getResourceAsStream("podcast.cfg")) {
            if (input == null) {
                System.err.println("Sorry, unable to find podcast.cfg");
                return;
            }
            properties.load(input);
        } catch (IOException ex) {
            System.err.println("Error loading configuration: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public String getGeminiApiKey() {
        return getProperty(KEY_GEMINI_API_KEY);
    }

    public String getDeepSeekApiKey() {
        return getProperty(KEY_DEEPSEEK_API_KEY);
    }

    public String getPodcastDownloadDir() {
        return getProperty(KEY_PODCAST_DOWNLOAD_DIR, DEFAULT_DOWNLOAD_DIR);
    }
    
    public String getPodcastPublishDir() {
        return getProperty(KEY_PODCAST_PUBLISH_DIR);
    }

    public int getChromeDebugPort() {
        String portStr = getProperty(KEY_CHROME_DEBUG_PORT);
        if (portStr != null && !portStr.isEmpty()) {
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                System.err.println("Invalid chrome debug port format, using default: " + DEFAULT_CHROME_DEBUG_PORT);
            }
        }
        return DEFAULT_CHROME_DEBUG_PORT;
    }
}
