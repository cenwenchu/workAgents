package com.qiyi.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import io.github.ollama4j.models.chat.OllamaChatResult;
import io.github.ollama4j.models.generate.OllamaStreamHandler;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * LLMUtil 测试类
 * 注意：运行以下测试需要本地启动 Ollama 服务，并确保已拉取相应的模型。
 * 默认使用 qwen3:8b 模型。
 */
public class LLMUtilTest {

    private static final String MODEL_NAME = LLMUtil.OLLAMA_MODEL_QWEN3_8B;
    private static final String VL_MODEL_NAME = LLMUtil.OLLAMA_MODEL_QWEN3_VL_8B;
    private static final String TRANSLATION_MODEL_NAME = LLMUtil.OLLAMA_MODEL_HUNYUAN_MT;
    public static final String OLLAMA_HOST = "http://192.168.3.60:11434";
    private static final long STREAMING_MAX_WAIT_MS = 60000L;

    private static boolean shouldRunOllamaTests() {
        String v = System.getenv("RUN_OLLAMA_TESTS");
        if (v == null) return false;
        v = v.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
    }

    private static String resolveOllamaHost() {
        String v = System.getenv("OLLAMA_HOST");
        if (v == null) return OLLAMA_HOST;
        v = v.trim();
        return v.isEmpty() ? OLLAMA_HOST : v;
    }

    public static void main(String[] args)
    {
        LLMUtilTest  test = new LLMUtilTest();
        //test.testChatWithOllama();
        //test.testChatWithOllamaStreaming();
        //test.testChatWithOllamaImage();
        test.testAnalyzeImageWithAliyun();
    }

    public void testAnalyzeImageWithAliyun() {
        AppLog.info("=== Testing analyzeImageWithAliyun ===");
        String imagePath = "/Users/cenwenchu/Desktop/1.png";
        String userPrompt = "请帮我查询待发货所有的订单（包括多页的数据），并且输出订单的所有信息，输出格式为：\\\"列名:列内容（去掉回车换行）\\\"，然后用\\\"｜\\\"分隔，列的顺序保持表格的顺序，一条记录一行。输出以后，回到第一条订单，选中订单，然后点击审核推单，读取弹出页面的成功和失败的笔数，失败笔数大于0，页面上获取失败原因，也一起输出";
        String prompt = "针对图片的情况，把下面这段用户需求，分解为多个可执行的步骤，最终会用于大模型生成代码，只需要拆分出具体步骤。用户需求为：" + userPrompt;
        long startTime = System.currentTimeMillis();
        String response = LLMUtil.analyzeImageWithAliyun(new File(imagePath), prompt);
        long endTime = System.currentTimeMillis();
        AppLog.info("Execution time: " + (endTime - startTime) + "ms");
        AppLog.info("Response: " + response);
        if (response.isEmpty()) {
            AppLog.error("Response is empty. Make sure Ollama is running and model '" + VL_MODEL_NAME + "' is available.");
        }
    }

    @Test
    public void testChatWithOllamaTranslation() {
        Assumptions.assumeTrue(shouldRunOllamaTests());
        AppLog.info("=== Testing chatWithOllamaTranslation ===");
        String sourceText = "你好，我很高兴到杭州，希望我们有个美好的假期";
        String prompt = "Translate the following segment into <" + HunyuanLanguage.JAPANESE.getCode() + ">, without additional explanation. \n\n " + sourceText;
        
        long startTime = System.currentTimeMillis();
        String response = LLMUtil.chatWithOllama(prompt, TRANSLATION_MODEL_NAME, null, false, resolveOllamaHost());
        long endTime = System.currentTimeMillis();
        AppLog.info("Execution time: " + (endTime - startTime) + "ms");
        AppLog.info("Response: " + response);
        if (response.isEmpty()) {
            AppLog.error("Response is empty. Make sure Ollama is running and model '" + TRANSLATION_MODEL_NAME + "' is available.");
        }
    }

    @Test
    public void testChatWithOllama() {
        Assumptions.assumeTrue(shouldRunOllamaTests());
        AppLog.info("=== Testing chatWithOllama ===");
        String prompt = "请你把如下的句子里面提炼出核心的时间，地点，人物，事情：明天小王要去上海参加国际绘画展";
        // Assuming the third argument is chatHistory, passing null or empty string
        long startTime = System.currentTimeMillis();
        String response = LLMUtil.chatWithOllama(prompt, MODEL_NAME, null,false, resolveOllamaHost());
        long endTime = System.currentTimeMillis();
        AppLog.info("Execution time: " + (endTime - startTime) + "ms");
        AppLog.info("No Thinking Response: " + response);    

        
        if (response.isEmpty()) {
            AppLog.error("Response is empty. Make sure Ollama is running and model '" + MODEL_NAME + "' is available.");
        }

        startTime = System.currentTimeMillis();
        response = LLMUtil.chatWithOllama(prompt, MODEL_NAME, null,true, resolveOllamaHost());
        endTime = System.currentTimeMillis();
        AppLog.info("Execution time: " + (endTime - startTime) + "ms");
        AppLog.info("Thinking Response: " + response);
        if (response.isEmpty()) {
            AppLog.error("Response is empty. Make sure Ollama is running and model '" + MODEL_NAME + "' is available.");
        }
    }

