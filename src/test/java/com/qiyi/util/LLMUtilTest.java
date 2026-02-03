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
        System.out.println("=== Testing analyzeImageWithAliyun ===");
        String imagePath = "/Users/cenwenchu/Desktop/1.png";
        String prompt = "请你提取一下图片里面的页面布局和元素信息，方便大模型理解这个界面的结构和元素，保障对于筛选项和操作按钮的准确和完整，不用给建议，只需要称述实际存在的元素内容，在保障完整性的同时，尽量减少字符数";
        long startTime = System.currentTimeMillis();
        String response = LLMUtil.analyzeImageWithAliyun(new File(imagePath), prompt);
        long endTime = System.currentTimeMillis();
        System.out.println("Execution time: " + (endTime - startTime) + "ms");
        System.out.println("Response: " + response);
        if (response.isEmpty()) {
            System.err.println("Response is empty. Make sure Ollama is running and model '" + VL_MODEL_NAME + "' is available.");
        }
    }

    @Test
    public void testChatWithOllamaTranslation() {
        Assumptions.assumeTrue(shouldRunOllamaTests());
        System.out.println("=== Testing chatWithOllamaTranslation ===");
        String sourceText = "你好，我很高兴到杭州，希望我们有个美好的假期";
        String prompt = "Translate the following segment into <" + HunyuanLanguage.JAPANESE.getCode() + ">, without additional explanation. \n\n " + sourceText;
        
        long startTime = System.currentTimeMillis();
        String response = LLMUtil.chatWithOllama(prompt, TRANSLATION_MODEL_NAME, null, false, resolveOllamaHost());
        long endTime = System.currentTimeMillis();
        System.out.println("Execution time: " + (endTime - startTime) + "ms");
        System.out.println("Response: " + response);
        if (response.isEmpty()) {
            System.err.println("Response is empty. Make sure Ollama is running and model '" + TRANSLATION_MODEL_NAME + "' is available.");
        }
    }

    @Test
    public void testChatWithOllama() {
        Assumptions.assumeTrue(shouldRunOllamaTests());
        System.out.println("=== Testing chatWithOllama ===");
        String prompt = "请你把如下的句子里面提炼出核心的时间，地点，人物，事情：明天小王要去上海参加国际绘画展";
        // Assuming the third argument is chatHistory, passing null or empty string
        long startTime = System.currentTimeMillis();
        String response = LLMUtil.chatWithOllama(prompt, MODEL_NAME, null,false, resolveOllamaHost());
        long endTime = System.currentTimeMillis();
        System.out.println("Execution time: " + (endTime - startTime) + "ms");
        System.out.println("No Thinking Response: " + response);    

        
        if (response.isEmpty()) {
            System.err.println("Response is empty. Make sure Ollama is running and model '" + MODEL_NAME + "' is available.");
        }

        startTime = System.currentTimeMillis();
        response = LLMUtil.chatWithOllama(prompt, MODEL_NAME, null,true, resolveOllamaHost());
        endTime = System.currentTimeMillis();
        System.out.println("Execution time: " + (endTime - startTime) + "ms");
        System.out.println("Thinking Response: " + response);
        if (response.isEmpty()) {
            System.err.println("Response is empty. Make sure Ollama is running and model '" + MODEL_NAME + "' is available.");
        }
    }

    @Test
    public void testChatWithOllamaStreaming() {
        Assumptions.assumeTrue(shouldRunOllamaTests());
        System.out.println("=== Testing chatWithOllamaStreaming ===");
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
                    e.printStackTrace();
                }
            }
            long endTime = System.currentTimeMillis();
            System.out.println("Execution time: " + (endTime - startTime) + "ms");
            System.out.println("\nFull Response from handler: " + contentBuilder.toString());
                                    
        } else {
            System.out.println("Response is null");
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
                    e.printStackTrace();
                }
            }
            long endTime = System.currentTimeMillis();
            System.out.println("Execution time: " + (endTime - startTime) + "ms");
            System.out.println("\nFull Response from handler: " + contentBuilder.toString());
            System.out.println("\nFull ThinkResponse from handler: " + thiinkContentBuilder.toString());
                                    
        } else {
            System.out.println("Think Response is null");
        }
        
    }


    @Test
    public void testChatWithOllamaImage() {
        Assumptions.assumeTrue(shouldRunOllamaTests());
        System.out.println("=== Testing chatWithOllamaImage ===");
        String prompt = "抽取一下信息";
        // Example image URL (Placeholder)
        String imageUrl = "/Users/cenwenchu/Desktop/2.png";
        
        List<String> images = Arrays.asList(imageUrl);
        
        // Note: Requires a vision-capable model like qwen3-vl:8b
        long startTime = System.currentTimeMillis();
        OllamaChatResult response = LLMUtil.chatWithOllamaImage(prompt, VL_MODEL_NAME, null, false, images, resolveOllamaHost());
        long endTime = System.currentTimeMillis();
        System.out.println("Execution time: " + (endTime - startTime) + "ms");
        
         if (response != null) {
             System.out.println("HTTP Status: " + response.getHttpStatusCode());
             if (response.getResponseModel() != null) {
                 
                 if (response.getResponseModel().getMessage() != null) {
                    System.out.println(VL_MODEL_NAME + " Response Content: " + response.getResponseModel().getMessage().getContent());
                 } else {
                    System.err.println("Message is null");
                 }
             } else {
                 System.err.println("ResponseModel is null");
             }
        }
        else
        {
             System.err.println("Response object is null. Make sure Ollama is running and model '" + VL_MODEL_NAME + "' is available.");
        }
    }
}
