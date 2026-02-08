package com.qiyi.tools.android;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.service.android.AndroidDeviceManager;
import com.qiyi.service.android.BaseMobileRPAProcessor;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.LLMUtil;
import io.github.pigmesh.ai.deepseek.core.chat.UserMessage;
import com.qiyi.util.AppLog;

import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.AppiumBy;
import io.appium.java_client.android.nativekey.KeyEvent;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.Point;
import org.openqa.selenium.interactions.Actions;


import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * 淘宝 App 自动化工具类
 * <p>
 * 基于 Appium 实现，用于在淘宝 App 中执行自动化任务，如搜索商品、查找外卖店铺、
 * 进入店铺详情、下单等操作。
 * </p>
 * <p>
 * 该工具类实现了 {@link Tool} 接口，可以被 Agent 调用。
 * 核心功能包括：
 * <ul>
 *     <li>初始化 Appium Driver 连接 Android 设备</li>
 *     <li>执行搜索任务 (operation='search')：搜索商品，扫描店铺列表，整理店铺信息，并获取商品详情</li>
 *     <li>执行下单任务 (operation='buy')：直接进入指定店铺，定位商品，加入购物车并点击结算</li>
 * </ul>
 * </p>
 */
@Tool.Info(
        name = "search_taobao_product",
        description = "用于在淘宝App中搜索商品或查找外卖店铺。支持两种模式：\n" +
                "1. 查询信息模式 (operation='search')：提供商品名称和类型，可选店铺名称。会扫描店铺列表并整理店铺信息，如果指定了店铺或扫描到店铺，还会尝试获取店内商品详情。\n" +
                "2. 下单模式 (operation='buy')：必须提供商品名称、类型和店铺名称。直接进入指定店铺并定位商品，为后续下单做准备。\n" +
                "参数：\n" +
                "- product_name (String, 必填): 搜索关键词。\n" +
                "- product_type (String, 选填): '普通商品'或'外卖商品'。\n" +
                "- target_shop_name (String, 选填): 目标店铺名称。在'buy'模式下必填。\n" +
                "- operation (String, 选填): 'search' (默认) 或 'buy'。\n" +
                "- max_shop_count (Integer, 选填): 搜索店铺数量限制，默认为 3。"
)
public class TaobaoAppiumTool extends BaseMobileRPAProcessor implements Tool {
    
    private ToolMessenger messenger;

    /**
     * 尝试翻页的最大次数
     */
    private static final int TRY_PAGE_COUNT = 5;

    /**
     * 搜索店铺数量限制，默认为 3
     */
    private int maxShopCount = 3;

    /**
     * 主函数，用于本地测试
     * @param aStrings 命令行参数
     * @throws Exception 异常
     */
    public static void main(String[] aStrings) throws Exception
    {
        TaobaoAppiumTool tool = new TaobaoAppiumTool();

        JSONObject parmas = new JSONObject();
        parmas.put("product_name", "皮蛋瘦肉粥");
        parmas.put("product_type", "外卖商品");
        parmas.put("max_shop_count", 5);
        parmas.put("target_shop_name", "三米粥铺");
        parmas.put("operation", "buy");

        //tool.initializeDriver(parmas, List.of("13000000000"));
        //tool.searchProductInShop("皮蛋瘦肉粥", List.of("13000000000"), "三米粥铺", true);

        com.qiyi.tools.context.ConsoleToolContext ctx = new com.qiyi.tools.context.ConsoleToolContext();
        String result  = tool.execute(parmas, ctx, ctx);

        AppLog.info(result);
    }
    
    /**
     * 构造函数，设置淘宝 App 的包名和启动 Activity
     */
    public TaobaoAppiumTool() {
        this.setAppPackage("com.taobao.taobao");
        this.setAppActivity("com.taobao.tao.welcome.Welcome");
    }
    
