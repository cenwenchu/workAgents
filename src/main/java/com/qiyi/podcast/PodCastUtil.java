package com.qiyi.podcast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.File;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.UploadFileConfig;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

public class PodCastUtil {

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


    public static String generateSummaryWithGemini(java.io.File pdfFile) 
    {
        String GEMINI_API_KEY = "";
        String responseText = "";

        try 
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
                Part.fromText("针对这个播客的内容，生成一份中文摘要，提炼核心的知识点，可以适当的拓展一些知识丰富摘要"), // 文本部分
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
