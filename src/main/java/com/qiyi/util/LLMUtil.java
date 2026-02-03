package com.qiyi.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
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

    public static final String OLLAMA_HOST = "http://localhost:11434";
    public static final String OLLAMA_MODEL_QWEN3_VL_8B = "qwen3-vl:8b";
    public static final String OLLAMA_MODEL_QWEN3_8B = "qwen3:8b";
    public static final String OLLAMA_MODEL_HUNYUAN_MT = "hunyuan-mt:latest";

    // --- Alibaba Cloud (Qwen) ---

    /**
     * 与阿里云 Qwen 模型进行对话
     *
     * @param prompt 提示词
     * @return 模型回复
     */
    public static String chatWithAliyun(String prompt) {
        try {
            Generation gen = new Generation();
            Message userMsg = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();
            
            GenerationParam param = GenerationParam.builder()
                    .model("qwen3-max") // 使用通义千问-Max
                    .messages(Arrays.asList(userMsg))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .apiKey(AppConfig.getInstance().getAliyunApiKey())
                    .build();
            
            GenerationResult result = gen.call(param);
            return result.getOutput().getChoices().get(0).getMessage().getContent();
        } catch (ApiException | NoApiKeyException | InputRequiredException e) {
            System.err.println("Alibaba Cloud Chat Error: " + e.getMessage());
            e.printStackTrace();
            return "";
        }
    }
    
    // --- Moonshot (月之暗面, OpenAI-compatible) ---
    public static String chatWithMoonshot(String prompt) {
        String apiKey = AppConfig.getInstance().getMoonshotApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.err.println("Moonshot API Key is missing!");
            return "";
        }
        try {
            String thinkingType = AppConfig.getInstance().getMoonshotThinking();
            if (thinkingType == null || thinkingType.trim().isEmpty()) {
                thinkingType = "disabled";
            }
            java.util.Map<String, Object> message = new java.util.HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("model", "kimi-k2.5");
            payload.put("messages", java.util.List.of(message));
            payload.put("stream", false);
            java.util.Map<String, Object> thinking = new java.util.HashMap<>();
            thinking.put("type", thinkingType);
            payload.put("thinking", thinking);
            
            String jsonBody = com.alibaba.fastjson2.JSON.toJSONString(payload);
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.moonshot.cn/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSONObject.parseObject(response.body());
                com.alibaba.fastjson2.JSONArray choices = json.getJSONArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    com.alibaba.fastjson2.JSONObject first = choices.getJSONObject(0);
                    com.alibaba.fastjson2.JSONObject msg = first.getJSONObject("message");
                    if (msg != null) {
                        return msg.getString("content");
                    }
                }
                return "";
            } else {
                System.err.println("Moonshot Chat Error: HTTP " + response.statusCode() + " - " + response.body());
                return "";
            }
        } catch (Exception e) {
            System.err.println("Moonshot Chat Error: " + e.getMessage());
            e.printStackTrace();
            return "";
        }
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
                        System.err.println("Failed to resolve image source for Aliyun analysis: " + src);
                        continue;
                    }
                    contents.add(Collections.singletonMap("image", imageUrl));
                }
            }
            if (contents.isEmpty()) {
                System.err.println("Failed to resolve any images for Aliyun analysis.");
                return "Error: Image upload failed.";
            }

            Map<String, Object> textContent = Collections.singletonMap("text", prompt);
            contents.add(textContent);

            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(contents)
                    .build();

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .model("qwen3-vl-plus") // 使用 Qwen3-VL-Plus
                    .message(userMsg)
                    .apiKey(AppConfig.getInstance().getAliyunApiKey())
                    .build();

            MultiModalConversationResult result = conv.call(param);
            return result.getOutput().getChoices().get(0).getMessage().getContent().get(0).get("text").toString();
        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            System.err.println("Alibaba Cloud VL Error: " + e.getMessage());
            e.printStackTrace();
            return "";
        }
    }

    public static String generateContentWithAliyunByFile(java.io.File file, String summaryPrompt) {
        try {
            String content = PodCastUtil.readFileContent(file);
            return chatWithAliyun(summaryPrompt + "\n\n" + content);
        } catch (IOException e) {
            System.err.println("Read file error: " + e.getMessage());
            return "";
        }
    }

    // --- DeepSeek ---

    public static String chatWithDeepSeek(String prompt) {
        String responseText = "";

        DeepSeekClient deepseekClient = new DeepSeekClient.Builder()
                .openAiApiKey(AppConfig.getInstance().getDeepSeekApiKey())
                .baseUrl("https://api.deepseek.com")
                .connectTimeout(java.time.Duration.ofSeconds(30))  // 连接超时
                .writeTimeout(java.time.Duration.ofSeconds(30))   // 写入超时（发送请求）
                .readTimeout(java.time.Duration.ofSeconds(600))   // 读取超时（等待响应）
                .callTimeout(java.time.Duration.ofSeconds(610))   // 整个调用超时，比readTimeout稍长
                .model("deepseek-chat")
                .build();

        try {
            UserMessage userMessage = UserMessage.builder().addText(prompt).build();
            ChatCompletionRequest request = ChatCompletionRequest.builder().messages(userMessage).build();

            ChatCompletionResponse response = deepseekClient
                    .chatCompletion(new OpenAiClientContext(), request)
                    .execute();

            if (response != null && response.choices() != null && !response.choices().isEmpty()) {
                responseText = response.choices().get(0).message().content();
            }
        } catch (Exception e) {
            System.out.println("DeepSeek Chat Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            deepseekClient.shutdown();
        }
        return responseText;
    }

    public static String chatWithDeepSeek(List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages, boolean isStreamingProcess) {
        String responseText = "";

        if (isStreamingProcess) {
            DeepSeekClient deepseekClient = new DeepSeekClient.Builder()
                    .openAiApiKey(AppConfig.getInstance().getDeepSeekApiKey())
                    .baseUrl("https://api.deepseek.com")
                    .model("deepseek-chat")
                    .logStreamingResponses(true)
                    .build();

            StringBuilder sb = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);

            try {
                ChatCompletionRequest request = ChatCompletionRequest.builder()
                        .model("deepseek-chat")
                        .messages(messages)
                        .stream(true)
                        .streamOptions(StreamOptions.builder().includeUsage(true).build())
                        .build();

                System.out.println("开始流式响应...\n");
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
                            System.err.println("\n流式错误: " + error.getMessage());
                            latch.countDown();
                        },
                        () -> {
                            System.out.println("\n\n流式响应完成！");
                            latch.countDown();
                        }
                );

                latch.await();
            } catch (Exception ex) {
                System.out.println("调用 DeepSeek API 失败: " + ex.getMessage());
                ex.printStackTrace();
            } finally {
                deepseekClient.shutdown();
            }
            responseText = sb.toString();

        } else {
            DeepSeekClient deepseekClient = new DeepSeekClient.Builder()
                    .openAiApiKey(AppConfig.getInstance().getDeepSeekApiKey())
                    .baseUrl("https://api.deepseek.com")
                    .model("deepseek-chat")
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .readTimeout(java.time.Duration.ofSeconds(600))
                    .logRequests(true)
                    .logResponses(true)
                    .build();

            try {
                ChatCompletionRequest request = ChatCompletionRequest.builder()
                        .messages(messages).build();

                ChatCompletionResponse response = deepseekClient
                        .chatCompletion(new OpenAiClientContext(), request)
                        .execute();

                if (response != null && response.choices() != null && !response.choices().isEmpty()) {
                    responseText = response.choices().get(0).message().content();
                } else {
                    System.out.println("未收到有效响应");
                }
            } catch (Exception ex) {
                System.out.println("调用 DeepSeek API 失败: " + ex.getMessage());
                ex.printStackTrace();
            } finally {
                deepseekClient.shutdown();
            }
        }

        return responseText;
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
        String responseText = "";
        try (Client client = Client.builder().apiKey(AppConfig.getInstance().getGeminiApiKey()).build()) {
            GenerateContentResponse response = client.models.generateContent("gemini-3-flash-preview", prompt, null);
            responseText = response.text();
        } catch (Exception e) {
            System.out.println("Gemini Chat Error: " + e.getMessage());
            e.printStackTrace();
        }
        return responseText;
    }

    public static String generateSummaryWithGemini(java.io.File pdfFile, String summaryPrompt) {
        String responseText = "";
        try {
            Client client = Client.builder()
                    .apiKey(AppConfig.getInstance().getGeminiApiKey())
                    .build();

            File uploadedFile = client.files.upload(
                    pdfFile.getAbsolutePath(),
                    UploadFileConfig.builder()
                            .mimeType("application/pdf")
                            .build()
            );

            System.out.println("文件上传成功: " + uploadedFile.uri().get());

            Content content = Content.fromParts(
                    Part.fromText(summaryPrompt),
                    Part.fromUri(uploadedFile.uri().get(), uploadedFile.mimeType().get())
            );

            GenerateContentResponse response = client.models.generateContent(
                    "gemini-3-flash-preview",
                    content,
                    null);

            responseText = response.text();
        } catch (Exception ex) {
            System.out.println("调用 Gemini API 失败: " + ex.getMessage());
            ex.printStackTrace();
        }
        return responseText;
    }

    public static String analyzeImageWithGemini(java.io.File imageFile, String prompt) {
        String responseText = "";
        try {
            Client client = Client.builder()
                    .apiKey(AppConfig.getInstance().getGeminiApiKey())
                    .build();

            File uploadedFile = client.files.upload(
                    imageFile.getAbsolutePath(),
                    UploadFileConfig.builder()
                            .mimeType("image/png")
                            .build()
            );

            System.out.println("图片上传成功: " + uploadedFile.uri().get());

            Content content = Content.fromParts(
                    Part.fromText(prompt),
                    Part.fromUri(uploadedFile.uri().get(), uploadedFile.mimeType().get())
            );

            GenerateContentResponse response = client.models.generateContent(
                    "gemini-2.0-flash-exp",
                    content,
                    null);

            responseText = response.text();
        } catch (Exception ex) {
            System.out.println("调用 Gemini Vision API 失败: " + ex.getMessage());
            ex.printStackTrace();
        }
        return responseText;
    }

    public static String analyzeImagesWithGemini(java.io.File[] imageFiles, String prompt) {
        String responseText = "";
        try {
            Client client = Client.builder()
                    .apiKey(AppConfig.getInstance().getGeminiApiKey())
                    .build();
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
                    client.models.generateContent("gemini-2.0-flash-exp", content, null);
            responseText = response.text();
        } catch (Exception ex) {
            System.out.println("调用 Gemini Vision API 失败: " + ex.getMessage());
            ex.printStackTrace();
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
                    "gemini-3-pro-image-preview",
                    content,
                    config);

            for (Part part : response.parts()) {
                if (part.text().isPresent()) {
                    System.out.println(part.text().get());
                } else if (part.inlineData().isPresent()) {
                    try {
                        var blob = part.inlineData().get();
                        if (blob.data().isPresent()) {
                            Path outputDirPath = Paths.get(outputDirectory);
                            if (!Files.exists(outputDirPath)) {
                                Files.createDirectories(outputDirPath);
                                System.out.println("创建输出目录: " + outputDirectory);
                            }

                            String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                            String originalFileName = Paths.get(fileString).getFileName().toString().replaceFirst("\\.[^.]+$", "");
                            String imageFileName = String.format("%s_%s_generated_image.png", originalFileName, timestamp);

                            Path imageFilePath = outputDirPath.resolve(imageFileName);
                            Files.write(imageFilePath, blob.data().get());
                            System.out.println("图片生成成功: " + imageFilePath);
                        }
                    } catch (IOException ex) {
                        System.out.println("写入图片文件失败: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
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
             System.err.println("Ollama Chat Error: " + e.getMessage());
             e.printStackTrace();
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
             System.err.println("Ollama Streaming Chat Error: " + e.getMessage());
             e.printStackTrace();
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
                        System.err.println("Failed to read image: " + src + ", error: " + e.getMessage());
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
             System.err.println("Ollama Image Chat Error: " + e.getMessage());
             e.printStackTrace();
             return null;
        }
    }

    // --- Minimax ---

    public static String chatWithMinimax(String prompt) {
        String apiKey = AppConfig.getInstance().getMinimaxApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.err.println("Minimax API Key is missing!");
            return "";
        }

        try {
            java.util.Map<String, Object> message = new java.util.HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);

            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("model", "MiniMax-M2.1");
            payload.put("messages", java.util.List.of(message));
            payload.put("stream", false);

            java.util.Map<String, Object> extraBody = new java.util.HashMap<>();
            extraBody.put("reasoning_split", Boolean.TRUE);
            payload.put("extra_body", extraBody);

            String jsonBody = com.alibaba.fastjson2.JSON.toJSONString(payload);

            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.minimaxi.com/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                com.alibaba.fastjson2.JSONObject jsonResponse = com.alibaba.fastjson2.JSON.parseObject(response.body());
                if (jsonResponse.containsKey("choices")) {
                    String content = jsonResponse.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content");
                    if (content != null) {
                        content = content.replaceAll("(?s)<think>.*?</think>", "").trim();
                    }
                    return content;
                }
            } else {
                System.err.println("Minimax API Error: " + response.statusCode() + " - " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Minimax Chat Exception: " + e.getMessage());
            e.printStackTrace();
        }
        return "";
    }

    // --- Zhipu GLM ---

    public static String chatWithGLM(String prompt) {
        String apiKey = AppConfig.getInstance().getGlmApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.err.println("GLM API Key is missing!");
            return "";
        }

        try {
            String thinkingRaw = AppConfig.getInstance().getGlmThinking();
            String thinkingNorm = thinkingRaw == null ? "" : thinkingRaw.trim().toLowerCase();
            String thinkingType;
            if (thinkingNorm.isEmpty()) {
                thinkingType = "disabled";
            } else if ("enabled".equals(thinkingNorm) || "enable".equals(thinkingNorm) || "true".equals(thinkingNorm) || "1".equals(thinkingNorm) || "on".equals(thinkingNorm)) {
                thinkingType = "enabled";
            } else if ("disabled".equals(thinkingNorm) || "disable".equals(thinkingNorm) || "false".equals(thinkingNorm) || "0".equals(thinkingNorm) || "off".equals(thinkingNorm)) {
                thinkingType = "disabled";
            } else {
                thinkingType = "disabled";
            }
            java.util.Map<String, Object> message = new java.util.HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);

            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("model", "glm-4.6");
            payload.put("messages", java.util.List.of(message));
            payload.put("stream", false);
            java.util.Map<String, Object> thinking = new java.util.HashMap<>();
            thinking.put("type", thinkingType);
            payload.put("thinking", thinking);

            String jsonBody = com.alibaba.fastjson2.JSON.toJSONString(payload);

            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://open.bigmodel.cn/api/paas/v4/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                com.alibaba.fastjson2.JSONObject jsonResponse = com.alibaba.fastjson2.JSON.parseObject(response.body());
                if (jsonResponse.containsKey("choices")) {
                    com.alibaba.fastjson2.JSONArray choices = jsonResponse.getJSONArray("choices");
                    if (choices != null && !choices.isEmpty()) {
                        String content = choices.getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content");
                        if (content != null) {
                            content = content.replaceAll("(?s)<think>.*?</think>", "").trim();
                        }
                        return content;
                    }
                }
            } else {
                System.err.println("GLM API Error: " + response.statusCode() + " - " + response.body());
            }
        } catch (Exception e) {
            System.err.println("GLM Chat Exception: " + e.getMessage());
            e.printStackTrace();
        }
        return "";
    }
}