    /**
     * 初始化 Appium Driver
     * @param udid 设备唯一标识符 (Serial Number)
     * @param appiumServerUrl Appium 服务器地址
     * @throws MalformedURLException URL 格式错误
     */
    /**
     * 获取连接的设备列表
     * @return 设备 UDID 列表
     */
    protected List<String> getConnectedDevices() {
        try {
            return AndroidDeviceManager.getInstance().getDevices();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 创建 AndroidDriver 实例
     * @param url Appium 服务器 URL
     * @param options UiAutomator2Options 配置
     * @return AndroidDriver 实例
     */
    protected AndroidDriver createDriver(URL url, UiAutomator2Options options) {
        return new AndroidDriver(url, options);
    }

    @Override
    public void initDriver(String udid, String appiumServerUrl)
            throws MalformedURLException {

        if (udid == null || udid.isEmpty()) {
             try {
                 List<String> devices = getConnectedDevices();
                 if (devices.isEmpty()) {
                     throw new RuntimeException("No Android devices connected.");
                 }
                 udid = devices.get(0);
             } catch (Exception e) {
                 throw new RuntimeException("Failed to get connected devices: " + e.getMessage(), e);
             }
        }

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
        
        driver = createDriver(new URL(appiumServerUrl), options);
        driver.setSetting("allowInvisibleElements", true);
        ((AndroidDriver) driver).activateApp(appPackage);
    }

    protected String llmChat(List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages, boolean stream) {
        return LLMUtil.chat(messages, stream);
    }

    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 执行工具的主要逻辑
     * @param params 工具参数
     * @param senderId 发送者 ID
     * @param atUserIds @ 用户 ID 列表
     * @return 执行结果
     */
    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        this.messenger = messenger;
        String serial = null;

        try {
            
            // 1. Prepare Product Name and Type
            String productName = null;
            String productType = "普通商品";
            String targetShopName = null;
            String operation = "search";

            if (params != null) {
                productName = params.getString("product_name");
                if (params.containsKey("product_type")) {
                    productType = params.getString("product_type");
                }
                if (params.containsKey("target_shop_name")) {
                    targetShopName = params.getString("target_shop_name");
                }
                if (params.containsKey("operation")) {
                    operation = params.getString("operation");
                }
                
                if (params.containsKey("max_shop_count")) {
                    this.maxShopCount = params.getIntValue("max_shop_count");
                } else {
                    this.maxShopCount = 3;
                }
            } else {
                this.maxShopCount = 3;
            }

            if (productName == null || productName.trim().isEmpty()) {
                String msg = "未指定商品名称 (product_name)。请在指令中明确指定要搜索的商品。";
                sendTextSafe(msg);
                return msg;
            }

            // 2. Init Driver
            try {
                if (params != null) {
                    serial = params.getString("serial");
                }
                
                sendTextSafe("正在初始化 Appium Driver" + (serial != null ? " (目标设备: " + serial + ")" : "") + "...");
                this.initDriver(serial, "http://127.0.0.1:4723");
            } catch (Exception e) {
                 AppLog.error(e);
                 return reportError("Appium 连接或启动 App 失败: " + e.getMessage());
            }

            sendTextSafe("Appium 连接成功，正在执行操作: " + operation + ", 搜索: " + productName);

            if ("buy".equals(operation)) {
                if (targetShopName == null || targetShopName.trim().isEmpty()) {
                     return reportError("下单模式 (operation='buy') 必须指定 target_shop_name。");
                }
                return executeBuyFlow(context, productName, productType, targetShopName);
            } else {
                return executeSearchFlow(context, productName, productType, targetShopName);
            }

        } catch (Throwable e) {
            AppLog.error(e);
            return reportError("Appium 执行异常 (Throwable): " + e.getMessage());
        } finally {
            this.cleanup();
        }
    }



    /**
     * 执行关键词搜索
     * @param productName 商品名称
     * @param productType 商品类型 (如 "外卖商品")
     * @throws Exception 搜索过程中发生的异常
     */
    private void searchKeyword(String productName, String productType) throws Exception {

        if ("外卖商品".equals(productType)) {
            try {
                AppLog.info("Looking for (Type: " + productType + ")...");

                findElementAndWaitToClick("//android.widget.TextView[contains(@content-desc,\"闪购\")]", 5);

                AppLog.info("Clicked '闪购'");
            } catch (Exception e) {
                AppLog.info("Exception: " + e.getMessage());
                AppLog.info("Warning: '闪购' not found, proceeding to search directly...");
            }

            // 0. Handle Marketing Popups
            handleMarketingPopups();

            // 4. Search Flow (Handle existing input state -> Input -> Enter)
            WebElement inputField = null;
            try {
                
                String searchEntranceXpath = "//android.widget.FrameLayout[@resource-id=\"com.taobao.taobao:id/search_view\"]//android.view.View[@content-desc=\"搜索栏\"]";
                findElementAndWaitToClick(searchEntranceXpath, 5);

                inputField = findElementAndWaitToClick("//android.webkit.WebView[@text=\"闪购搜索\"]//android.widget.Button", 5);

                if (inputField != null) {
                    AppLog.info("Found Input Field, sending text: " + productName);
                    
                    // Use Clipboard + Paste to handle Chinese input
                    try {
                            ((AndroidDriver) driver).setClipboardText(productName);
                            sleep(500);
                            ((AndroidDriver) driver).pressKey(new KeyEvent(AndroidKey.PASTE));
                            AppLog.info("Pasted text from clipboard");
                    } catch (Exception e) {
                            AppLog.info("Clipboard paste failed, falling back to Actions: " + e.getMessage());
                            new Actions(driver).sendKeys(productName).perform();
                    }


                    // Press Enter to search
                    AppLog.info("Pressing Enter key...");
                    driver.pressKey(new KeyEvent(AndroidKey.ENTER));
                    
                    // Hide keyboard
                    try {
                        sleep(1000);
                        ((AndroidDriver) driver).hideKeyboard();
                    } catch (Exception e) {
                        // Ignore
                    }
                }
            } catch (Exception e) {
                AppLog.info("Search flow error: " + e.getMessage());
                if (inputField == null) {
                    throw new Exception("未找到搜索框 (EditText)");
                }
            }
        }
    }

    /**
     * 处理营销弹窗
     * <p>
     * 尝试检测并关闭常见的营销弹窗、广告或升级提示。
     * 这是一个“尽力而为”的操作，不会因为未找到弹窗而抛出异常。
     * </p>
     * @param context 工具上下文
     */
    private void handleMarketingPopups() {
        AppLog.info("Checking for marketing popups...");
        try {
            // 定义常见的关闭按钮定位符
            // 注意：这些定位符是基于经验的通用猜测，可能需要根据实际 App 更新调整
            List<org.openqa.selenium.By> closeLocators = new ArrayList<>();
            
            // 1. 基于 Content-desc
            closeLocators.add(AppiumBy.xpath("//android.view.View[contains(@content-desc, '关闭')]"));
            closeLocators.add(AppiumBy.xpath("//android.widget.ImageView[contains(@content-desc, '关闭')]"));
            closeLocators.add(AppiumBy.xpath("//*[contains(@content-desc, '以后再说')]"));
            closeLocators.add(AppiumBy.xpath("//*[contains(@content-desc, '跳过')]"));
            
            // 2. 基于 Text
            closeLocators.add(AppiumBy.xpath("//*[contains(@text, '以后再说')]"));
            closeLocators.add(AppiumBy.xpath("//*[contains(@text, '跳过')]"));
            
            // 3. 基于 ID (模糊匹配 - 谨慎使用，因为可能误伤)
            // 淘宝的关闭按钮通常没有特定的 ID，或者 ID 是混淆的。
            // 这里仅作为备选，且限定包含 close 关键字
            closeLocators.add(AppiumBy.xpath("//*[contains(@resource-id, 'close') and contains(@resource-id, 'btn')]"));

            // 循环尝试，因为可能叠加多个弹窗
            int maxPopups = 3; 
            for (int i = 0; i < maxPopups; i++) {
                boolean found = false;
                for (org.openqa.selenium.By locator : closeLocators) {
                    try {
                        // 使用较短的超时时间，快速检查
                        // findElements 不会抛出异常，如果没找到返回空列表
                        // 注意：这里需要 driver 实例，TaobaoAppiumTool 继承自 BaseMobileRPAProcessor，应该有 driver 字段
                        List<WebElement> elements = driver.findElements(locator);
                        if (!elements.isEmpty()) {
                            for(WebElement btn : elements) {
                                if (btn.isDisplayed()) {
                                    AppLog.info("Found popup close button: " + locator.toString());
                                    btn.click();
                                    found = true;
                                    break;
                                }
                            }
                            if (found) {
                                sleep(1500); // 等待弹窗关闭动画
                                break; // 找到一个就跳出内层循环，进行下一次外层检测（应对多重弹窗）
                            }
                        }
                    } catch (Exception ignored) {
                        // 忽略单个定位查找失败
                    }
                }
                if (!found) {
                    break; // 没有发现任何弹窗，结束检查
                }
            }
        } catch (Exception e) {
            AppLog.info("Error handling popups: " + e.getMessage());
            // 不抛出异常，以免阻断主流程
        }
    }

    /**
     * 执行搜索流程 (Search Mode)
     * <p>
     * 1. 搜索关键词
     * 2. 扫描并提取店铺列表信息
     * 3. 使用 LLM 整理店铺数据
     * 4. 依次进入店铺获取商品详情
     * </p>
     * @param context 工具上下文 (用于发送通知)
     * @param productName 商品名称
     * @param productType 商品类型
     * @param targetShopName 目标店铺名称 (可选)
     * @return 最终汇总结果
     * @throws Exception 异常
     */
    private String executeSearchFlow(ToolContext context, String productName, String productType, String targetShopName) throws Exception {
        // 0. Handle Marketing Popups
        handleMarketingPopups();

        // 1. Search
        searchKeyword(productName, productType);

        // 2. Scan up to 5 pages
        StringBuilder rawShopData = new StringBuilder();
        Set<String> processedShops = new HashSet<>();
        
        for (int i = 0; i < TRY_PAGE_COUNT; i++) {
            // Find elements on current screen
            List<WebElement> webElements = this.findElementsAndWait("//android.view.View[contains(@resource-id,\"shopItemCard\")]/android.view.View[1]", 2);

            if (webElements.isEmpty() && i > 0) {
                AppLog.info("No elements found on page " + (i + 1));
                break; 
            }

            for (WebElement element : webElements) {
                String elementText = "";
                try {
                    elementText = element.getText();
                } catch (Exception e) {
                    continue; 
                }
                
                if (processedShops.contains(elementText)) {
                    continue;
                }
                processedShops.add(elementText);
                
                rawShopData.append("店铺信息:").append("\n");
                rawShopData.append(elementText).append("\n");
                AppLog.info(elementText);
            }
            
            // Scroll to load next page
            if (i < 4) {
                AppLog.info("Scrolling to next page...");
                try {
                    this.scroll(0.5, Direction.UP, 0.8);
                    sleep(1000);
                } catch (Exception e) {
                    AppLog.error(e);
                }
            }
        }

        String structuredData = "";
        List<String> shopNamesToFetch = new ArrayList<>();

        // 3. LLM Process
        if (rawShopData.length() > 0) {
            try {
                String prompt = "将以下文本（每块代表一个店铺信息）整理提取出：店铺名称，配送费，起送费，月售，距离，配送时间。\n" +
                                "每一行一条记录，格式为：店铺名称：XXX，配送费：XXX，起送费：XXX，月售：XXX，距离：XXX，配送时间：XXX\n" +
                                "只返回整理后的数据，除了店铺名称其他信息带上单位，不要其他废话。\n" +
                                "文本内容如下：\n" + rawShopData.toString();
                
                List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages = new ArrayList<>();
                messages.add(UserMessage.builder().addText(prompt).build());
                structuredData = llmChat(messages, false);
                sendTextSafe("店铺列表整理结果：\n" + structuredData);
                
                // Parse shop names from structured data
                String[] lines = structuredData.split("\n");
                for (String line : lines) {
                    if (line.contains("店铺名称：")) {
                        int start = line.indexOf("店铺名称：") + 5;
                        int end = line.indexOf("，", start);
                        if (end == -1) end = line.length(); // In case it's the last field or different format
                        String name = line.substring(start, end).trim();
                        if (!name.isEmpty()) {
                            shopNamesToFetch.add(name);
                        }
                    }
                }

            } catch (Exception e) {
                 AppLog.info("LLM Processing Failed: " + e.getMessage());
                 sendTextSafe(rawShopData.toString());
                 return rawShopData.toString(); // Fallback
            }
        } else {
            return "未找到相关店铺信息。";
        }

        // 4. Fetch Details "One by One"
        StringBuilder detailedResult = new StringBuilder(structuredData);
        detailedResult.append("\n\n--- 店铺内商品详情 ---\n");

        if (targetShopName != null && !targetShopName.isEmpty()) {
             // Only fetch for target shop

             String result = enterShopAndFetchDetail(context, targetShopName, productName);
             if (!result.equals(""))
                detailedResult.append(result).append("\n");
            else
                detailedResult.append("在店铺 [" + targetShopName + "] 未找到商品");
        } else {
            // Fetch for all found shops (Limit to top 3 to avoid timeout)
            int count = 0;
            for (String shopName : shopNamesToFetch) {

                String result = enterShopAndFetchDetail(context, shopName, productName);

                if (!result.equals(""))
                {
                    detailedResult.append(result).append("\n");
                    count++;
                }
                else
                    detailedResult.append("在店铺 [" + shopName + "] 未找到商品");

                if (count >= maxShopCount) break; // Limit

            }
        }
        
        sendTextSafe("最终汇总结果：\n" + detailedResult.toString());
        return detailedResult.toString();
    }

    /**
     * 在当前页面查找指定名称的店铺
     * <p>
     * 会在当前页面查找，如果找不到会尝试滚动屏幕。
     * </p>
     * @param shopName 店铺名称
     * @return 找到的店铺卡片元素，如果未找到则返回 null
     * @throws Exception 异常
     */
    private WebElement findShop(String shopName) throws Exception {
        Set<String> processedShops = new HashSet<>();
        
        for (int i = 0; i < TRY_PAGE_COUNT; i++) {
            List<WebElement> shopCards = this.findElementsAndWait("//android.view.View[contains(@resource-id,\"shopItemCard\")]/android.view.View[1]", 2);

            if (shopCards.isEmpty() && i > 0) {
                break;
            }

            for (int k = 0; k < shopCards.size(); k++) {
                WebElement card = shopCards.get(k);
                String text = "";
                try {
                    text = card.getDomAttribute("text");
                    if (text == null || text.isEmpty()) {
                        text = card.getText();
                    }
                } catch (Exception e) {
                    continue;
                }

                if (text != null && text.contains(shopName)) {
                     // Check if element is at the bottom of the screen
                     try {
                         Point location = card.getLocation();
                         int screenHeight = driver.manage().window().getSize().getHeight();
                         
                         // If element is in the bottom 20% of the screen, scroll up a bit
                         if (location.y > screenHeight * 0.8) {
                             AppLog.info("Target found but at bottom (" + location.y + "/" + screenHeight + "), scrolling up...");
                             this.scroll(0.3, Direction.UP, 0.8);
                             Thread.sleep(1000);
                             
                             // Re-fetch elements on current screen
                             shopCards = this.findElementsAndWait("//android.view.View[contains(@resource-id,\"shopItemCard\")]/android.view.View[1]", 2);
                             k = -1; // Reset loop to search again in the new list
                             continue;
                         }
                     } catch (Exception e) {
                         AppLog.info("Error checking element location: " + e.getMessage());
                     }
                    
                    return card;
                }
            }

            if (i < 4) {
                try {
                    this.scroll(0.5, Direction.UP, 0.8);
                    Thread.sleep(1000);
                } catch (Exception e) {
                    AppLog.error(e);
                }
            }
        }
        return null;
    }

    /**
     * 进入店铺并获取详情 (Search Mode 辅助方法)
     * @param context 工具上下文
     * @param shopName 店铺名称
     * @param productName 商品名称
     * @return 详情结果
     */
    private String enterShopAndFetchDetail(ToolContext context, String shopName, String productName) {
        try {
            AppLog.info("Starting detail fetch for shop: " + shopName);
            // Try to reset to home using Deep Link first (Faster)
            resetAppToHome();

            // 1. Search for Shop Name directly (most reliable way to find specific shop)
            searchKeyword(productName, "外卖商品"); // Reuse search logic but search for SHOP NAME

            Thread.sleep(1000);

            // 2. Find and Enter Shop
            WebElement shopCard = findShop(shopName);

            if (shopCard == null) {
                return "未找到店铺：" + shopName;
            }

            shopCard.click();

            // 3. Search product inside shop
            return searchProductInShop(productName, context, shopName, false);

        } catch (Exception e) {
            AppLog.error(e);
            return "获取店铺 [" + shopName + "] 详情失败：" + e.getMessage();
        }
    }

    /**
     * 执行下单流程 (Buy Mode)
     * <p>
     * 1. 搜索关键词
     * 2. 定位并进入目标店铺
     * 3. 在店内搜索商品并尝试加入购物车
     * </p>
     * @param context 工具上下文
     * @param productName 商品名称
     * @param productType 商品类型
     * @param targetShopName 目标店铺名称
     * @return 执行结果
     * @throws Exception 异常
     */
    private String executeBuyFlow(ToolContext context, String productName, String productType, String targetShopName) throws Exception {
        // 0. Handle Marketing Popups
        handleMarketingPopups();

        searchKeyword(productName, productType);
        
        // Use findShop to locate and click
        WebElement targetCard = findShop(targetShopName);

        if (targetCard != null) {
            try {
                 targetCard.click();
                 
                 sendTextSafe("找到并已进入店铺：" + targetShopName + "，准备定位商品：" + productName);
                 
                 String result = searchProductInShop(productName, context, targetShopName, true);
                 sendTextSafe(result);
                 return result;

            } catch (Exception e) {
                return reportError("进入店铺失败: " + e.getMessage());
            }
        }
        
        String msg = "未找到目标店铺：" + targetShopName;
        sendTextSafe(msg);
        return msg;
    }

    /**
     * 在店铺内搜索商品
     * @param productName 商品名称
     * @param context 工具上下文
     * @param shopName 店铺名称
     * @param clickToEnter 是否点击进入商品/加入购物车 (true: 加购模式, false: 仅获取信息)
     * @return 商品信息或操作结果
     * @throws Exception 异常
     */
    public String searchProductInShop(String productName, ToolContext context, String shopName, boolean clickToEnter) throws Exception {
        // Search for product inside the shop
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                // Use wait mechanism instead of sleep + scroll
                // Wait up to 10 seconds for the product to appear
                String productXpath = "//android.widget.TextView[contains(@text, \"" + productName + "\")]";
                List<WebElement> elements = this.findElementsAndWait(productXpath, 10);
                
                if (elements.isEmpty()) {
                    if (i == maxRetries - 1) return "";
                    Thread.sleep(1000);
                    continue;
                }
                
                WebElement productElement = elements.get(0);
                
                if (productElement != null) {
                    String productText = productElement.getText();
                    WebElement parent = null;
                    
                    try {
                        
                        parent = driver.findElement(AppiumBy.xpath("//android.view.View[contains(@resource-id,\"item_\")]/android.widget.TextView[contains(@text, '" + productName + "')]/.."));
                        
                        StringBuilder sb = new StringBuilder(shopName).append(" : ");
                        boolean foundText = false;
                        
                        List<WebElement> textViews = parent.findElements(org.openqa.selenium.By.className("android.widget.TextView"));
                        
                        if (textViews != null && !textViews.isEmpty()) {
                            for (WebElement tv : textViews) {
                                String t = tv.getText();
                                if (t != null && !t.trim().isEmpty()) {
                                    if (sb.length() > 0 && !sb.toString().trim().endsWith("¥")) 
                                        sb.append(" | ");
    
                                    sb.append(t);
                                    foundText = true;
                                }
                            }
                        } 
    
                        if (foundText) {
                            productText = sb.toString();
                            AppLog.info("Found product text: " + productText);
                        } else {
                            productText = "";
                        }
                    } catch (Exception ex) {
                        AppLog.info(ex.getMessage());
                    }
                    
                    if (clickToEnter) {
                        String msg = "已进入店铺 [" + shopName + "]，找到商品：" + productName + "，正在尝试直接加购...";
                        sendTextSafe(msg);
                        
                        try {
                            
                            try
                            {
                                //先清空购物车
                                //android.view.View[@resource-id="btn__cart"]
                                WebElement cartBtn = driver.findElement(AppiumBy.xpath("//android.view.View[@resource-id=\"btn__cart\"]"));
                                cartBtn.click();
                                Thread.sleep(2000);

                                //点击text为 清空 的组件
                                WebElement clearBtn = driver.findElement(AppiumBy.xpath("//*[@text=\"清空\"]"));
                                clearBtn.click();
                                Thread.sleep(2000);

                                clearBtn = driver.findElement(AppiumBy.xpath("//android.widget.TextView[@text=\"清空\"]"));
                                clearBtn.click();
                                Thread.sleep(1000);         
                            }
                            catch(Exception ex)
                            {
                                //do nothing;
                            }
                            

                            // Try adding to cart up to 3 times
                            boolean added = false;
                            int addCount = 0;
                            for (int k = 0; k < 5; k++) {
                                AppLog.info("Attempt " + (k+1) + " to add to cart...");
                                
                                // 1. Try to click "加购" (Add to Cart) under parent
                                try {
                                    if (parent != null) {
                                        WebElement addToCartBtn = parent.findElement(AppiumBy.xpath(".//android.widget.Button[@text='加购']"));
                                        addToCartBtn.click();
                                        addCount++;
                                    }
                                } catch (Exception e) {
                                    AppLog.info("Add to cart button click failed: " + e.getMessage());
                                }
                                
                                Thread.sleep(1000);
                
                                // 2. Check if "去结算" (Checkout) is visible and clickable
                                try {
                                    String checkoutXpath = "//android.widget.TextView[@text=\"去结算\"]";
                                    List<WebElement> checkoutBtns = this.findElementsAndWait(checkoutXpath, 2);
                                    if (!checkoutBtns.isEmpty()) {
                                        WebElement btn = checkoutBtns.get(0);
                                        if (btn.isDisplayed() && btn.isEnabled()) {
                                            AppLog.info("Found checkout button, clicking...");
                                            btn.click();
                                            added = true;
                                            break;
                                        }
                                    }
                                } catch (Exception e) {
                                     AppLog.info("Check checkout button failed: " + e.getMessage());
                                }
                            }
                
                            if (added) {
                                String totalPrice = "";
                                try {
                                    //获取 //android.widget.TextView[@text="合计¥13.9"] 组件的文本内容
                                    WebElement totalPriceElement = driver.findElement(AppiumBy.xpath("//android.widget.TextView[contains(@text,\"合计\")]"));
                                    totalPrice = totalPriceElement.getText();
                                } catch (Exception e) {
                                    // ignore
                                }

                                String discountPrice = "";
                                try {
                                    // android.widget.TextView[@text="已优惠¥16"] 优惠金额
                                    WebElement discountPriceElement = driver.findElement(AppiumBy.xpath("//android.widget.TextView[contains(@text,\"已优惠\")]"));
                                    discountPrice = discountPriceElement.getText();
                                } catch (Exception e) {
                                    // ignore
                                }
                                
                                String content = "已成功将 " + productName + " 商品加入购物车(数量:" + addCount + "，金额" + totalPrice;
                                if (discountPrice != null && !discountPrice.isEmpty()) {
                                    content += "，" + discountPrice;
                                }
                                content += ")，并点击结算";

                                return content;
                            } else {
                                return "尝试多次加购(成功:" + addCount + ")，但未检测到结算按钮";
                            }
                        } catch (Exception e) {
                             return "找到商品但点击失败：" + e.getMessage();
                        }
                    }
    
                    String msg = "已进入店铺 [" + shopName + "]，找到商品信息：\n" + productText;
                    sendTextSafe(msg);
                    return productText;
                }
            } catch (StaleElementReferenceException e) {
                AppLog.info("Stale element detected, retrying... " + (i + 1));
                if (i == maxRetries - 1) {
                     String msg = "已进入店铺 [" + shopName + "]，但在店铺内未找到商品：" + productName + " (Stale Element)";
                     sendTextSafe(msg);
                     return msg;
                }
                Thread.sleep(1000);
            } catch (Exception e) {
                if (e.getMessage().contains("do not exist in DOM")) {
                     AppLog.info("Element stale (msg check), retrying... " + (i + 1));
                     if (i < maxRetries - 1) {
                         Thread.sleep(1000);
                         continue;
                     }
                }
                String msg = "已进入店铺 [" + shopName + "]，但在店铺内未找到商品：" + productName + " (Error: " + e.getMessage() + ")";
                AppLog.info(msg);
                return "";
            }
        }

