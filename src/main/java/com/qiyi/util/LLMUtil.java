package com.qiyi.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import java.util.Collections;
import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.File;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.UploadFileConfig;
import com.qiyi.config.AppConfig;

import io.github.pigmesh.ai.deepseek.core.DeepSeekClient;
import io.github.pigmesh.ai.deepseek.core.OpenAiClient.OpenAiClientContext;
import io.github.pigmesh.ai.deepseek.core.chat.ChatCompletionRequest;
import io.github.pigmesh.ai.deepseek.core.chat.ChatCompletionResponse;
import io.github.pigmesh.ai.deepseek.core.chat.UserMessage;
import io.github.pigmesh.ai.deepseek.core.shared.StreamOptions;
import reactor.core.publisher.Flux;

import io.github.ollama4j.OllamaAPI;
import io.github.ollama4j.models.chat.OllamaChatMessage;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import io.github.ollama4j.models.chat.OllamaChatRequestBuilder;
import io.github.ollama4j.models.chat.OllamaChatResult;
import io.github.ollama4j.models.generate.OllamaStreamHandler;

import io.github.ollama4j.models.chat.OllamaChatMessageRole;

/**
 * 大模型能力统一入口（Router + Clients）。
 *
 * <p>该类封装多个模型/供应商的调用细节（DeepSeek / 阿里云 DashScope / Gemini / Ollama / 其他），对上层提供：</p>
 * <ul>
 *     <li>chat / chatResult：按配置与优先级路由到可用模型</li>
 *     <li>hasAnyRemoteChatKeyConfigured：用于判断是否可以走远程 LLM；无 Key 时可降级为直连工具模式</li>
 * </ul>
 *
 * <p>注意：不要在日志中输出任何 API Key 或密钥内容。</p>
 */
public class LLMUtil {

    public enum ModelType {
        ALL,
        DEEPSEEK,
        GEMINI,
        ALIYUN,
        ALIYUN_VL,
        OLLAMA, // Added Ollama support
        MINIMAX,
        MOONSHOT,
        GLM
    }

    public static class LLMSettings {
        public static String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
        public static String DEEPSEEK_MODEL = "deepseek-chat";

        public static String ALIYUN_CHAT_MODEL = "qwen3-max";
        public static String ALIYUN_VL_MODEL = "qwen3-vl-plus";

        public static String GEMINI_CHAT_MODEL = "gemini-3-flash-preview";
        public static String GEMINI_PDF_MODEL = "gemini-3-flash-preview";
        public static String GEMINI_VISION_MODEL = "gemini-2.0-flash-exp";
        public static String GEMINI_IMAGE_MODEL = "gemini-3-pro-image-preview";

        public static String MOONSHOT_CHAT_COMPLETIONS_URL = "https://api.moonshot.cn/v1/chat/completions";
        public static String MOONSHOT_MODEL = "kimi-k2.5";

        public static String MINIMAX_CHAT_COMPLETIONS_URL = "https://api.minimaxi.com/v1/chat/completions";
        public static String MINIMAX_MODEL = "MiniMax-M2.1";

        public static String GLM_CHAT_COMPLETIONS_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
        public static String GLM_MODEL = "glm-4.6";

        public static int HTTP_CONNECT_TIMEOUT_SECONDS = 20;
        public static int HTTP_REQUEST_TIMEOUT_SECONDS = 120;
        public static int HTTP_RETRY_MAX_ATTEMPTS = 3;
        public static long HTTP_RETRY_BACKOFF_MILLIS = 600;

        public static int DEEPSEEK_CONNECT_TIMEOUT_SECONDS = 30;
        public static int DEEPSEEK_WRITE_TIMEOUT_SECONDS = 30;
        public static int DEEPSEEK_READ_TIMEOUT_SECONDS = 600;
        public static int DEEPSEEK_CALL_TIMEOUT_SECONDS = 610;

