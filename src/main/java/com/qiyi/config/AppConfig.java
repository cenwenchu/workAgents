package com.qiyi.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import com.qiyi.util.AppLog;

public class AppConfig {
    private static final AppConfig INSTANCE = new AppConfig();
    private final Properties properties = new Properties();

    // Configuration Keys
    public static final String KEY_GEMINI_API_KEY = "GEMINI_API_KEY";
    public static final String KEY_DEEPSEEK_API_KEY = "deepseek.api-key";
    public static final String KEY_MINIMAX_API_KEY = "minimax.api-key";
    public static final String KEY_MOONSHOT_API_KEY = "moonshot.api-key";
    public static final String KEY_MOONSHOT_THINKING = "moonshot.thinking";
    public static final String KEY_GLM_API_KEY = "glm.api-key";
    public static final String KEY_GLM_THINKING = "glm.thinking";
    public static final String KEY_ALIYUN_API_KEY = "aliyun.api-key";
    public static final String KEY_ALIYUN_OSS_ENDPOINT = "aliyun.oss.endpoint";
    public static final String KEY_ALIYUN_OSS_ACCESS_KEY_ID = "aliyun.oss.access-key-id";
    public static final String KEY_ALIYUN_OSS_ACCESS_KEY_SECRET = "aliyun.oss.access-key-secret";
    public static final String KEY_ALIYUN_OSS_BUCKET_NAME = "aliyun.oss.bucket-name";
    public static final String KEY_DINGTALK_TOKEN = "dingtalk.robot.token";
    public static final String KEY_DINGTALK_SECRET = "dingtalk.robot.secret";
    public static final String KEY_DINGTALK_CLIENT_ID = "dingtalk.robot.client.id";
    public static final String KEY_DINGTALK_CLIENT_SECRET = "dingtalk.robot.client.secret";
    public static final String KEY_DINGTALK_CODE = "dingtalk.robot.code";
    public static final String KEY_PODCAST_PUBLISH_DIR = "podcast.publish.dir";
    public static final String KEY_PODCAST_PUBLISHED_DIR = "podcast.published.dir";
    public static final String KEY_PODCAST_PUBLISH_BATCH_SIZE = "podcast.publish.batch.size";
    public static final String KEY_PODCAST_DOWNLOAD_DIR = "podcast.download.dir";
    public static final String KEY_ADMIN_USERS = "podcast.admin.users";
    public static final String KEY_DINGTALK_AGENT_ID = "dingtalk.agent.id";
    public static final String KEY_CHROME_DEBUG_PORT = "chrome.debug.port";
    public static final String KEY_AUTOWEB_VISUAL_PROMPT = "autoweb.visual.prompt";
    public static final String KEY_AUTOWEB_WAIT_FOR_LOAD_STATE_TIMEOUT_MS = "autoweb.waitForLoadState.timeout.ms";
    public static final String KEY_AUTOWEB_DEBUG_FRAME_CAPTURE = "autoweb.debug.frame.capture";
    public static final String KEY_FUTU_OPEND_HOST = "futu.opend.host";
    public static final String KEY_FUTU_OPEND_PORT = "futu.opend.port";

    // Default Values
    public static final String DEFAULT_DOWNLOAD_DIR = "/tmp/podCastItems/";
    public static final int DEFAULT_CHROME_DEBUG_PORT = 9222;
    public static final int DEFAULT_PUBLISH_BATCH_SIZE = 1;
    public static final String DEFAULT_AUTOWEB_VISUAL_PROMPT = "请你提取一下图片里面的页面布局和元素信息，方便大模型理解这个界面的结构和元素，保障对于筛选项和操作按钮的准确和完整，不用给建议，只需要称述实际存在的元素内容，在保障完整性的同时，尽量减少字符数";
    public static final int DEFAULT_AUTOWEB_WAIT_FOR_LOAD_STATE_TIMEOUT_MS = 20000;
    public static final boolean DEFAULT_AUTOWEB_DEBUG_FRAME_CAPTURE = false;
    public static final String DEFAULT_FUTU_OPEND_HOST = "127.0.0.1";
    public static final int DEFAULT_FUTU_OPEND_PORT = 11111;

    private AppConfig() {
        loadProperties();
    }

    public static AppConfig getInstance() {
        return INSTANCE;
    }