    @Test
    public void testChatWithOllamaStreaming() {
        Assumptions.assumeTrue(shouldRunOllamaTests());
        AppLog.info("=== Testing chatWithOllamaStreaming ===");
        String prompt = "简单说一下中国长城的历史";
        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder thiinkContentBuilder = new StringBuilder();

        long startTime = System.currentTimeMillis();
        String host = resolveOllamaHost();
        OllamaChatResult response = LLMUtil.chatWithOllamaStreaming(prompt, MODEL_NAME,null,false,null, new OllamaStreamHandler() {
            @Override
            public void accept(String s) {
                //这个用于逐步输出，如果要一次性获得结果，可以直接使用最后的response里面的完整content
                contentBuilder.append(s);
            }
        }, host);

        if (response != null) {
            // Wait for completion if async
            long startWait = System.currentTimeMillis();
            while (response.getResponseModel() != null && !response.getResponseModel().isDone()) {
                if (System.currentTimeMillis() - startWait > STREAMING_MAX_WAIT_MS) {
                    throw new RuntimeException("Streaming timeout (no-thinking), host=" + host);
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    AppLog.error(e);
                }
            }
            long endTime = System.currentTimeMillis();
            AppLog.info("Execution time: " + (endTime - startTime) + "ms");
            AppLog.info("\nFull Response from handler: " + contentBuilder.toString());
                                    
        } else {
            AppLog.info("Response is null");
        }

        //contentBuilder 清理一下
        contentBuilder.setLength(0);

        startTime = System.currentTimeMillis();
        response = LLMUtil.chatWithOllamaStreaming(prompt, MODEL_NAME,null,true,new OllamaStreamHandler() {
            @Override
            public void accept(String s) {
                //这个用于逐步输出，如果要一次性获得结果，可以直接使用最后的response里面的完整content
                thiinkContentBuilder.append(s);
            }
        }, new OllamaStreamHandler() {
            @Override
            public void accept(String s) {
                //这个用于逐步输出，如果要一次性获得结果，可以直接使用最后的response里面的完整content
                contentBuilder.append(s);
            }
        }, host);

        if (response != null) {
            // Wait for completion if async
            long startWait = System.currentTimeMillis();
            while (response.getResponseModel() != null && !response.getResponseModel().isDone()) {
                if (System.currentTimeMillis() - startWait > STREAMING_MAX_WAIT_MS) {
                    throw new RuntimeException("Streaming timeout (thinking), host=" + host);
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    AppLog.error(e);
                }
            }
            long endTime = System.currentTimeMillis();
            AppLog.info("Execution time: " + (endTime - startTime) + "ms");
            AppLog.info("\nFull Response from handler: " + contentBuilder.toString());
            AppLog.info("\nFull ThinkResponse from handler: " + thiinkContentBuilder.toString());
                                    
        } else {
            AppLog.info("Think Response is null");
        }
        
    }


    @Test
    public void testChatWithOllamaImage() {
        Assumptions.assumeTrue(shouldRunOllamaTests());
        AppLog.info("=== Testing chatWithOllamaImage ===");
        String prompt = "抽取一下信息";
        // Example image URL (Placeholder)
        String imageUrl = "/Users/cenwenchu/Desktop/2.png";
        
        List<String> images = Arrays.asList(imageUrl);
        
        // Note: Requires a vision-capable model like qwen3-vl:8b
        long startTime = System.currentTimeMillis();
        OllamaChatResult response = LLMUtil.chatWithOllamaImage(prompt, VL_MODEL_NAME, null, false, images, resolveOllamaHost());
        long endTime = System.currentTimeMillis();
        AppLog.info("Execution time: " + (endTime - startTime) + "ms");
        
         if (response != null) {
             AppLog.info("HTTP Status: " + response.getHttpStatusCode());
             if (response.getResponseModel() != null) {
                 
                 if (response.getResponseModel().getMessage() != null) {
                    AppLog.info(VL_MODEL_NAME + " Response Content: " + response.getResponseModel().getMessage().getContent());
                 } else {
                    AppLog.error("Message is null");
                 }
             } else {
                 AppLog.error("ResponseModel is null");
             }
        }
        else
        {
             AppLog.error("Response object is null. Make sure Ollama is running and model '" + VL_MODEL_NAME + "' is available.");
        }
    }
}