        AppLog.info("在店铺 [" + shopName + "] 未找到商品");
        return "";
    }

    private void resetAppToHome() {
        try {
            AppLog.info("Attempting to reset navigation via Back Key...");
            String homeIndicatorXpath = "//android.widget.TextView[contains(@content-desc,\"闪购\")]";
            
            for (int i = 0; i < 5; i++) {
                // Check if we are at home (Short timeout)
                if (!this.findElementsAndWait(homeIndicatorXpath, 1).isEmpty()) {
                     AppLog.info("Found Home indicator ('闪购'), stopping back navigation.");
                     break;
                }

                ((AndroidDriver) driver).pressKey(new KeyEvent(AndroidKey.BACK));
                Thread.sleep(800);
            }
        } catch (Exception e) {
            AppLog.info("Back key navigation failed: " + e.getMessage());
        }
    }

    private void cleanup() {
        if (driver != null) {
            try {
                // Force stop the app to ensure clean state for next run
                ((AndroidDriver) driver).terminateApp(appPackage);
            } catch (Exception e) {
                // Ignore if fails
            }
        }
        this.quitDriver();
    }

    private void sendTextSafe(String content) {
        if (this.messenger == null) return;
        try {
            this.messenger.sendText(content);
        } catch (Exception ignored) {
        }
    }

    private String reportError(String msg) {
        AppLog.error(msg);
        sendTextSafe("Error: " + msg);
        return msg;
    }
}