    private void loadProperties() {
        try (InputStream input = AppConfig.class.getClassLoader().getResourceAsStream("podcast.cfg")) {
            if (input == null) {
                AppLog.error("Sorry, unable to find podcast.cfg");
                return;
            }
            properties.load(input);
        } catch (IOException ex) {
            AppLog.error("Error loading configuration: " + ex.getMessage());
            AppLog.error(ex);
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

    public String getMinimaxApiKey() {
        return getProperty(KEY_MINIMAX_API_KEY);
    }
    
    public String getMoonshotApiKey() {
        return getProperty(KEY_MOONSHOT_API_KEY);
    }
    
    public String getMoonshotThinking() {
        return getProperty(KEY_MOONSHOT_THINKING);
    }

    public String getGlmApiKey() {
        return getProperty(KEY_GLM_API_KEY);
    }
    
    public String getGlmThinking() {
        return getProperty(KEY_GLM_THINKING);
    }

    public String getAliyunApiKey() {
        return getProperty(KEY_ALIYUN_API_KEY);
    }

    public String getAliyunOssEndpoint() {
        return getProperty(KEY_ALIYUN_OSS_ENDPOINT);
    }

    public String getAutowebVisualPrompt() {
        return getProperty(KEY_AUTOWEB_VISUAL_PROMPT, DEFAULT_AUTOWEB_VISUAL_PROMPT);
    }

    public int getAutowebWaitForLoadStateTimeoutMs() {
        String v = getProperty(KEY_AUTOWEB_WAIT_FOR_LOAD_STATE_TIMEOUT_MS);
        if (v != null && !v.isEmpty()) {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                AppLog.error("Invalid autoweb waitForLoadState timeout format, using default: " + DEFAULT_AUTOWEB_WAIT_FOR_LOAD_STATE_TIMEOUT_MS);
            }
        }
        return DEFAULT_AUTOWEB_WAIT_FOR_LOAD_STATE_TIMEOUT_MS;
    }

    public boolean isAutowebDebugFrameCaptureEnabled() {
        String v = getProperty(KEY_AUTOWEB_DEBUG_FRAME_CAPTURE);
        if (v != null && !v.isEmpty()) {
            return Boolean.parseBoolean(v);
        }
        return DEFAULT_AUTOWEB_DEBUG_FRAME_CAPTURE;
    }

    public String getAliyunOssAccessKeyId() {
        return getProperty(KEY_ALIYUN_OSS_ACCESS_KEY_ID);
    }

    public String getAliyunOssAccessKeySecret() {
        return getProperty(KEY_ALIYUN_OSS_ACCESS_KEY_SECRET);
    }

    public String getAliyunOssBucketName() {
        return getProperty(KEY_ALIYUN_OSS_BUCKET_NAME);
    }

    public String getPodcastDownloadDir() {
        return getProperty(KEY_PODCAST_DOWNLOAD_DIR, DEFAULT_DOWNLOAD_DIR);
    }
    
    public String getPodcastPublishDir() {
        return getProperty(KEY_PODCAST_PUBLISH_DIR);
    }

    public String getPodcastPublishedDir() {
        return getProperty(KEY_PODCAST_PUBLISHED_DIR);
    }
    
    public String getPodcastSummaryDir() {
        return getPodcastDownloadDir() + "summary/";
    }

    public int getPodcastPublishBatchSize() {
        String sizeStr = getProperty(KEY_PODCAST_PUBLISH_BATCH_SIZE);
        if (sizeStr != null && !sizeStr.isEmpty()) {
            try {
                return Integer.parseInt(sizeStr);
            } catch (NumberFormatException e) {
                AppLog.error("Invalid batch size format, using default: " + DEFAULT_PUBLISH_BATCH_SIZE);
            }
        }
        return DEFAULT_PUBLISH_BATCH_SIZE;
    }

    public int getChromeDebugPort() {
        String portStr = getProperty(KEY_CHROME_DEBUG_PORT);
        if (portStr != null && !portStr.isEmpty()) {
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                AppLog.error("Invalid chrome debug port format, using default: " + DEFAULT_CHROME_DEBUG_PORT);
            }
        }
        return DEFAULT_CHROME_DEBUG_PORT;
    }

    public String getFutuOpenDHost() {
        return getProperty(KEY_FUTU_OPEND_HOST, DEFAULT_FUTU_OPEND_HOST);
    }

    public int getFutuOpenDPort() {
        String portStr = getProperty(KEY_FUTU_OPEND_PORT);
        if (portStr != null && !portStr.isEmpty()) {
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                AppLog.error("Invalid futu OpenD port format, using default: " + DEFAULT_FUTU_OPEND_PORT);
            }
        }
        return DEFAULT_FUTU_OPEND_PORT;
    }
}