        public static String LOG_LEVEL = "INFO";
        public static boolean LOG_STACKTRACE = false;
    }

    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(LLMSettings.HTTP_CONNECT_TIMEOUT_SECONDS))
            .build();

    public static class LLMResult {
        private final boolean success;
        private final String text;
        private final ModelType model;
        private final String provider;
        private final Map<String, Object> usage;
        private final String error;

        private LLMResult(boolean success, String text, ModelType model, String provider, Map<String, Object> usage, String error) {
            this.success = success;
            this.text = text == null ? "" : text;
            this.model = model;
            this.provider = provider;
            this.usage = usage == null ? null : Collections.unmodifiableMap(usage);
            this.error = error;
        }

        public static LLMResult ok(String text, ModelType model, String provider, Map<String, Object> usage) {
            return new LLMResult(true, text, model, provider, usage, null);
        }

        public static LLMResult fail(ModelType model, String provider, String error) {
            return new LLMResult(false, "", model, provider, null, error == null ? "" : error);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getText() {
            return text;
        }

        public ModelType getModel() {
            return model;
        }

        public String getProvider() {
            return provider;
        }

        public Map<String, Object> getUsage() {
            return usage;
        }

        public String getError() {
            return error;
        }
    }

    public interface LLMProvider {
        ModelType type();
        boolean isConfigured(AppConfig cfg);
        LLMResult chat(String prompt);
        default LLMResult chat(List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages, boolean isStreamingProcess) {
            return chat(toPrompt(messages));
        }
    }

    private static class LLMRouter {
        private final List<LLMProvider> providers;

        private LLMRouter(List<LLMProvider> providers) {
            this.providers = providers;
        }

        private LLMProvider findProvider(ModelType type) {
            for (LLMProvider p : providers) {
                if (p.type() == type) return p;
            }
            return null;
        }

        public LLMResult chat(String prompt, ModelType preferredModel) {
            ModelType normalizedPreferred = normalize(preferredModel);
            AppConfig cfg = AppConfig.getInstance();

            if (normalizedPreferred != null && normalizedPreferred != ModelType.ALL) {
                LLMProvider p = findProvider(normalizedPreferred);
                if (p == null) {
                    return LLMResult.fail(normalizedPreferred, null, "Provider not found: " + normalizedPreferred);
                }
                if (!p.isConfigured(cfg)) {
                    return LLMResult.fail(normalizedPreferred, p.getClass().getSimpleName(), "Model not configured: " + normalizedPreferred);
                }
                return p.chat(prompt);
            }

            for (ModelType t : getFallbackOrder()) {
                LLMProvider p = findProvider(t);
                if (p == null) continue;
                if (!p.isConfigured(cfg)) continue;
                LLMResult res = p.chat(prompt);
                if (res != null && res.isSuccess()) return res;
            }
            LLMProvider ollama = findProvider(ModelType.OLLAMA);
            if (ollama != null) return ollama.chat(prompt);
            return LLMResult.fail(ModelType.OLLAMA, null, "No available provider");
        }

        public LLMResult chat(List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages, boolean isStreamingProcess, ModelType preferredModel) {
            ModelType normalizedPreferred = normalize(preferredModel);
            AppConfig cfg = AppConfig.getInstance();

            if (normalizedPreferred != null && normalizedPreferred != ModelType.ALL) {
                LLMProvider p = findProvider(normalizedPreferred);
                if (p == null) {
                    return LLMResult.fail(normalizedPreferred, null, "Provider not found: " + normalizedPreferred);
                }
                if (!p.isConfigured(cfg)) {
                    return LLMResult.fail(normalizedPreferred, p.getClass().getSimpleName(), "Model not configured: " + normalizedPreferred);
                }
                return p.chat(messages, isStreamingProcess);
            }

            for (ModelType t : getFallbackOrder()) {
                LLMProvider p = findProvider(t);
                if (p == null) continue;
                if (!p.isConfigured(cfg)) continue;
                LLMResult res = p.chat(messages, isStreamingProcess);
                if (res != null && res.isSuccess()) return res;
            }
            LLMProvider ollama = findProvider(ModelType.OLLAMA);
            if (ollama != null) return ollama.chat(messages, isStreamingProcess);
            return LLMResult.fail(ModelType.OLLAMA, null, "No available provider");
        }
    }

    private static final LLMRouter ROUTER = new LLMRouter(Arrays.asList(
            new DeepSeekProvider(),
            new AliyunProvider(),
            new MoonshotProvider(),
            new MinimaxProvider(),
            new GlmProvider(),
            new GeminiProvider(),
            new OllamaProvider()
    ));

    private enum LogLevel {
        ERROR(1),
        WARN(2),
        INFO(3),
        DEBUG(4);

        private final int rank;
        LogLevel(int rank) { this.rank = rank; }
    }

    private static LogLevel resolveLogLevel() {
        String raw = LLMSettings.LOG_LEVEL == null ? "" : LLMSettings.LOG_LEVEL.trim().toUpperCase();
        if ("DEBUG".equals(raw)) return LogLevel.DEBUG;
        if ("INFO".equals(raw)) return LogLevel.INFO;
        if ("WARN".equals(raw) || "WARNING".equals(raw)) return LogLevel.WARN;
        if ("ERROR".equals(raw)) return LogLevel.ERROR;
        return LogLevel.INFO;
    }

    private static boolean logEnabled(LogLevel level) {
        return level.rank <= resolveLogLevel().rank;
    }

    private static void logInfo(String msg) {
        if (!logEnabled(LogLevel.INFO)) return;
        AppLog.info(msg);
    }

    private static void logWarn(String msg) {
        if (!logEnabled(LogLevel.WARN)) return;
        AppLog.info(msg);
    }

    private static void logError(String msg, Throwable t) {
        if (!logEnabled(LogLevel.ERROR)) return;
        AppLog.error(msg);
        if (t != null && LLMSettings.LOG_STACKTRACE) {
            AppLog.error(t);
        }
    }

    public static final String OLLAMA_HOST = "http://localhost:11434";
    public static final String OLLAMA_MODEL_QWEN3_VL_8B = "qwen3-vl:8b";
    public static final String OLLAMA_MODEL_QWEN3_8B = "qwen3:8b";
    public static final String OLLAMA_MODEL_HUNYUAN_MT = "hunyuan-mt:latest";

    public static String chat(String prompt) {
        return chat(prompt, null);
    }

    public static String chat(String prompt, ModelType preferredModel) {
        return chatResult(prompt, preferredModel).getText();
    }

    public static String chat(List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages, boolean isStreamingProcess) {
        return chat(messages, isStreamingProcess, null);
    }

    public static String chat(List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages, boolean isStreamingProcess, ModelType preferredModel) {
        return chatResult(messages, isStreamingProcess, preferredModel).getText();
    }

    public static LLMResult chatResult(String prompt) {
        return chatResult(prompt, null);
    }

    public static LLMResult chatResult(String prompt, ModelType preferredModel) {
        long begin = System.nanoTime();
        int promptChars = prompt == null ? 0 : prompt.length();
        int approxTokens = promptChars <= 0 ? 0 : Math.max(1, promptChars / 4);
        try {
            LLMResult res = ROUTER.chat(prompt, preferredModel);
            long costMs = (System.nanoTime() - begin) / 1_000_000;
            logInfo("[llm] chat done, preferred=" + (preferredModel == null ? "null" : preferredModel.name())
                    + ", model=" + (res == null || res.getModel() == null ? "null" : res.getModel().name())
                    + ", provider=" + (res == null ? "null" : safeOneLine(res.getProvider()))
                    + ", success=" + (res != null && res.isSuccess())
                    + ", promptChars=" + promptChars
                    + ", approxTokens=" + approxTokens
                    + ", elapsedMs=" + costMs
                    + ", usageKeys=" + (res == null || res.getUsage() == null ? "null" : res.getUsage().keySet()));
            if (res != null && !res.isSuccess()) {
                logWarn("[llm] chat failed, error=" + safeOneLine(res.getError()));
            }
            return res;
        } catch (Exception e) {
            long costMs = (System.nanoTime() - begin) / 1_000_000;
            logError("[llm] chat exception, preferred=" + (preferredModel == null ? "null" : preferredModel.name())
                    + ", promptChars=" + promptChars
                    + ", approxTokens=" + approxTokens
                    + ", elapsedMs=" + costMs
                    + ", error=" + safeOneLine(e.getMessage()), e);
            return LLMResult.fail(preferredModel == null ? ModelType.ALL : preferredModel, "LLMUtil", e.getMessage());
        }
    }

    public static LLMResult chatResult(List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages, boolean isStreamingProcess) {
        return chatResult(messages, isStreamingProcess, null);
    }

    public static LLMResult chatResult(List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages, boolean isStreamingProcess, ModelType preferredModel) {
        long begin = System.nanoTime();
        int msgCount = messages == null ? 0 : messages.size();
        String prompt = toPrompt(messages);
        int promptChars = prompt == null ? 0 : prompt.length();
        int approxTokens = promptChars <= 0 ? 0 : Math.max(1, promptChars / 4);
        try {
            LLMResult res = ROUTER.chat(messages, isStreamingProcess, preferredModel);
            long costMs = (System.nanoTime() - begin) / 1_000_000;
            logInfo("[llm] chat(messages) done, preferred=" + (preferredModel == null ? "null" : preferredModel.name())
                    + ", model=" + (res == null || res.getModel() == null ? "null" : res.getModel().name())
                    + ", provider=" + (res == null ? "null" : safeOneLine(res.getProvider()))
                    + ", success=" + (res != null && res.isSuccess())
                    + ", streaming=" + isStreamingProcess
                    + ", messageCount=" + msgCount
                    + ", promptChars=" + promptChars
                    + ", approxTokens=" + approxTokens
                    + ", elapsedMs=" + costMs
                    + ", usageKeys=" + (res == null || res.getUsage() == null ? "null" : res.getUsage().keySet()));
            if (res != null && !res.isSuccess()) {
                logWarn("[llm] chat(messages) failed, error=" + safeOneLine(res.getError()));
            }
            return res;
        } catch (Exception e) {
            long costMs = (System.nanoTime() - begin) / 1_000_000;
            logError("[llm] chat(messages) exception, preferred=" + (preferredModel == null ? "null" : preferredModel.name())
                    + ", streaming=" + isStreamingProcess
                    + ", messageCount=" + msgCount
                    + ", promptChars=" + promptChars
                    + ", approxTokens=" + approxTokens
                    + ", elapsedMs=" + costMs
                    + ", error=" + safeOneLine(e.getMessage()), e);
            return LLMResult.fail(preferredModel == null ? ModelType.ALL : preferredModel, "LLMUtil", e.getMessage());
        }
    }

    private static String safeOneLine(String s) {
        if (s == null) return "null";
        String v = s.replace("\n", "\\n").replace("\r", "\\r").trim();
        if (v.length() > 400) return v.substring(0, 400) + "...";
        return v;
    }

    /**
     * 判断是否配置了任意“远程”聊天模型的 Key。
     *
     * <p>用于在入口层决定是否走 LLM 工具规划链路，还是降级为“直连工具模式”。</p>
     */
    public static boolean hasAnyRemoteChatKeyConfigured() {
        AppConfig cfg = AppConfig.getInstance();
        return hasKey(cfg.getDeepSeekApiKey())
                || hasKey(cfg.getAliyunApiKey())
                || hasKey(cfg.getMoonshotApiKey())
                || hasKey(cfg.getMinimaxApiKey())
                || hasKey(cfg.getGlmApiKey())
                || hasKey(cfg.getGeminiApiKey());
    }

    private static ModelType resolveChatModel(ModelType preferredModel) {
        ModelType normalizedPreferred = normalize(preferredModel);
        if (normalizedPreferred != null && normalizedPreferred != ModelType.ALL && isModelConfigured(normalizedPreferred)) {
            return normalizedPreferred;
        }
        List<ModelType> fallbackOrder = Arrays.asList(
                ModelType.DEEPSEEK,
                ModelType.ALIYUN,
                ModelType.MOONSHOT,
                ModelType.MINIMAX,
                ModelType.GLM,
                ModelType.GEMINI,
                ModelType.OLLAMA
        );
        for (ModelType t : fallbackOrder) {
            if (isModelConfigured(t)) {
                return t;
            }
        }
        return ModelType.OLLAMA;
    }

    private static boolean isModelConfigured(ModelType modelType) {
        AppConfig cfg = AppConfig.getInstance();
        if (modelType == ModelType.DEEPSEEK) return hasKey(cfg.getDeepSeekApiKey());
        if (modelType == ModelType.ALIYUN) return hasKey(cfg.getAliyunApiKey());
        if (modelType == ModelType.ALIYUN_VL) return hasKey(cfg.getAliyunApiKey());
        if (modelType == ModelType.MOONSHOT) return hasKey(cfg.getMoonshotApiKey());
        if (modelType == ModelType.MINIMAX) return hasKey(cfg.getMinimaxApiKey());
        if (modelType == ModelType.GLM) return hasKey(cfg.getGlmApiKey());
        if (modelType == ModelType.GEMINI) return hasKey(cfg.getGeminiApiKey());
        if (modelType == ModelType.OLLAMA) return true;
        return false;
    }

    private static ModelType normalize(ModelType modelType) {
        if (modelType == null) return null;
        if (modelType == ModelType.ALIYUN_VL) return ModelType.ALIYUN;
        return modelType;
    }

    private static List<ModelType> getFallbackOrder() {
        return Arrays.asList(
                ModelType.DEEPSEEK,
                ModelType.ALIYUN,
                ModelType.MOONSHOT,
                ModelType.MINIMAX,
                ModelType.GLM,
                ModelType.GEMINI,
                ModelType.OLLAMA
        );
    }

    private static boolean hasKey(String v) {
        return v != null && !v.trim().isEmpty();
    }

    private static String toPrompt(List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Object m : messages) {
            if (m == null) continue;
            String role = tryInvokeToString(m, "role");
            if (role == null) role = tryInvokeToString(m, "getRole");
            String content = tryInvokeToString(m, "content");
            if (content == null) content = tryInvokeToString(m, "getContent");
            if (content == null) content = String.valueOf(m);
            if (role != null && !role.trim().isEmpty()) {
                sb.append(role.trim()).append(": ");
            }
            sb.append(content).append("\n");
        }
        return sb.toString().trim();
    }

    private static String tryInvokeToString(Object target, String methodName) {
        try {
            Object res = target.getClass().getMethod(methodName).invoke(target);
            if (res == null) return null;
            return String.valueOf(res);
        } catch (Exception ignored) {
            return null;
        }
    }

    // --- Alibaba Cloud (Qwen) ---

    private static class AliyunProvider implements LLMProvider {
        @Override
        public ModelType type() {
            return ModelType.ALIYUN;
        }

        @Override
        public boolean isConfigured(AppConfig cfg) {
            return cfg != null && hasKey(cfg.getAliyunApiKey());
        }

        @Override
        public LLMResult chat(String prompt) {
            try {
                Generation gen = new Generation();
                Message userMsg = Message.builder()
                        .role(Role.USER.getValue())
                        .content(prompt)
                        .build();

                GenerationParam param = GenerationParam.builder()
                        .model(LLMSettings.ALIYUN_CHAT_MODEL)
                        .messages(Arrays.asList(userMsg))
                        .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                        .apiKey(AppConfig.getInstance().getAliyunApiKey())
                        .build();

                GenerationResult result = gen.call(param);
                String text = result.getOutput().getChoices().get(0).getMessage().getContent();
                return LLMResult.ok(text, ModelType.ALIYUN, "AliyunProvider", null);
            } catch (ApiException | NoApiKeyException | InputRequiredException e) {
                logError("Alibaba Cloud Chat Error: " + e.getMessage(), e);
                return LLMResult.fail(ModelType.ALIYUN, "AliyunProvider", e.getMessage());
            } catch (Exception e) {
                logError("Alibaba Cloud Chat Error: " + e.getMessage(), e);
                return LLMResult.fail(ModelType.ALIYUN, "AliyunProvider", e.getMessage());
            }
        }
    }

    private static class GeminiProvider implements LLMProvider {
        @Override
        public ModelType type() {
            return ModelType.GEMINI;
        }

        @Override
        public boolean isConfigured(AppConfig cfg) {
            return cfg != null && hasKey(cfg.getGeminiApiKey());
        }

        @Override
        public LLMResult chat(String prompt) {
            try (Client client = Client.builder().apiKey(AppConfig.getInstance().getGeminiApiKey()).build()) {
                GenerateContentResponse response = client.models.generateContent(LLMSettings.GEMINI_CHAT_MODEL, prompt, null);
                String text = response == null ? "" : response.text();
                return LLMResult.ok(text, ModelType.GEMINI, "GeminiProvider", null);
            } catch (Exception e) {
                logError("Gemini Chat Error: " + e.getMessage(), e);
                return LLMResult.fail(ModelType.GEMINI, "GeminiProvider", e.getMessage());
            }
        }
    }

    private static class OllamaProvider implements LLMProvider {
        @Override
        public ModelType type() {
            return ModelType.OLLAMA;
        }

        @Override
        public boolean isConfigured(AppConfig cfg) {
            return true;
        }

        @Override
        public LLMResult chat(String prompt) {
            try {
                String text = chatWithOllama(prompt, OLLAMA_MODEL_QWEN3_8B, null, false);
                return LLMResult.ok(text, ModelType.OLLAMA, "OllamaProvider", null);
            } catch (Exception e) {
                logError("Ollama Chat Error: " + e.getMessage(), e);
                return LLMResult.fail(ModelType.OLLAMA, "OllamaProvider", e.getMessage());
            }
        }
    }

    private static class DeepSeekProvider implements LLMProvider {
        @Override
        public ModelType type() {
            return ModelType.DEEPSEEK;
        }

        @Override
        public boolean isConfigured(AppConfig cfg) {
            return cfg != null && hasKey(cfg.getDeepSeekApiKey());
        }

        @Override
        public LLMResult chat(String prompt) {
            if (!hasKey(AppConfig.getInstance().getDeepSeekApiKey())) {
                return LLMResult.fail(ModelType.DEEPSEEK, "DeepSeekProvider", "DeepSeek API Key is missing!");
            }

            DeepSeekClient deepseekClient = new DeepSeekClient.Builder()
                    .openAiApiKey(AppConfig.getInstance().getDeepSeekApiKey())
                    .baseUrl(LLMSettings.DEEPSEEK_BASE_URL)
                    .connectTimeout(java.time.Duration.ofSeconds(LLMSettings.DEEPSEEK_CONNECT_TIMEOUT_SECONDS))
                    .writeTimeout(java.time.Duration.ofSeconds(LLMSettings.DEEPSEEK_WRITE_TIMEOUT_SECONDS))
                    .readTimeout(java.time.Duration.ofSeconds(LLMSettings.DEEPSEEK_READ_TIMEOUT_SECONDS))
                    .callTimeout(java.time.Duration.ofSeconds(LLMSettings.DEEPSEEK_CALL_TIMEOUT_SECONDS))
                    .model(LLMSettings.DEEPSEEK_MODEL)
                    .logRequests(logEnabled(LogLevel.DEBUG))
                    .logResponses(logEnabled(LogLevel.DEBUG))
                    .build();

            try {
                UserMessage userMessage = UserMessage.builder().addText(prompt).build();
                ChatCompletionRequest request = ChatCompletionRequest.builder().messages(userMessage).build();

                ChatCompletionResponse response = deepseekClient
                        .chatCompletion(new OpenAiClientContext(), request)
                        .execute();

                String responseText = "";
                if (response != null && response.choices() != null && !response.choices().isEmpty()) {
                    responseText = response.choices().get(0).message().content();
                }
                return LLMResult.ok(responseText, ModelType.DEEPSEEK, "DeepSeekProvider", null);
            } catch (Exception e) {
                logError("DeepSeek Chat Error: " + e.getMessage(), e);
                return LLMResult.fail(ModelType.DEEPSEEK, "DeepSeekProvider", e.getMessage());
            } finally {
                deepseekClient.shutdown();
            }
        }

        @Override
        public LLMResult chat(List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages, boolean isStreamingProcess) {
            if (!hasKey(AppConfig.getInstance().getDeepSeekApiKey())) {
                return LLMResult.fail(ModelType.DEEPSEEK, "DeepSeekProvider", "DeepSeek API Key is missing!");
            }

            if (!isStreamingProcess) {
                DeepSeekClient deepseekClient = new DeepSeekClient.Builder()
                        .openAiApiKey(AppConfig.getInstance().getDeepSeekApiKey())
                        .baseUrl(LLMSettings.DEEPSEEK_BASE_URL)
                        .model(LLMSettings.DEEPSEEK_MODEL)
                        .connectTimeout(java.time.Duration.ofSeconds(LLMSettings.DEEPSEEK_CONNECT_TIMEOUT_SECONDS))
                        .writeTimeout(java.time.Duration.ofSeconds(LLMSettings.DEEPSEEK_WRITE_TIMEOUT_SECONDS))
                        .readTimeout(java.time.Duration.ofSeconds(LLMSettings.DEEPSEEK_READ_TIMEOUT_SECONDS))
                        .callTimeout(java.time.Duration.ofSeconds(LLMSettings.DEEPSEEK_CALL_TIMEOUT_SECONDS))
                        .logRequests(logEnabled(LogLevel.DEBUG))
                        .logResponses(logEnabled(LogLevel.DEBUG))
                        .build();
                try {
                    ChatCompletionRequest request = ChatCompletionRequest.builder()
                            .messages(messages).build();

                    ChatCompletionResponse response = deepseekClient
                            .chatCompletion(new OpenAiClientContext(), request)
                            .execute();

                    String responseText = "";
                    if (response != null && response.choices() != null && !response.choices().isEmpty()) {
                        responseText = response.choices().get(0).message().content();
                    } else {
                        logWarn("DeepSeek: 未收到有效响应");
                    }
                    return LLMResult.ok(responseText, ModelType.DEEPSEEK, "DeepSeekProvider", null);
                } catch (Exception ex) {
                    logError("调用 DeepSeek API 失败: " + ex.getMessage(), ex);
                    return LLMResult.fail(ModelType.DEEPSEEK, "DeepSeekProvider", ex.getMessage());
                } finally {
                    deepseekClient.shutdown();
                }
            }

            DeepSeekClient deepseekClient = new DeepSeekClient.Builder()
                    .openAiApiKey(AppConfig.getInstance().getDeepSeekApiKey())
                    .baseUrl(LLMSettings.DEEPSEEK_BASE_URL)
                    .model(LLMSettings.DEEPSEEK_MODEL)
                    .connectTimeout(java.time.Duration.ofSeconds(LLMSettings.DEEPSEEK_CONNECT_TIMEOUT_SECONDS))
                    .writeTimeout(java.time.Duration.ofSeconds(LLMSettings.DEEPSEEK_WRITE_TIMEOUT_SECONDS))
                    .readTimeout(java.time.Duration.ofSeconds(LLMSettings.DEEPSEEK_READ_TIMEOUT_SECONDS))
                    .callTimeout(java.time.Duration.ofSeconds(LLMSettings.DEEPSEEK_CALL_TIMEOUT_SECONDS))
                    .logStreamingResponses(logEnabled(LogLevel.DEBUG))
                    .build();

            StringBuilder sb = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);

            try {
                ChatCompletionRequest request = ChatCompletionRequest.builder()
                        .model(LLMSettings.DEEPSEEK_MODEL)
                        .messages(messages)
                        .stream(true)
                        .streamOptions(StreamOptions.builder().includeUsage(true).build())
                        .build();

                Flux<ChatCompletionResponse> flux = deepseekClient.chatFluxCompletion(request);

                flux.subscribe(
                        chunk -> {
                            if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                                String delta = chunk.choices().get(0).delta().content();
                                if (delta != null) {
                                    sb.append(delta);
                                }
                            }
                        },
                        error -> {
                            logError("DeepSeek 流式错误: " + error.getMessage(), error);
                            latch.countDown();
                        },
                        latch::countDown
                );

                latch.await();
            } catch (Exception ex) {
                logError("调用 DeepSeek API 失败: " + ex.getMessage(), ex);
                return LLMResult.fail(ModelType.DEEPSEEK, "DeepSeekProvider", ex.getMessage());
            } finally {
                deepseekClient.shutdown();
            }

            return LLMResult.ok(sb.toString(), ModelType.DEEPSEEK, "DeepSeekProvider", null);
        }
    }

    private static class MoonshotProvider implements LLMProvider {
        @Override
        public ModelType type() {
            return ModelType.MOONSHOT;
        }

        @Override
        public boolean isConfigured(AppConfig cfg) {
            return cfg != null && hasKey(cfg.getMoonshotApiKey());
        }

        @Override
        public LLMResult chat(String prompt) {
            String apiKey = AppConfig.getInstance().getMoonshotApiKey();
            if (!hasKey(apiKey)) {
                return LLMResult.fail(ModelType.MOONSHOT, "MoonshotProvider", "Moonshot API Key is missing!");
            }
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("model", LLMSettings.MOONSHOT_MODEL);
                Map<String, Object> message = new HashMap<>();
                message.put("role", "user");
                message.put("content", prompt);
                payload.put("messages", java.util.List.of(message));
                payload.put("stream", false);
                Map<String, Object> thinking = new HashMap<>();
                thinking.put("type", normalizeThinkingType(AppConfig.getInstance().getMoonshotThinking()));
                payload.put("thinking", thinking);
                return postOpenAiLikeChatCompletionResult(LLMSettings.MOONSHOT_CHAT_COMPLETIONS_URL, apiKey, payload, ModelType.MOONSHOT, "MoonshotProvider");
            } catch (Exception e) {
                logError("Moonshot Chat Error: " + e.getMessage(), e);
                return LLMResult.fail(ModelType.MOONSHOT, "MoonshotProvider", e.getMessage());
            }
        }
    }

    private static class MinimaxProvider implements LLMProvider {
        @Override
        public ModelType type() {
            return ModelType.MINIMAX;
        }

        @Override
        public boolean isConfigured(AppConfig cfg) {
            return cfg != null && hasKey(cfg.getMinimaxApiKey());
        }

        @Override
        public LLMResult chat(String prompt) {
            String apiKey = AppConfig.getInstance().getMinimaxApiKey();
            if (!hasKey(apiKey)) {
                return LLMResult.fail(ModelType.MINIMAX, "MinimaxProvider", "Minimax API Key is missing!");
            }
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("model", LLMSettings.MINIMAX_MODEL);
                Map<String, Object> message = new HashMap<>();
                message.put("role", "user");
                message.put("content", prompt);
                payload.put("messages", java.util.List.of(message));
                payload.put("stream", false);

                Map<String, Object> extraBody = new HashMap<>();
                extraBody.put("reasoning_split", Boolean.TRUE);
                payload.put("extra_body", extraBody);

                LLMResult res = postOpenAiLikeChatCompletionResult(LLMSettings.MINIMAX_CHAT_COMPLETIONS_URL, apiKey, payload, ModelType.MINIMAX, "MinimaxProvider");
                if (res.isSuccess()) {
                    String content = res.getText().replaceAll("(?s)<think>.*?</think>", "").trim();
                    return LLMResult.ok(content, ModelType.MINIMAX, "MinimaxProvider", res.getUsage());
                }
                return res;
            } catch (Exception e) {
                logError("Minimax Chat Exception: " + e.getMessage(), e);
                return LLMResult.fail(ModelType.MINIMAX, "MinimaxProvider", e.getMessage());
            }
        }
    }

    private static class GlmProvider implements LLMProvider {
        @Override
        public ModelType type() {
            return ModelType.GLM;
        }

        @Override
        public boolean isConfigured(AppConfig cfg) {
            return cfg != null && hasKey(cfg.getGlmApiKey());
        }

        @Override
        public LLMResult chat(String prompt) {
            String apiKey = AppConfig.getInstance().getGlmApiKey();
            if (!hasKey(apiKey)) {
                return LLMResult.fail(ModelType.GLM, "GlmProvider", "GLM API Key is missing!");
            }
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("model", LLMSettings.GLM_MODEL);
                Map<String, Object> message = new HashMap<>();
                message.put("role", "user");
                message.put("content", prompt);
                payload.put("messages", java.util.List.of(message));
                payload.put("stream", false);
                Map<String, Object> thinking = new HashMap<>();
                thinking.put("type", normalizeThinkingType(AppConfig.getInstance().getGlmThinking()));
                payload.put("thinking", thinking);

                LLMResult res = postOpenAiLikeChatCompletionResult(LLMSettings.GLM_CHAT_COMPLETIONS_URL, apiKey, payload, ModelType.GLM, "GlmProvider");
                if (res.isSuccess()) {
                    String content = res.getText().replaceAll("(?s)<think>.*?</think>", "").trim();
                    return LLMResult.ok(content, ModelType.GLM, "GlmProvider", res.getUsage());
                }
                return res;
            } catch (Exception e) {
                logError("GLM Chat Exception: " + e.getMessage(), e);
                return LLMResult.fail(ModelType.GLM, "GlmProvider", e.getMessage());
            }
        }
    }

    /**
     * 与阿里云 Qwen 模型进行对话
     *
     * @param prompt 提示词
     * @return 模型回复
     */
    public static String chatWithAliyun(String prompt) {
        return chatResult(prompt, ModelType.ALIYUN).getText();
    }
    
    // --- Moonshot (月之暗面, OpenAI-compatible) ---
    public static String chatWithMoonshot(String prompt) {
        return chatResult(prompt, ModelType.MOONSHOT).getText();
    }

    /**
     * 使用阿里云 Qwen-VL 模型分析图片
     *
     * @param imageFile 图片文件
     * @param prompt 提示词
     * @return 模型回复
     */
    public static String analyzeImageWithAliyun(java.io.File imageFile, String prompt) {
        if (imageFile == null) return "";
        return analyzeImageWithAliyun(java.util.Collections.singletonList(imageFile.getAbsolutePath()), prompt);
    }

    public static String analyzeImageWithAliyun(java.util.List<String> imageSources, String prompt) {
        try {
            MultiModalConversation conv = new MultiModalConversation();

            java.util.List<Map<String, Object>> contents = new java.util.ArrayList<>();
            if (imageSources != null) {
                for (String src : imageSources) {
                    if (src == null || src.trim().isEmpty()) continue;
                    src = src.trim();
                    String imageUrl = null;
                    if (src.startsWith("http://") || src.startsWith("https://")) {
                        imageUrl = src;
                    } else {
                        java.io.File f = null;
                        if (src.startsWith("file:")) {
                            try {
                                f = java.nio.file.Paths.get(java.net.URI.create(src)).toFile();
                            } catch (Exception ignored) {
                                f = null;
                            }
                        }
                        if (f == null) f = new java.io.File(src);
                        if (f.exists()) {
                            imageUrl = OSSUtil.uploadFile(f);
                        }
                    }
                    if (imageUrl == null || imageUrl.trim().isEmpty()) {
                        logWarn("Failed to resolve image source for Aliyun analysis: " + src);
                        continue;
                    }
                    contents.add(Collections.singletonMap("image", imageUrl));
                }
            }
            if (contents.isEmpty()) {
                logWarn("Failed to resolve any images for Aliyun analysis.");
                return "Error: Image upload failed.";
            }

            Map<String, Object> textContent = Collections.singletonMap("text", prompt);
            contents.add(textContent);

            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(contents)
                    .build();

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .model(LLMSettings.ALIYUN_VL_MODEL)
                    .message(userMsg)
                    .apiKey(AppConfig.getInstance().getAliyunApiKey())
                    .build();

            MultiModalConversationResult result = conv.call(param);
            return result.getOutput().getChoices().get(0).getMessage().getContent().get(0).get("text").toString();
        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            logError("Alibaba Cloud VL Error: " + e.getMessage(), e);
            return "";
        }
    }

    public static String generateContentWithAliyunByFile(java.io.File file, String summaryPrompt) {
        try {
            String content = PodCastUtil.readFileContent(file);
            return chatWithAliyun(summaryPrompt + "\n\n" + content);
        } catch (IOException e) {
            logError("Read file error: " + e.getMessage(), e);
            return "";
        }
    }

    // --- DeepSeek ---

    public static String chatWithDeepSeek(String prompt) {
        return chatResult(prompt, ModelType.DEEPSEEK).getText();
    }

    public static String chatWithDeepSeek(List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages, boolean isStreamingProcess) {
        return chatResult(messages, isStreamingProcess, ModelType.DEEPSEEK).getText();
    }

    public static String generateContentWithDeepSeekByFile(java.io.File file, String summaryPrompt, boolean isStreamingProcess) throws IOException {
        String content = PodCastUtil.readFileContent(file);

        UserMessage userMessage = UserMessage.builder()
                .addText(summaryPrompt)
                .addText(content).build();

        List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages = new ArrayList<>();
        messages.add(userMessage);

        return chatWithDeepSeek(messages, isStreamingProcess);
    }

    // --- Gemini ---

    public static String chatWithGemini(String prompt) {
        return chatResult(prompt, ModelType.GEMINI).getText();
    }

    public static String generateSummaryWithGemini(java.io.File pdfFile, String summaryPrompt) {
        String responseText = "";
        try (Client client = Client.builder()
                .apiKey(AppConfig.getInstance().getGeminiApiKey())
                .build()) {

            File uploadedFile = client.files.upload(
                    pdfFile.getAbsolutePath(),
                    UploadFileConfig.builder()
                            .mimeType("application/pdf")
                            .build()
            );

            logInfo("文件上传成功: " + uploadedFile.uri().get());

            Content content = Content.fromParts(
                    Part.fromText(summaryPrompt),
                    Part.fromUri(uploadedFile.uri().get(), uploadedFile.mimeType().get())
            );

            GenerateContentResponse response = client.models.generateContent(
                    LLMSettings.GEMINI_PDF_MODEL,
                    content,
                    null);

            responseText = response.text();
        } catch (Exception ex) {
            logError("调用 Gemini API 失败: " + ex.getMessage(), ex);
        }
        return responseText;
    }

    public static String analyzeImageWithGemini(java.io.File imageFile, String prompt) {
        String responseText = "";
        try (Client client = Client.builder()
                .apiKey(AppConfig.getInstance().getGeminiApiKey())
                .build()) {

            File uploadedFile = client.files.upload(
                    imageFile.getAbsolutePath(),
                    UploadFileConfig.builder()
                            .mimeType("image/png")
                            .build()
            );

            logInfo("图片上传成功: " + uploadedFile.uri().get());

            Content content = Content.fromParts(
                    Part.fromText(prompt),
                    Part.fromUri(uploadedFile.uri().get(), uploadedFile.mimeType().get())
            );

            GenerateContentResponse response = client.models.generateContent(
                    LLMSettings.GEMINI_VISION_MODEL,
                    content,
                    null);

            responseText = response.text();
        } catch (Exception ex) {
            logError("调用 Gemini Vision API 失败: " + ex.getMessage(), ex);
        }
        return responseText;
    }

    public static String analyzeImagesWithGemini(java.io.File[] imageFiles, String prompt) {
        String responseText = "";
        try (Client client = Client.builder()
                .apiKey(AppConfig.getInstance().getGeminiApiKey())
                .build()) {
            List<Part> parts = new ArrayList<>();
            parts.add(Part.fromText(prompt));
            for (java.io.File f : imageFiles) {
                if (f != null && f.exists()) {
                    File uploadedFile = client.files.upload(
                            f.getAbsolutePath(),
                            UploadFileConfig.builder().mimeType("image/png").build()
                    );
                    parts.add(Part.fromUri(uploadedFile.uri().get(), uploadedFile.mimeType().get()));
                }
            }
            Content content = Content.fromParts(parts.toArray(new Part[0]));
            GenerateContentResponse response =
                    client.models.generateContent(LLMSettings.GEMINI_VISION_MODEL, content, null);
            responseText = response.text();
        } catch (Exception ex) {
            logError("调用 Gemini Vision API 失败: " + ex.getMessage(), ex);
        }
        return responseText;
    }

    public static void generateImageWithGemini(String fileString, String outputDirectory, String imagePrompt) {
        try (Client client = Client.builder().apiKey(AppConfig.getInstance().getGeminiApiKey()).build()) {

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseModalities(Arrays.asList("IMAGE"))
                    .build();

            File uploadedFile = client.files.upload(
                    fileString,
                    UploadFileConfig.builder()
                            .mimeType("application/text")
                            .build()
            );

            Content content = Content.fromParts(
                    Part.fromText(imagePrompt),
                    Part.fromUri(uploadedFile.uri().get(), uploadedFile.mimeType().get())
            );

            GenerateContentResponse response = client.models.generateContent(
                    LLMSettings.GEMINI_IMAGE_MODEL,
                    content,
                    config);

            for (Part part : response.parts()) {
                if (part.text().isPresent()) {
                    logInfo(part.text().get());
                } else if (part.inlineData().isPresent()) {
                    try {
                        var blob = part.inlineData().get();
                        if (blob.data().isPresent()) {
                            Path outputDirPath = Paths.get(outputDirectory);
                            if (!Files.exists(outputDirPath)) {
                                Files.createDirectories(outputDirPath);
                                logInfo("创建输出目录: " + outputDirectory);
                            }

                            String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                            String originalFileName = Paths.get(fileString).getFileName().toString().replaceFirst("\\.[^.]+$", "");
                            String imageFileName = String.format("%s_%s_generated_image.png", originalFileName, timestamp);

                            Path imageFilePath = outputDirPath.resolve(imageFileName);
                            Files.write(imageFilePath, blob.data().get());
                            logInfo("图片生成成功: " + imageFilePath);
                        }
                    } catch (IOException ex) {
                        logError("写入图片文件失败: " + ex.getMessage(), ex);
                    }
                }
            }
        } catch (Exception e) {
            logError("调用 Gemini Image API 失败: " + e.getMessage(), e);
        }
    }

    // --- Ollama ---

    /**
     * 与本地 Ollama 模型进行简单对话
     * 
     * @param prompt 提示词
     * @param modelName 模型名称
     * @return 模型回复
     */
    public static String chatWithOllama(String prompt, String modelName,String chatHistroy,boolean isThinking) {
        return chatWithOllama(prompt, modelName, chatHistroy, isThinking, OLLAMA_HOST);
    }

    /**
     * 与本地 Ollama 模型进行简单对话 (指定 Host)
     * 
     * @param prompt 提示词
     * @param modelName 模型名称
     * @param host Ollama 服务地址 (e.g. http://localhost:11434)
     * @return 模型回复
     */
    public static String chatWithOllama(String prompt, String modelName,String chatHistroy,boolean isThinking, String host) {
        OllamaAPI ollamaAPI = new OllamaAPI(host);
        ollamaAPI.setRequestTimeoutSeconds(120);
        try {
            OllamaChatRequestBuilder builder = OllamaChatRequestBuilder.getInstance(modelName);
            builder.withKeepAlive("10m");
            builder.withThinking(isThinking);

            if (chatHistroy != null)
                builder.withMessage(OllamaChatMessageRole.ASSISTANT, chatHistroy);

            builder.withMessage(OllamaChatMessageRole.USER, prompt);
            OllamaChatRequest request = builder.build();
            OllamaChatResult chatResult = ollamaAPI.chat(request);
            return chatResult.getResponseModel().getMessage().getContent();
        } catch (Exception e) {
             logError("Ollama Chat Error: " + e.getMessage(), e);
             return "";
        }
    }

    /**
     * 与本地 Ollama 模型进行流式对话
     * 
     * @param prompt 提示词
     * @param modelName 模型名称
     * @param handler 流式处理器
     * @return 完整回复
     */
    public static OllamaChatResult chatWithOllamaStreaming(String prompt, String modelName,String chatHistroy,boolean isThinking,
                                                                    OllamaStreamHandler thinkHandler,OllamaStreamHandler responseHandler) {
        return chatWithOllamaStreaming(prompt, modelName, chatHistroy, isThinking, thinkHandler, responseHandler, OLLAMA_HOST);
    }

    /**
     * 与本地 Ollama 模型进行流式对话 (指定 Host)
     * 
     * @param prompt 提示词
     * @param modelName 模型名称
     * @param handler 流式处理器
     * @param host Ollama 服务地址
     * @return 完整回复
     */
    public static OllamaChatResult chatWithOllamaStreaming(String prompt, String modelName,String chatHistroy,boolean isThinking,
                                                                    OllamaStreamHandler thinkHandler,OllamaStreamHandler responseHandler, String host) {
        OllamaAPI ollamaAPI = new OllamaAPI(host);
        ollamaAPI.setRequestTimeoutSeconds(120);
        try {
             OllamaChatRequestBuilder builder = OllamaChatRequestBuilder.getInstance(modelName);
             builder.withKeepAlive("10m");
             builder.withMessage(OllamaChatMessageRole.USER, prompt);

             builder.withThinking(isThinking);

            if (chatHistroy != null)
                builder.withMessage(OllamaChatMessageRole.ASSISTANT, chatHistroy);

             OllamaChatRequest request = builder.build();
             
             OllamaChatResult chatResult;
             
             if (isThinking)
                chatResult = ollamaAPI.chat(request, thinkHandler,responseHandler);
             else
                chatResult = ollamaAPI.chat(request,null,responseHandler);

             return chatResult;
        } catch (Exception e) {
             logError("Ollama Streaming Chat Error: " + e.getMessage(), e);
             return null;
        }
    }

    
    /**
     * 与本地 Ollama 模型进行带图片对话
     * 
     * @param prompt 提示词
     * @param modelName 模型名称 (e.g. qwen3-vl:8b)
     * @param imageSources 图片路径列表 (支持本地文件路径或HTTP/HTTPS链接)
     * @return 模型回复
     */
    public static OllamaChatResult chatWithOllamaImage(String prompt, String modelName,String chatHistroy,boolean isThinking, List<String> imageSources) {
        return chatWithOllamaImage(prompt, modelName, chatHistroy, isThinking, imageSources, OLLAMA_HOST);
    }

    /**
     * 与本地 Ollama 模型进行带图片对话 (指定 Host)
     * 
     * @param prompt 提示词
     * @param modelName 模型名称 (e.g. qwen3-vl:8b)
     * @param imageSources 图片路径列表 (支持本地文件路径或HTTP/HTTPS链接)
     * @param host Ollama 服务地址
     * @return 模型回复
     */
    public static OllamaChatResult chatWithOllamaImage(String prompt, String modelName,String chatHistroy,boolean isThinking, List<String> imageSources, String host) {
        OllamaAPI ollamaAPI = new OllamaAPI(host);
        ollamaAPI.setRequestTimeoutSeconds(120);
        //ollamaAPI.setVerbose(false); 
        try {
            List<byte[]> images = new ArrayList<>();
            if (imageSources != null) {
                for (String src : imageSources) {
                    if (src == null || src.trim().isEmpty()) {
                        continue;
                    }
                    src = src.trim();
                    try {
                        byte[] imageBytes;
                        if (src.startsWith("http://") || src.startsWith("https://")) {
                            // Download from URL with optimization (timeout, UA)
                            java.net.URL url = java.net.URI.create(src).toURL();
                            java.net.URLConnection conn = url.openConnection();
                            conn.setConnectTimeout(10000); // 10 seconds connect timeout
                            conn.setReadTimeout(30000);    // 30 seconds read timeout
                            
                            // Set a User-Agent to avoid being blocked by some servers
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
                            
                            try (java.io.InputStream in = conn.getInputStream()) {
                                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                                int nRead;
                                byte[] data = new byte[16384];
                                while ((nRead = in.read(data, 0, data.length)) != -1) {
                                    buffer.write(data, 0, nRead);
                                }
                                imageBytes = buffer.toByteArray();
                            }
                        } else {
                            // Read from local file
                            imageBytes = Files.readAllBytes(Paths.get(src));
                        }
                        images.add(imageBytes);
                    } catch (IOException e) {
                        logWarn("Failed to read image: " + src + ", error: " + e.getMessage());
                        // Continue to next image or return error? 
                        // Current logic: ignore failed image
                    }
                }
            }
            
            OllamaChatMessage message = new OllamaChatMessage(OllamaChatMessageRole.USER, prompt);
            if (!images.isEmpty()) {
                message.setImages(images);
            }
            
            List<OllamaChatMessage> messages = new ArrayList<>();
            if (chatHistroy != null) {
                 messages.add(new OllamaChatMessage(OllamaChatMessageRole.ASSISTANT, chatHistroy));
            }
            messages.add(message);
            
            OllamaChatRequestBuilder builder = OllamaChatRequestBuilder.getInstance(modelName);
            builder.withKeepAlive("10m");
            // builder.withMessages(messages); // Causes empty response, use request.setMessages instead

            builder.withThinking(isThinking);

            OllamaChatRequest request = builder.build();
            request.setMessages(messages);
            
            OllamaChatResult chatResult = ollamaAPI.chat(request);
            return chatResult;
        } catch (Exception e) {
             logError("Ollama Image Chat Error: " + e.getMessage(), e);
             return null;
        }
    }

    // --- Minimax ---

    public static String chatWithMinimax(String prompt) {
        return chatResult(prompt, ModelType.MINIMAX).getText();
    }

    // --- Zhipu GLM ---

    public static String chatWithGLM(String prompt) {
        return chatResult(prompt, ModelType.GLM).getText();
    }

    private static String normalizeThinkingType(String thinkingRaw) {
        String thinkingNorm = thinkingRaw == null ? "" : thinkingRaw.trim().toLowerCase();
        if (thinkingNorm.isEmpty()) return "disabled";
        if ("enabled".equals(thinkingNorm) || "enable".equals(thinkingNorm) || "true".equals(thinkingNorm) || "1".equals(thinkingNorm) || "on".equals(thinkingNorm)) {
            return "enabled";
        }
        if ("disabled".equals(thinkingNorm) || "disable".equals(thinkingNorm) || "false".equals(thinkingNorm) || "0".equals(thinkingNorm) || "off".equals(thinkingNorm)) {
            return "disabled";
        }
        return "disabled";
    }

    private static LLMResult postOpenAiLikeChatCompletionResult(String url, String apiKey, Map<String, Object> payload, ModelType modelType, String providerName) throws Exception {
        String jsonBody = com.alibaba.fastjson2.JSON.toJSONString(payload);
        int attempts = Math.max(1, LLMSettings.HTTP_RETRY_MAX_ATTEMPTS);

        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(LLMSettings.HTTP_REQUEST_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            try {
                HttpResponse<String> response = SHARED_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code >= 200 && code < 300) {
                    com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(response.body());
                    com.alibaba.fastjson2.JSONArray choices = json.getJSONArray("choices");
                    String content = "";
                    if (choices != null && !choices.isEmpty()) {
                        com.alibaba.fastjson2.JSONObject first = choices.getJSONObject(0);
                        com.alibaba.fastjson2.JSONObject msg = first.getJSONObject("message");
                        if (msg != null) {
                            String c = msg.getString("content");
                            content = c == null ? "" : c;
                        }
                    }

                    Map<String, Object> usage = null;
                    com.alibaba.fastjson2.JSONObject usageObj = json.getJSONObject("usage");
                    if (usageObj != null) {
                        usage = usageObj.toJavaObject(Map.class);
                    }

                    return LLMResult.ok(content, modelType, providerName, usage);
                }

                boolean retryable = code == 408 || code == 409 || code == 425 || code == 429 || (code >= 500 && code <= 599);
                if (!retryable || attempt == attempts) {
                    String err = "HTTP " + code + " - " + response.body();
                    logError("LLM HTTP Error: " + err, null);
                    return LLMResult.fail(modelType, providerName, err);
                }
            } catch (IOException e) {
                if (attempt == attempts) throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }

            try {
                Thread.sleep(LLMSettings.HTTP_RETRY_BACKOFF_MILLIS * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        return LLMResult.fail(modelType, providerName, "Unknown error");
    }

    private static String postOpenAiLikeChatCompletion(String url, String apiKey, Map<String, Object> payload) throws Exception {
        LLMResult res = postOpenAiLikeChatCompletionResult(url, apiKey, payload, ModelType.ALL, "OpenAiLike");
        return res == null ? "" : res.getText();
    }
}
