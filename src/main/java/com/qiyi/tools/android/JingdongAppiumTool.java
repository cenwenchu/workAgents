package com.qiyi.tools.android;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.android.AndroidDeviceManager;
import com.qiyi.android.BaseMobileRPAProcessor;
import com.qiyi.tools.Tool;
import com.qiyi.util.DingTalkUtil;
import com.qiyi.util.LLMUtil;

import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.android.nativekey.KeyEvent;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class JingdongAppiumTool extends BaseMobileRPAProcessor implements Tool {
    
    private static Logger logger = LogManager.getLogger(JingdongAppiumTool.class);

    public static void main(String[] aStrings)
    {
        JingdongAppiumTool tool = new JingdongAppiumTool();
        tool.execute(null, null, null);
    }
    
    public JingdongAppiumTool() {
        this.setAppPackage("com.jingdong.app.mall");
        this.setAppActivity("com.jingdong.app.mall.MainFrameActivity");
    }

    @Override
    public String getName() {
        // Use the same name as the old tool to replace it seamlessly
        return "jingdong_search_price";
    }

    @Override
    public String getDescription() {
        return "打开京东应用，点击秒送，搜索指定商品，搜索最低价格。参数：product_name (String, 必填) - 要搜索的商品名称。";
    }
    
    @Override
    public void initDriver(String udid, String appiumServerUrl)
            throws MalformedURLException {

        HashMap<String, Object> uiAutomator2Options = new HashMap<>();

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
                System.out.println("Looking for '秒送'...");

                findElementAndWaitToClick("//android.widget.TextView[@text=\"秒送\"]",5);
                          
                System.out.println("Clicked '秒送'");
            } catch (Exception e) {
                System.out.println("Exception: " + e.getMessage());
                System.out.println("Warning: '秒送' not found, proceeding to search directly...");
            }

            // 4. Search Flow (Handle existing input state -> Input -> Enter)
            WebElement inputField = null;
            try {
                String searchEntranceXpath = "//android.widget.TextView[@content-desc=\"搜索栏\"]";
                findElementAndWaitToClick(searchEntranceXpath, 5);

                inputField = findElementAndWaitToClick("//android.widget.TextView[contains(@resource-id,'com.jd.lib.omnichannelsearch') and @text='搜索']/../android.widget.AutoCompleteTextView[contains(@resource-id,'com.jd.lib.omnichannelsearch')]", 5);

                if (inputField != null) {
                    System.out.println("Found Input Field, sending text: " + productName);
                    inputField.click();
                    inputField.clear(); // Clear existing text if any
                    inputField.sendKeys(productName);
                    Thread.sleep(1000);

                    // Press Enter to search
                    System.out.println("Pressing Enter key...");
                    driver.pressKey(new KeyEvent(AndroidKey.ENTER));
                }
            } catch (Exception e) {
                 System.out.println("Search flow error: " + e.getMessage());
                 if (inputField == null) {
                     return reportError(users, "未找到搜索框 (EditText)");
                 }
            }

            System.out.println(driver.getContext());

            StringBuilder finalResult = new StringBuilder();

            // 5. 找到多个搜索结果
            List<WebElement> webElements = this.findElementsAndWait("//android.widget.FrameLayout[@resourceId='com.jd.lib.omnichannelsearch.feature:id/ao']", 5);

            for (WebElement element : webElements) {
                element.click();

                System.out.println(driver.getContext());

            }
            
            return finalResult.toString();

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
