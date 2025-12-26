package com.qiyi.podcast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Files;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.File;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.UploadFileConfig;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

import io.github.pigmesh.ai.deepseek.core.DeepSeekClient;
import io.github.pigmesh.ai.deepseek.core.OpenAiClient.OpenAiClientContext;
import io.github.pigmesh.ai.deepseek.core.chat.ChatCompletionRequest;
import io.github.pigmesh.ai.deepseek.core.chat.ChatCompletionResponse;
import io.github.pigmesh.ai.deepseek.core.chat.UserMessage;

public class PodCastUtil {

    static String GEMINI_API_KEY = "";
    static String DEEPSEEK_API_KEY = "";


    public static String getChromeWsEndpoint(int port) {
        try {
            URL url = new URL("http://localhost:" + port + "/json/version");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String inputLine;
            
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(response.toString());
            return node.get("webSocketDebuggerUrl").asText();
            
        } catch (Exception e) {
            return null;
        }
    }
    
    public static boolean isLoggedIn(Page page) {
        try {
            // 检查是否有登录状态的元素
            ElementHandle userElement = page.querySelector("//div/span[contains(text(),'Sign Out')]");
            return userElement != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static void waitForManualLogin(Page page) {
        System.out.println("请在浏览器中手动登录，登录后按 Enter 键继续...");
        
        try {
            // 等待用户手动操作
            System.in.read();
            
            // 等待页面稳定
            page.waitForLoadState(LoadState.NETWORKIDLE);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void killChromeProcess(int port) {
        //  先杀死占用 9222 端口的进程等待一段时间，确保进程已完全终止
        try {
            System.out.println("正在杀死占用 9222 端口的进程...");
            Process killProcess = Runtime.getRuntime().exec(new String[]{"bash", "-c", "lsof -ti:9222 | xargs kill -9"});
            killProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("已杀死占用 9222 端口的进程");
            
            Thread.sleep(1000);
        } catch (Exception ex) {
            // TODO Auto-generated catch block
            ex.printStackTrace();
        }
    }

    public static void startChromeBrowser() throws IOException, InterruptedException {
        // 启动 Chrome 浏览器
        System.out.println("正在启动 Chrome 浏览器...");
        String command = "nohup /Applications/Google\\ Chrome.app/Contents/MacOS/Google\\ Chrome --remote-debugging-port=9222 --user-data-dir=\"/tmp/chrome-debug\" > /tmp/chrome-debug.log 2>&1 &";
        Process chromeProcess = Runtime.getRuntime().exec(new String[]{"bash", "-c", command});
        chromeProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("Chrome 浏览器已启动");
        
        // 等待 Chrome 完全启动
        Thread.sleep(3000);
    }

        // 专门检测高度是否稳定
    public static void waitForHeightStabilized(Page page, int maxSeconds) throws InterruptedException {
        int stableCount = 0;
        int lastHeight = 0;
        long startTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - startTime < maxSeconds * 1000L) {
                Number currentHeight = (Number) page.evaluate("document.documentElement.scrollHeight");
            
                if (currentHeight.intValue() == lastHeight) {
                    stableCount++;
                    if (stableCount >= 10) { // 连续10次高度不变
                        //System.out.println("页面高度已稳定: " + currentHeight);
                        return;
                    }
                } else {
                    stableCount = 0;
                    lastHeight = currentHeight.intValue();
                    //System.out.println("检测到高度变化: " + currentHeight);
                }
            
                Thread.sleep(500); // 每.5秒检查一次
            }   
        
            System.out.println("等待超时，当前高度: " + 
                page.evaluate("document.documentElement.scrollHeight"));
        }   


    public static void initClientConfig() {
        if (GEMINI_API_KEY.equals("") || DEEPSEEK_API_KEY.equals(""))
        {
            Properties props = new Properties();
            try (InputStream input = PodCastUtil.class.getClassLoader().getResourceAsStream("podcast.cfg")) {
                if (input == null) {
                    System.out.println("配置文件 podcast.cfg 未找到");
                } else {
                    props.load(input);
                    System.out.println("配置文件加载成功");
                }
            } catch (IOException ex) {
                System.out.println("加载配置文件失败: " + ex.getMessage());
                ex.printStackTrace();
            }

            GEMINI_API_KEY = props.getProperty("GEMINI_API_KEY");
            DEEPSEEK_API_KEY = props.getProperty("deepseek.api-key");
        }
    }


    public static void generateImageWithGemini(String fileString, String outputDirectory) {

            initClientConfig();

            try (Client client =Client.builder().apiKey(GEMINI_API_KEY).build();) {

            GenerateContentConfig config = GenerateContentConfig.builder()
                .responseModalities(Arrays.asList("IMAGE"))
                .build();

            File uploadedFile = client.files.upload(
                fileString,
                UploadFileConfig.builder()
                    .mimeType("application/text") // 设置正确的 MIME 类型 (如 image/jpeg, application/pdf 等)
                    .build()
                );

                Content content = Content.fromParts(
                    Part.fromText("针对这份播客摘要，生成一张图片，图片中包含摘要中的核心知识点"), // 文本部分
                    Part.fromUri(uploadedFile.uri().get(), uploadedFile.mimeType().get()) // 文件部分
                );

                GenerateContentResponse response = client.models.generateContent(
                    "gemini-3-pro-image-preview",
                    content,
                    config);

                for (Part part : response.parts()) 
                {
                    if (part.text().isPresent()) {
                        System.out.println(part.text().get());
                    } 
                    else if (part.inlineData().isPresent()) {

                        try 
                        {
                            var blob = part.inlineData().get();
                            if (blob.data().isPresent()) {
                                // 确保输出目录存在
                                java.nio.file.Path outputDirPath = Paths.get(outputDirectory);
                                if (!Files.exists(outputDirPath)) {
                                    Files.createDirectories(outputDirPath);
                                    System.out.println("创建输出目录: " + outputDirectory);
                                }
                                
                                // 生成唯一的文件名
                                String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                                String originalFileName = Paths.get(fileString).getFileName().toString().replaceFirst("\\.[^.]+$", "");
                                String imageFileName = String.format("%s_%s_generated_image.png", originalFileName, timestamp);
                                
                                // 构建完整的文件路径
                                java.nio.file.Path imageFilePath = outputDirPath.resolve(imageFileName);
                                
                                // 写入图片文件
                                Files.write(imageFilePath, blob.data().get());
                                System.out.println("图片生成成功: " + imageFilePath);
                            }
                        }
                        catch (IOException ex) {
                            System.out.println("写入图片文件失败: " + ex.getMessage());
                            ex.printStackTrace();
                        }
                    }
                }
        }

    }


    public static String generateSummaryWithDeepSeek(java.io.File pdfFile) 
    {
        String responseText = "";

        initClientConfig();

        DeepSeekClient deepseekClient = new DeepSeekClient.Builder()
            .openAiApiKey(DEEPSEEK_API_KEY)
            .baseUrl("https://api.deepseek.com")
            .model("deepseek-chat")
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .readTimeout(java.time.Duration.ofSeconds(60))
            .logRequests(true)                          // 开启请求日志
            .logResponses(true)                         // 开启响应日志
            .build();

        try
        {

            String pdfContent = readFileContent(pdfFile);

            UserMessage userMessage = UserMessage.builder()
                        .addText("针对这个播客的内容，首先可以去掉很多寒暄，日常聊天，以及一些无关紧要的内容；然后根据对话，提炼出一些重点知识点，或者话题；"+
                            "最后根据这些知识点和话题，适当的补充一些专业词汇的介绍，生成一份中文摘要，直接输出摘要，不要有其他的回复信息。播客文字内容如下：")
                        .addText(pdfContent).build();

            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .messages(userMessage).build();

            ChatCompletionResponse response = deepseekClient
                .chatCompletion(new OpenAiClientContext(), request)
                .execute();

            // 4. 处理响应
            if (response != null && response.choices() != null && !response.choices().isEmpty()) {
                responseText = response.choices().get(0).message().content();
                System.out.println("\n=== DeepSeek 回答 ===");
                System.out.println(responseText);
                
                // 打印使用统计
                System.out.println("\n=== 使用统计 ===");
                System.out.println("Prompt tokens: " + response.usage().promptTokens());
                System.out.println("Completion tokens: " + response.usage().completionTokens());
                System.out.println("Total tokens: " + response.usage().totalTokens());
            }
            else {
                System.out.println("未收到有效响应");
            }
        }
        catch (Exception ex) {
            System.out.println("调用 DeepSeek API 失败: " + ex.getMessage());
            ex.printStackTrace();
        }
        finally {
            deepseekClient.shutdown();
        }
        
        return responseText;
    }

    public static String readFileContent(java.io.File file) throws IOException {

        try (PDDocument document = Loader.loadPDF(file)) {
            // 2. 创建PDF文本提取器
            PDFTextStripper stripper = new PDFTextStripper();
            
            // 3. 提取文本
            return stripper.getText(document);
        }
    }

    public static String generateSummaryWithGemini(java.io.File pdfFile) 
    {
        
        String responseText = "";

        try 
        {
            initClientConfig();

            Client client = Client.builder()
            .apiKey(GEMINI_API_KEY)
            .build();


            // 2. 上传本地文件 
            File uploadedFile = client.files.upload(
                pdfFile.getAbsolutePath(),
                UploadFileConfig.builder()
                    .mimeType("application/pdf") // 设置正确的 MIME 类型 (如 image/jpeg, application/pdf 等)
                    .build()
            );

            System.out.println("文件上传成功: " + uploadedFile.uri().get());

            // 3. 构建请求内容 (文本 + 文件)
            Content content = Content.fromParts(
                Part.fromText("针对这个播客的内容，首先可以去掉很多寒暄，日常聊天，以及一些无关紧要的内容；然后根据对话，提炼出一些重点知识点，或者话题；"+
                "最后根据这些知识点和话题，适当的补充一些专业词汇的介绍，生成一份中文摘要"), // 文本部分
                Part.fromUri(uploadedFile.uri().get(), uploadedFile.mimeType().get()) // 文件部分
            );


            GenerateContentResponse response =
                client.models.generateContent(
            "gemini-3-flash-preview",
            content,
            null);

            responseText = response.text();
            
            System.out.println(responseText);
        }
        catch (Exception ex) {
            System.out.println("调用 Gemini API 失败: " + ex.getMessage());
            ex.printStackTrace();
        }

        return responseText;
    }

}