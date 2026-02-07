package com.qiyi.service.podcast.service;


import java.nio.file.Files;
import java.nio.file.Paths;
import com.qiyi.util.AppLog;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotScale;
import com.qiyi.config.AppConfig;
import com.qiyi.service.dingtalk.DingTalkService;
import com.qiyi.util.PodCastUtil;
import com.qiyi.service.wechat.WechatArticle;

/**
 * 将播客摘要/正文自动发布到微信公众号后台（草稿/发表）。
 *
 * <p>通过 Playwright 操作微信公众平台页面：打开后台 → 新建图文 → 填写标题/作者/正文/摘要 → 设置原创声明/分类/封面 → 保存草稿或发表。</p>
 */
public class PodCastPostToWechat {

    private Browser browser;
    private static final int DEFAULT_TIMEOUT_MS = 30*1000;
    private static final int DIGEST_MAX_CHARS = 120;
    public static final String SUCCESS_MSG = "成功发布:";
    public static final String WECHAT_LOGIN_URL = "https://mp.weixin.qq.com/cgi-bin/home";
    private static final DingTalkService DING_TALK_SERVICE = DingTalkService.fromAppConfig();

    public PodCastPostToWechat(Browser browser) {
        this.browser = browser;
    }

    private void log(String msg) {
        AppLog.info(msg);
    }
    
    //根据指定文件，自动发布微信公众号文章
    public String publishPodcastToWechat(String podcastFilePath,boolean isDraft) throws Exception {

        if (browser == null) {
            log("浏览器未连接，请先连接浏览器");
            return "浏览器未连接，请先连接浏览器";
        }

        // 创建新页面
        BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
        Page page = context.newPage();
        page.setDefaultTimeout(DEFAULT_TIMEOUT_MS);
        
        log("创建新页面");

        // 打开微信公众号后台
        openWechatPodcastBackground(page);

        // 填写播客信息
        WechatArticle article = null;
        try {
            showLoadingTip(page, "正在调用大模型处理将要发布的文章，请稍等...");
            article = PodCastUtil.generateWechatArticleFromDeepseek(podcastFilePath);
        } finally {
            removeLoadingTip(page);
        }

        // 验证文章是否符合要求
        if (!PodCastUtil.validateArticle(article)) {
            log("文章不符合要求，无法发布");
            AppLog.info(article.toString());
            return "文章不符合要求，无法发布";
        }


        //需要吧content里面的内容，如果有两行及以上的回车换行要压缩为一行
        article.setContent(article.getContent()
            .replaceAll("\n{2,}", "\n")
            .replace("（深度研究版）", "")
            .replace("（快速传播版）", "")
            .replace("#", ""));

        //AppLog.info(article.getContent());

        // 发布播客
        String result = publishPodcast(context,page,article,isDraft,podcastFilePath);

        return result;
    }


    //发布播客
    public String publishPodcast(BrowserContext context,Page page,WechatArticle article,boolean isDraft,String podcastFilePath) throws Exception {
        
        String result = SUCCESS_MSG + article.getTitle();

        //检查发布按钮是否可见
        ElementHandle publishButton = page.waitForSelector("//div[@class='new-creation__menu-title' and contains(text(),'文章')]",
             new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));
       
        //点击后会弹出新页面，然后要在新页面上操作，填写文章标题等
        Page publishPage = context.waitForPage(() -> {
            // 点击发布按钮
            publishButton.click();
        });

        //填写文章标题
        publishPage.fill("//textarea[contains(@placeholder,'标题') and @name='title']", article.getTitle());
        //填写作者
        publishPage.fill("//input[contains(@placeholder,'作者') and @name='author']", article.getAuthor());
        //写入正文
        publishPage.fill("//div[contains(@class,'ProseMirror') and @contenteditable='true']", article.getContent());
        // 填写摘要：微信公众号摘要字段有长度限制，超长会导致保存/发表失败
        publishPage.fill("//textarea[@name='digest']", normalizeWechatDigest(article.getSummary()));


        //点击一个div叫做原创声明，然后会打开新的浮层页面，然后继续操作浮层，最后点击按钮关闭浮层
        publishPage.click("//div[contains(@class,'js_unset_original_title') and contains(text(),'未声明')]");
        //在新打开的浮层页面上，输入作者
        publishPage.fill("//input[contains(@placeholder,'作者')]", article.getAuthor());
        //勾选确认checkbox
        publishPage.check("//i[@class='weui-desktop-icon-checkbox']");

