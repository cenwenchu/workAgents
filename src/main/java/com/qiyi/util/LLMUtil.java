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

public class LLMUtil {

    public enum ModelType {
        ALL,
        DEEPSEEK,
        GEMINI,
        ALIYUN,
        ALIYUN_VL
    }

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
                    .model("qwen-max") // 使用通义千问-Max
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

    /**
     * 使用阿里云 Qwen-VL 模型分析图片
     *
     * @param imageFile 图片文件
     * @param prompt 提示词
     * @return 模型回复
     */
    public static String analyzeImageWithAliyun(java.io.File imageFile, String prompt) {
        try {
            // Upload to OSS first as Aliyun VL models require URL
            String imageUrl = OSSUtil.uploadFile(imageFile);
            if (imageUrl == null) {
                System.err.println("Failed to upload image to OSS for Aliyun analysis.");
                return "Error: Image upload failed.";
            }

            MultiModalConversation conv = new MultiModalConversation();
            
            Map<String, Object> imageContent = Collections.singletonMap("image", imageUrl);
            Map<String, Object> textContent = Collections.singletonMap("text", prompt);
            
            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Arrays.asList(imageContent, textContent))
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
                .connectTimeout(java.time.Duration.ofSeconds(120))
                .readTimeout(java.time.Duration.ofSeconds(600))
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
}
