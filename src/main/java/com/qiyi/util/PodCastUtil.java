package com.qiyi.util;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Files;
import com.fasterxml.jackson.core.type.TypeReference;
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
import com.qiyi.podcast.PodCastItem;
import com.qiyi.wechat.WechatArticle;

import io.github.pigmesh.ai.deepseek.core.DeepSeekClient;
import io.github.pigmesh.ai.deepseek.core.OpenAiClient.OpenAiClientContext;
import io.github.pigmesh.ai.deepseek.core.chat.ChatCompletionRequest;
import io.github.pigmesh.ai.deepseek.core.chat.ChatCompletionResponse;
import io.github.pigmesh.ai.deepseek.core.chat.Message;
import io.github.pigmesh.ai.deepseek.core.chat.UserMessage;
import io.github.pigmesh.ai.deepseek.core.shared.StreamOptions;
import reactor.core.publisher.Flux;

public class PodCastUtil {

    static String GEMINI_API_KEY = "";
    static String DEEPSEEK_API_KEY = "";

    /**
     * 获取 Chrome 浏览器的 WebSocket 调试端点 URL
     * 
     * @param port 调试端口号 (通常为 9222)
     * @return WebSocket URL，如果获取失败返回 null
     */
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
    
    /**
     * 检查当前页面是否已登录
     * 
     * @param page Playwright 页面对象
     * @return 已登录返回 true，否则返回 false
     */
    public static boolean isLoggedIn(Page page) {
        try {
            // 检查是否有登录状态的元素
            ElementHandle userElement = page.querySelector("//div/span[contains(text(),'Sign Out')]");
            return userElement != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isWechatLoggedIn(Page page) {
        try {
            //尝试点击一下登录按钮，看是否会有弹窗
            // Fix: 先检查是否存在，避免抛出异常导致直接返回 false
            if (page.isVisible("//a[contains(text(),'登录')]")) {
                page.click("//a[contains(text(),'登录')]");
                //等待页面加载完毕
                page.waitForLoadState(LoadState.NETWORKIDLE);
            }

            // 检查是否有登录状态的元素
            ElementHandle userElement = page.querySelector("//div/span[contains(@class,'acount_box-nickname')]");
            return userElement != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 暂停程序等待用户在浏览器中手动完成登录
     * 
     * @param page Playwright 页面对象
     */
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

    /**
     * 杀死占用指定端口的 Chrome 进程
     * 
     * @param port 端口号 (通常为 9222)
     */
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

    /**
     * 启动带有远程调试端口的 Chrome 浏览器
     * 
     * @throws IOException IO异常
     * @throws InterruptedException 中断异常
     */
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

    /**
     * 等待页面高度稳定（用于处理无限滚动加载）
     * 
     * @param page Playwright 页面对象
     * @param maxSeconds 最大等待时间（秒）
     * @throws InterruptedException 中断异常
     */
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


    /**
     * 使用 Gemini 模型生成图片
     * 
     * @param fileString 输入文件路径（摘要文本）
     * @param outputDirectory 图片输出目录
     * @param imagePrompt 生成图片的提示词
     */
    public static void generateImageWithGemini(String fileString, String outputDirectory,String imagePrompt) {

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
                    Part.fromText(imagePrompt), // 文本部分
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


    


    /**
     * 使用 DeepSeek 模型生成文件内容
     * 
     * @param file 文件对象
     * @param summaryPrompt 摘要提示词
     * @param isStreamingProcess 是否使用流式输出
     * @return 生成的文本
     * @throws IOException IO异常
     */
    public static String generateContentWithDeepSeekByFile(java.io.File file,String summaryPrompt,boolean isStreamingProcess) throws IOException 
    {
        String content = readFileContent(file);

        UserMessage userMessage = UserMessage.builder()
                    .addText(summaryPrompt)
                    .addText(content).build();

        List<Message> messages = new ArrayList<>();
        messages.add(userMessage);

        return chatWithDeepSeek(messages, isStreamingProcess);
    }

    /**
     * 与 DeepSeek 模型进行多轮对话
     * 
     * @param messages 消息历史列表
     * @param isStreamingProcess 是否使用流式输出
     * @return 模型回复的文本
     */
    public static String chatWithDeepSeek(List<Message> messages, boolean isStreamingProcess) {
        String responseText = "";

        initClientConfig();

        if (isStreamingProcess)
        {
            DeepSeekClient deepseekClient = new DeepSeekClient.Builder()
            .openAiApiKey(DEEPSEEK_API_KEY)
            .baseUrl("https://api.deepseek.com")
            .model("deepseek-chat")
            .logStreamingResponses(true)                        // 开启响应日志
            .build();

            StringBuilder sb = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);

            try
            {
                ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .model("deepseek-chat")
                    .messages(messages)
                    .stream(true)  // 启用流式
                    .streamOptions(StreamOptions.builder().includeUsage(true).build())
                    .build();
                
        
                System.out.println("开始流式响应...\n");
                Flux<ChatCompletionResponse> flux = deepseekClient.chatFluxCompletion(request);

                flux.subscribe(
                    chunk -> {
                        // 处理每个流式块
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
            }
            catch (Exception ex) {
                System.out.println("调用 DeepSeek API 失败: " + ex.getMessage());
                ex.printStackTrace();
            }
            finally {
                deepseekClient.shutdown();
            }

            responseText = sb.toString();

        }
        else
        {
            DeepSeekClient deepseekClient = new DeepSeekClient.Builder()
            .openAiApiKey(DEEPSEEK_API_KEY)
            .baseUrl("https://api.deepseek.com")
            .model("deepseek-chat")
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .readTimeout(java.time.Duration.ofSeconds(600))
            .logRequests(true)                          // 开启请求日志
            .logResponses(true)                         // 开启响应日志
            .build();

            try
            {
                ChatCompletionRequest request = ChatCompletionRequest.builder()
                        .messages(messages).build();

                ChatCompletionResponse response = deepseekClient
                    .chatCompletion(new OpenAiClientContext(), request)
                    .execute();

                // 4. 处理响应
                if (response != null && response.choices() != null && !response.choices().isEmpty()) {
                    responseText = response.choices().get(0).message().content();
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
        }
        
        return responseText;
    }

    /**
     * 读取PDF文件内容
     * 
     * @param file PDF文件对象
     * @return 提取的文本内容
     * @throws IOException IO异常
     */
    public static String readFileContent(java.io.File file) throws IOException {

        //判断如果是pdf用这种方式，如果是txt文件，就用其他方式读取
        if (file.getName().toLowerCase().endsWith(".pdf")) {
            try (PDDocument document = Loader.loadPDF(file)) {
                // 2. 创建PDF文本提取器
                PDFTextStripper stripper = new PDFTextStripper();
                
                // 3. 提取文本
                return stripper.getText(document);
            }
        }
        else {
            // 读取普通文本文件
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                return content.toString();
            }
        }
    }

    /**
     * 使用 Gemini 模型生成摘要
     * 
     * @param pdfFile PDF文件对象
     * @param summaryPrompt 摘要提示词
     * @return 生成的摘要文本
     */
    public static String generateSummaryWithGemini(java.io.File pdfFile,String summaryPrompt) 
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
                Part.fromText(summaryPrompt), // 文本部分
                Part.fromUri(uploadedFile.uri().get(), uploadedFile.mimeType().get()) // 文件部分
            );


            GenerateContentResponse response =
                client.models.generateContent(
            "gemini-3-flash-preview",
            content,
            null);

            responseText = response.text();
            
            //System.out.println(responseText);
        }
        catch (Exception ex) {
            System.out.println("调用 Gemini API 失败: " + ex.getMessage());
            ex.printStackTrace();
        }

        return responseText;
    }

    /**
     * 与 DeepSeek 模型进行简单对话（用于翻译等）
     * 
     * @param prompt 提示词
     * @return 模型回复的文本
     */
    public static String chatWithDeepSeek(String prompt) {
        initClientConfig();
        String responseText = "";
        
        DeepSeekClient deepseekClient = new DeepSeekClient.Builder()
            .openAiApiKey(DEEPSEEK_API_KEY)
            .baseUrl("https://api.deepseek.com")
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

    /**
     * 与 Gemini 模型进行简单对话（用于翻译等）
     * 
     * @param prompt 提示词
     * @return 模型回复的文本
     */
    public static String chatWithGemini(String prompt) {
        initClientConfig();
        String responseText = "";
        try (Client client = Client.builder().apiKey(GEMINI_API_KEY).build()) {
            GenerateContentResponse response = client.models.generateContent("gemini-3-flash-preview", prompt, null);
            responseText = response.text();
        } catch (Exception e) {
            System.out.println("Gemini Chat Error: " + e.getMessage());
            e.printStackTrace();
        }
        return responseText;
    }

    /**
     * 从文件读取播客名称列表
     * 
     * @param filePath 文件路径
     * @return 播客名称数组
     */
    public static String[] readPodCastNamesFromFile(String filePath) {
        List<String> podCastNames = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                podCastNames.add(line.trim());
            }
        } catch (IOException e) {
            System.err.println("读取播客列表文件失败: " + e.getMessage());
            e.printStackTrace();
        }
        return podCastNames.toArray(new String[0]);
    }

    /**
     * 将播客条目列表写入文件 (JSON格式)
     * 
     * @param itemList 播客条目列表
     * @param filePath 输出文件路径
     */
    public static void writeItemListToFile(List<PodCastItem> itemList, String filePath) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            // Ensure directory exists
            java.io.File file = new java.io.File(filePath);
            java.io.File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // Write list to file with pretty printing
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, itemList);
            System.out.println("成功将 " + itemList.size() + " 个 PodcastItem 写入文件: " + filePath);
        } catch (IOException e) {
            System.err.println("写入文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 从文件读取播客条目列表 (JSON格式)
     * 
     * @param filePath 输入文件路径
     * @return 播客条目列表
     */
    public static List<PodCastItem> readItemListFromFile(String filePath) {
        ObjectMapper mapper = new ObjectMapper();
        List<PodCastItem> itemList = new ArrayList<>();
        java.io.File file = new java.io.File(filePath);

        if (!file.exists() || file.length() == 0) {
            return itemList;
        }

        try {
            // Read list from file
            itemList = mapper.readValue(file, new TypeReference<List<PodCastItem>>(){});
            System.out.println("成功从文件读取 " + itemList.size() + " 个 PodcastItem");
        } catch (IOException e) {
            System.err.println("读取文件失败: " + e.getMessage());
            e.printStackTrace();
        }
        return itemList;
    }


    /**
     * 从播客文件中提取微信公众号文章信息
     * 
     * @param podcastFilePath 播客文件路径
     * @return 微信公众号文章对象
     * @throws IOException 
     */
    public static WechatArticle generateWechatArticleFromDeepseek(String podcastFilePath) throws IOException {

        String promptString = "根据以下的播客摘要，帮忙生成一个适合微信公众号的文章，返回内容请分成四部分：文章标题，文章摘要（控制在100字以内），文章分类（从生活、健康、科学、财经、科技、商业、其他中选一个），文章完整内容;返回格式如下：文章标题:xxx\n" + //
                        "文章摘要:xxx\n" + //
                        "文章分类:xxx\n" + //
                        "文章完整内容:xxx\n，播客内容如下：:";

        
        String content = generateContentWithDeepSeekByFile(new java.io.File(podcastFilePath),promptString,true);


        //System.out.println(content);

        WechatArticle article =  parseFromString(content);
        article.setAuthor("Curcuma");

        article.setContent(
            new StringBuilder().append(article.getContent()).
            append("\n").append("-- 延伸阅读 --").append("\n")
                .append(readFileContent(new java.io.File(podcastFilePath)).replace("*", " ")
                .replace("**", "  ")).toString());

        

        return article;
    }

    public static WechatArticle parseFromString(String formattedText) {

        WechatArticle article = new WechatArticle();

        BufferedReader reader = new BufferedReader(new StringReader(formattedText));
        StringBuilder contentBuilder = new StringBuilder();
        boolean inContentSection = false;
        
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                if (line.startsWith("文章标题:")) {
                    article.setTitle(extractValue(line, "文章标题:"));
                } else if (line.startsWith("文章摘要:")) {
                    article.setSummary(extractValue(line, "文章摘要:"));
                } else if (line.startsWith("文章分类:")) {
                    article.setCategory(extractValue(line, "文章分类:"));
                } else if (line.startsWith("文章完整内容:")) {
                    // 内容部分开始
                    inContentSection = true;
                    // 可能有冒号后的内容
                    String afterColon = line.substring(line.indexOf(':') + 1).trim();
                    if (!afterColon.isEmpty()) {
                        contentBuilder.append(afterColon).append("\n");
                    }
                } else if (inContentSection) {
                    // 如果line有换行，则去掉换行
                    line = line.replace("\n", "");

                    //如果单行长度超过20个字符的，且开头不是两个空格
                    if (line.length() > 30 && !line.startsWith("  ")) {
                        line = "    " + line;
                    }
                    contentBuilder.append(line).append("\n");
                }
            }

            // 设置内容
            article.setContent(contentBuilder.toString().trim());

            article.setContent(article.getContent().replace("**", ""));
            article.setContent(article.getContent().replace("*", ""));
            
        } catch (Exception e) {
            System.err.println("解析文章时出错: " + e.getMessage());
            e.printStackTrace();
        }
        return article;
    }

    /**
     * 提取字段值
     * @param line 包含字段的行
     * @param prefix 字段前缀（如"文章标题:"）
     * @return 字段值
     */
    private static String extractValue(String line, String prefix) {
        if (line == null || prefix == null) {
            return "";
        }
        return line.substring(prefix.length()).trim();
    }

    /**
     * 验证解析结果
     * @param article 解析后的文章对象
     * @return 验证结果
     */
    public static boolean validateArticle(WechatArticle article) {
        if (article == null) {
            return false;
        }
        
        if (article.getTitle() == null || article.getTitle().isEmpty()) {
            System.err.println("文章标题为空");
            return false;
        }
        
        if (article.getSummary() == null || article.getSummary().isEmpty()) {
            System.err.println("文章摘要为空");
            return false;
        }
        
        if (article.getCategory() == null || article.getCategory().isEmpty()) {
            System.err.println("文章分类为空");
            return false;
        }
        
        if (article.getContent() == null || article.getContent().isEmpty()) {
            System.err.println("文章内容为空");
            return false;
        }
        
        // 验证摘要长度
        if (article.getSummary().length() > 200) {
            System.err.println("文章摘要超过限制: " + article.getSummary().length() + "字");
            return false;
        }
        
        return true;
    }

}