package com.qiyi.tools.android;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.android.AndroidDeviceManager;
import com.qiyi.android.BaseMobileRPAProcessor;
import com.qiyi.tools.Tool;
import com.qiyi.util.DingTalkUtil;
import com.qiyi.util.LLMUtil;

import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class TaobaoAppiumTool extends BaseMobileRPAProcessor implements Tool {
    
    private static Logger logger = LogManager.getLogger(TaobaoAppiumTool.class);

    public static void main(String[] aStrings)
    {
        TaobaoAppiumTool tool = new TaobaoAppiumTool();
        tool.execute(null, null, null);
    }
    
    public TaobaoAppiumTool() {
        this.setAppPackage("com.taobao.taobao");
        this.setAppActivity("com.taobao.tao.welcome.Welcome");
    }

    @Override
    public String getName() {
        // Use the same name as the old tool to replace it seamlessly
        return "taobao_price_check";
    }

    @Override
    public String getDescription() {
        return "打开淘宝应用，点击闪购，搜索指定商品，并截图分析最低价格（Appium 版）。参数：product_name (String, 必填) - 要搜索的商品名称。";
    }
    
    @Override
    public void initDriver(String udid, String appiumServerUrl)
            throws MalformedURLException {

        HashMap<String, Object> uiAutomator2Options = new HashMap<>();

        // 优化 UiAutomator2 性能
        uiAutomator2Options.put("mjpegServerPort", 7810);
        uiAutomator2Options.put("mjpegScreenshotUrl", "http://127.0.0.1:7810");
        uiAutomator2Options.put("skipDeviceInitialization", true);
        uiAutomator2Options.put("autoGrantPermissions", true);
        uiAutomator2Options.put("disableWindowAnimation", true);
        uiAutomator2Options.put("disableSuppressAccessibilityService", true);

        // 华为需要禁用部分检查
        uiAutomator2Options.put("disableWindowAnimation", true);
        uiAutomator2Options.put("disableSuppressAccessibilityService", true);
        
        // Override to add specific options for Taobao
        UiAutomator2Options options = new UiAutomator2Options()
                .setUdid(udid)
                .setNoReset(true)
                .setLocaleScript("zh-Hans-CN") 
                .setNewCommandTimeout(Duration.ofMinutes(3))
                .setAdbExecTimeout(Duration.ofSeconds(120))
                .setAppPackage(appPackage)
                .setAppActivity(appActivity);

        options.setCapability("uiautomator2Options", uiAutomator2Options);
        
        driver = new AndroidDriver(
                new URL(appiumServerUrl), options);
        driver.setSetting("allowInvisibleElements", true);
        // driver.activateApp(appPackage); // Welcome activity handles startup
    }

    @Override
    public String execute(JSONObject params, String senderId, List<String> atUserIds) {
        String serial = null;
        List<String> users = new ArrayList<>();
        if (senderId != null) users.add(senderId);

        try {
            serial = getDeviceSerial(params);
            
            // 1. Prepare Product Name
            String productName = null;
            if (params != null) {
                productName = params.getString("product_name");
            }
            else
                productName = "瑞幸咖啡";

            if (productName == null || productName.trim().isEmpty()) {
                return reportError(users, "未指定商品名称 (product_name)。请在指令中明确指定要搜索的商品。");
            }

            DingTalkUtil.sendTextMessageToEmployees(users, "正在初始化 Appium Driver (目标设备: " + serial + ")...");

            // 2. Init Driver
            try {
                this.initDriver(serial, "http://127.0.0.1:4723");
            } catch (Exception e) {
                 e.printStackTrace();
                 return reportError(users, "Appium 连接失败: " + e.getMessage());
            }

            DingTalkUtil.sendTextMessageToEmployees(users, "Appium 连接成功，正在搜索: " + productName);

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

            // 3. Click "闪购" (Flash Sale)
            try {
                System.out.println("Looking for '闪购'...");

                findElementAndWaitToClick("//android.widget.TextView[contains(@content-desc,'闪购')]",5);
                          
                System.out.println("Clicked '闪购'");
            } catch (Exception e) {
                System.out.println("Warning: '闪购' not found, proceeding to search directly...");
            }

            // 4. Click Search Entrance
            // 使用 BaseMobileRPAProcessor 的方法
            try {
                 String searchEntranceXpath = "//android.widget.FrameLayout[@resource-id=\"com.taobao.taobao:id/search_view\"]/android.widget.FrameLayout/android.widget.LinearLayout/android.widget.FrameLayout/";
                 findElementAndWaitToClick(searchEntranceXpath, 5);
                 System.out.println("Found Search Entrance, clicking...");
            } catch (Exception e) {
                 System.out.println("Search entrance not found, checking for EditText directly...");
            }

            // 5. Input Text
            try {
                WebElement inputField = findElementAndWait("android.widget.EditText", 5); // 这里的xpath其实是classname，但在 findElementAndWait 中是用 AppiumBy.xpath
                // Wait, BaseMobileRPAProcessor.findElementAndWait uses AppiumBy.xpath(xpath). 
                // So "android.widget.EditText" is NOT a valid xpath. It should be "//android.widget.EditText"
                
                // Correcting xpath
                inputField = findElementAndWaitToClick("//android.widget.EditText", 5);
                System.out.println("Found Input Field, sending text: " + productName);
                inputField.sendKeys(productName);
                Thread.sleep(1000);
            } catch (Exception e) {
                 // Try class name if xpath fails? BaseMobileRPAProcessor only supports xpath.
                 // Fallback to driver.findElement
                 try {
                     WebElement inputField = driver.findElement(By.className("android.widget.EditText"));
                     inputField.click();
                     inputField.sendKeys(productName);
                 } catch (Exception ex) {
                     return reportError(users, "未找到输入框 (EditText)");
                 }
            }

            // 6. Click Search Button
            try {
                findElementAndWaitToClick("//*[@text='搜索' or @content-desc='搜索']", 5);
                System.out.println("Found Search Button, clicking...");
            } catch (Exception e) {
                 return reportError(users, "未找到搜索按钮");
            }

            // 7. Wait for results and Screenshot
            Thread.sleep(5000); 
            DingTalkUtil.sendTextMessageToEmployees(users, "搜索完成，正在截图分析...");

            File screenshot1 = driver.getScreenshotAs(OutputType.FILE);
            File localPng1 = new File("taobao_price_1_" + System.currentTimeMillis() + ".png");
            FileUtils.copyFile(screenshot1, localPng1);

            // Scroll down (Swipe Up)
            // Use BaseMobileRPAProcessor scroll
            this.scroll(0.6, Direction.UP, 0.8);
            Thread.sleep(1200);

            File screenshot2 = driver.getScreenshotAs(OutputType.FILE);
            File localPng2 = new File("taobao_price_2_" + System.currentTimeMillis() + ".png");
            FileUtils.copyFile(screenshot2, localPng2);

            // 8. Analyze with Gemini
            String prompt = "请综合分析两张淘宝搜索结果截图，找到所有显示的商品中，价格最低的商品（排除广告）。返回该商品的价格和名称。";
            File[] imgs = new File[]{localPng1, localPng2};
            
            String analysisResult = LLMUtil.analyzeImagesWithGemini(imgs, prompt);
            
            String finalResult = "分析结果 (Appium Refactored):\n" + analysisResult;
            DingTalkUtil.sendTextMessageToEmployees(users, finalResult);
            
            // Clean up
            if (localPng1.exists()) localPng1.delete();
            if (localPng2.exists()) localPng2.delete();
            
            return finalResult;

        } catch (Throwable e) {
            e.printStackTrace();
            return reportError(users, "Appium 执行异常 (Throwable): " + e.getMessage());
        } finally {
            this.quitDriver();
        }
    }

    private String getDeviceSerial(JSONObject params) throws Exception {
        String serial = null;
        
        if (params != null)
            serial = params.getString("serial");
        
        if (serial != null && !serial.isEmpty()) {
            return serial;
        }
        
        // If no serial provided, get the first connected device
        List<String> devices = AndroidDeviceManager.getInstance().getDevices();
        if (devices.isEmpty()) {
            throw new Exception("No Android devices connected.");
        }
        return devices.get(0);
    }

    private String reportError(List<String> users, String msg) {
        System.err.println(msg);
        try {
            DingTalkUtil.sendTextMessageToEmployees(users, "Error: " + msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return msg;
    }
}