        //点击浮层上的按钮叫做确定
        String confirmBtn = "//button[contains(text(),'确定')]";
        if (publishPage.isVisible(confirmBtn) && publishPage.isEnabled(confirmBtn)) {
            publishPage.click(confirmBtn);
        } else {
            publishPage.click("//button[contains(text(),'取消')]");
        }

        //填写分类
        publishPage.click("//div/span[contains(@class,'js_article_tags_content') and contains(text(),'未添加')]");
        //在新打开的浮层页面上，点击分类
        publishPage.click("//input[contains(@placeholder,'选择合集')]");

        //等待选项的出来
        publishPage.waitForSelector("//li[contains(@class,'select-opt-li')]",
             new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));

        //点击以后，会展开一个下拉菜单，如果分类存在，则点击分类
        if (publishPage.isVisible("//li[contains(text(),'" + article.getCategory() + "')]")) {
            publishPage.click("//li[contains(text(),'" + article.getCategory() + "')]");

            //点击浮层上的按钮叫做确定
            publishPage.click("//button[contains(text(),'确认')]");

            AppLog.info("设置了分类为："+article.getCategory());
        } 
        else
        {
            //分类不存在就关闭浮层,并且按钮可见
            publishPage.click("//h3[contains(text(),'合集')]/../button[contains(@class,'dialog__close-btn')]");
            
            AppLog.info("分类不存在："+article.getCategory());
        }

        
        //修改一下留言的配置
        publishPage.click("//div[@class='setting-group__content']");
        publishPage.click("//label[contains(text(),'留言开关')]/..//div[contains(@class,'weui-desktop-switch__box')]");
        confirmBtn = "//button[contains(text(),'确定')]";
        String confirmBtnClass = publishPage.getAttribute(confirmBtn, "class");
        if (publishPage.isVisible(confirmBtn) && publishPage.isEnabled(confirmBtn) && (confirmBtnClass == null || !confirmBtnClass.contains("btn_disabled"))) {
            publishPage.click(confirmBtn);
        } else {
            publishPage.click("//button[contains(text(),'取消')]");
        }
        
        //鼠标移动到coverUploadArea，然后上传图片
        ElementHandle coverUploadArea = publishPage.querySelector("//div[contains(@class,'select-cover__mask')]");
        if (coverUploadArea != null) {
            coverUploadArea.hover();

            publishPage.waitForSelector("//li/a[contains(text(),'图片库')] >> visible=true",
             new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS)).click();


            publishPage.waitForSelector("//div[contains(@class,'img-picker__item')]",
             new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS)).click();

             publishPage.click("//button[contains(text(),'下一步')]");
             publishPage.click("//button[contains(text(),'确认')]");

        }


        //点击发布按钮 (在当前操作页面上操作)
        if (isDraft) {
            publishPage.click("//button/span[contains(text(),'草稿')]");

            try {
                publishPage.waitForSelector("//div[contains(text(),'已保存') and @class='inner']", 
                    new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));
                log("发布成功：" + article.getTitle());
            } catch (Exception e) {
                AppLog.error(e);
            }

            //关闭当前操作页面 (可能是原页面，也可能是排版后的新页面)
            publishPage.close();

        } else {
            // publishPage.click("//button/span[contains(text(),'发表')]");
            // 使用 evaluate 强制触发点击，或者等待可见后再点
            // 优化定位：直接定位到 button 元素，而非内部 span
            com.microsoft.playwright.Locator publishBtn = publishPage.locator("//span[@id='js_send']/button");
            publishBtn.waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE).setTimeout(DEFAULT_TIMEOUT_MS));
            
            // 关键修复：等待 1.5s 确保 JS 事件监听器已绑定 (解决 visible 但点击无效的问题)
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

            // 检查是否处于软禁用状态 (weui-btn_disabled)
            String btnClass = publishBtn.getAttribute("class");
            if (btnClass != null && btnClass.contains("disabled")) {
                AppLog.info("警告：发表按钮似乎处于禁用状态 (class=" + btnClass + ")");
            }

            // 尝试点击
            try {
                publishBtn.click();
            } catch (Exception e) {
                AppLog.info("常规点击发表失败，尝试 JS 点击: " + e.getMessage());
                publishBtn.evaluate("element => element.click()");
            }

            try
            {
                //无需声明并发表
                com.microsoft.playwright.Locator noDeclareBtn = publishPage.locator("//button[contains(text(),'无需声明并发表')]");
                if (noDeclareBtn.isVisible()) {
                    noDeclareBtn.click();
                }
            }
            catch(Exception ex)
            {
                AppLog.info("无需声明并发表 检测/点击异常 (可忽略): " + ex.getMessage());
            }

            // Check if final publish button is visible, if not, retry clicking the initial publish button
            String finalPublishSelector = "//button[contains(text(),'发表')] >> visible=true";
            if (!publishPage.isVisible(finalPublishSelector)) {
                AppLog.info("Final publish button not visible, retrying initial publish button click...");
                try {
                    publishBtn.click();
                } catch (Exception e) {
                    publishBtn.evaluate("element => element.click()");
                }
                // Small wait for UI update
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }

            publishPage.waitForSelector(finalPublishSelector,
                new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS)).click();

            if (publishPage.isVisible("//button[contains(text(),'继续发表')]")) {
                publishPage.click("//button[contains(text(),'继续发表')]");
            } 

            
            // 增加等待时间，确保二维码完全加载
            publishPage.waitForSelector("//p[contains(text(),'请联系管理员进行验证')]", 
                new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                AppLog.error(e);
            }
            
            String fileName = "publish_screenshot_" + System.currentTimeMillis() + ".png";

            //podcastFilePath 如果是目录则直接用，如果是文件，则取他的目录
            String podcastDirPath = podcastFilePath;
            if (Files.isRegularFile(Paths.get(podcastFilePath))) {
                podcastDirPath = Paths.get(podcastFilePath).getParent().toString();
            }

            java.nio.file.Path screenshotPath = java.nio.file.Paths.get(podcastDirPath, fileName);
            
            // 尝试定位登录框截图，清晰度更高
            com.microsoft.playwright.Locator qrcodeScanElement = publishPage.locator(".qrcode_scan");

            if (qrcodeScanElement.isVisible()) {
                qrcodeScanElement.screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions()
                    .setPath(screenshotPath).setScale(ScreenshotScale.DEVICE)); 
            } else {
                publishPage.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(screenshotPath));
            }
            
            String mediaId = DING_TALK_SERVICE.uploadMedia(screenshotPath.toFile());

            DING_TALK_SERVICE.sendAsyncWorkTextMessage(DING_TALK_SERVICE.getConfig().getPodcastAdminUsers(), "微信公众号文章需要扫码发布，请扫描下方二维码： --" + System.currentTimeMillis());
            DING_TALK_SERVICE.sendAsyncWorkImageMessage(DING_TALK_SERVICE.getConfig().getPodcastAdminUsers(), mediaId);
            
            // 上传成功后删除本地文件
            screenshotPath.toFile().delete();

            //等待operationPage被关闭 或者 发表按钮消失（代表发布成功），给予足够的时间扫码
            //使用轮询方式，以便在页面关闭时能立即感知，避免 waitForSelector 在页面关闭后长时间等待
            long maxWait = DEFAULT_TIMEOUT_MS * 3; 
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < maxWait) {
                if (publishPage.isClosed() || !publishPage.isVisible("//p[contains(text(),'请联系管理员进行验证')]")) {
                    break;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }

            Thread.sleep(3000);

            // 确保页面被关闭，才继续向下执行
            if (!publishPage.isClosed()) {
                publishPage.close();
            }
        }


        if (isDraft) {
            page.waitForSelector("//span[@title='内容管理']").click();
            ElementHandle draftBox = page.waitForSelector("//a[@title='草稿箱']");
            draftBox.click();

            try
            {
                page.waitForSelector("//a/span[contains(text(),'" + article.getTitle() + "')]", new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));
                log("创建草稿成功：" + article.getTitle());
            }
            catch(Exception ex){
                log("草稿箱没有新的草稿：" + article.getTitle());
                result = "草稿箱没有新的草稿：" + article.getTitle();
            }
        }
        else
        {
            // 循环尝试检查发布结果，支持刷新页面
            ElementHandle publishedArticle = null;
            int maxRetries = 3;
            boolean found = false;

            for (int i = 0; i < maxRetries; i++) {
                try {
                    // 每次尝试都重新导航，确保列表刷新
                    if (i > 0) {
                        log("未找到已发布文章，刷新页面重试 (" + i + "/" + maxRetries + ")...");
                        page.reload();
                        page.waitForLoadState(
                                com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                                new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                        );
                    }

                    page.waitForSelector("//a[@title='首页']", new Page.WaitForSelectorOptions().setTimeout(20000)).click();
                    page.waitForSelector("//span[@title='内容管理']", new Page.WaitForSelectorOptions().setTimeout(20000)).click();
                    ElementHandle publicRecord = page.waitForSelector("//a[@title='发表记录']", new Page.WaitForSelectorOptions().setTimeout(20000));
                    publicRecord.click();

                    // 检查是否有已发布的文章
                    publishedArticle = page.waitForSelector("//div[@class='weui-desktop-block'][.//a/span[contains(text(),'" + article.getTitle().trim() + "')] and .//span[contains(text(),'已发表')]]", 
                        new Page.WaitForSelectorOptions().setTimeout(30000)); // 30s timeout per attempt
                    
                    if (publishedArticle != null) {
                        found = true;
                        break;
                    }
                } catch (Exception e) {
                    if (i == maxRetries - 1) {
                         log("尝试 " + maxRetries + " 次后仍未找到发布文章: " + e.getMessage());
                    }
                }
            }

            if (found && publishedArticle != null) {
                try
                {
                    String articleUrl = publishedArticle.querySelector("a.weui-desktop-mass-appmsg__title").getAttribute("href");
                    result += " ，文章网页地址：" + articleUrl;
                    log("发布文章成功：" + article.getTitle());
                }
                catch(Exception ex){
                    log("获取文章网页地址失败：" + article.getTitle());
                    result = "获取文章网页地址失败：" + article.getTitle();
                    AppLog.error(ex);
                }
            } else {
                log("没有新的发布内容：" + article.getTitle());
                result = "没有新的发布内容：" + article.getTitle();
            }
        } 

        return result;
    }

    /**
     * 规范化微信公众号摘要：压缩空白并限制最大字数，避免超长导致提交失败。
     */
    static String normalizeWechatDigest(String summary) {
        if (summary == null) return "";
        String normalized = summary.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return "";
        return truncateByCodePoints(normalized, DIGEST_MAX_CHARS);
    }

    private static String truncateByCodePoints(String text, int maxCodePoints) {
        if (text == null) return "";
        if (maxCodePoints <= 0) return "";
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maxCodePoints) return text;
        int endIndex = text.offsetByCodePoints(0, maxCodePoints);
        return text.substring(0, endIndex);
    }

    //打开微信公众号后台，判断是否登陆，登陆后，进入发布页面
    public void openWechatPodcastBackground(Page page) {
        page.navigate(WECHAT_LOGIN_URL);
        page.waitForLoadState(
                com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
        );

        long maxWaitTime = 5 * 60 * 1000; // 5 minutes
        long startTime = System.currentTimeMillis();
        boolean isLogged = PodCastUtil.isWechatLoggedIn(page);

        while (!isLogged) {
            if (System.currentTimeMillis() - startTime > maxWaitTime) {
                log("登录超时，请重新运行任务。");
                throw new RuntimeException("微信公众号登录超时");
            }
            
            log("用户未登录公众号后台，请在浏览器中手动扫码登录...");
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待登录被中断");
            }
            isLogged = PodCastUtil.isWechatLoggedIn(page);
        }

        //点击首页的按钮，进入发布页面
        try {
            // 等待首页按钮出现，确保页面加载完成
            page.waitForSelector("//div//a[contains(@title,'首页')]", 
                new Page.WaitForSelectorOptions().setTimeout(10000));
            page.click("//div//a[contains(@title,'首页')]");
        } catch (Exception e) {
            log("注意：未能点击'首页'按钮，可能是已在首页或页面结构变化，尝试继续执行...");
        }

    }

    private void showLoadingTip(Page page, String message) {
        String js = "const tip = document.createElement('div');" +
                    "tip.id = 'ai-loading-tip';" +
                    "tip.style.position = 'fixed';" +
                    "tip.style.top = '20px';" +
                    "tip.style.left = '50%';" +
                    "tip.style.transform = 'translateX(-50%)';" +
                    "tip.style.padding = '15px 25px';" +
                    "tip.style.backgroundColor = 'rgba(0, 0, 0, 0.8)';" +
                    "tip.style.color = 'white';" +
                    "tip.style.borderRadius = '5px';" +
                    "tip.style.zIndex = '99999';" +
                    "tip.style.fontSize = '16px';" +
                    "tip.style.boxShadow = '0 4px 6px rgba(0,0,0,0.1)';" +
                    "tip.innerText = '" + message + "';" +
                    "document.body.appendChild(tip);";
        page.evaluate(js);
    }

    private void removeLoadingTip(Page page) {
        String js = "const tip = document.getElementById('ai-loading-tip');" +
                    "if (tip) tip.remove();";
        page.evaluate(js);
    }
    
}
