package com.qiyi.podcast.tools;

import java.io.IOException;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.qiyi.util.PlayWrightUtil;
import com.qiyi.util.PodCastUtil;
import com.qiyi.wechat.WechatArticle;

public class PodCastPostToWechat {

    private Browser browser;
    private static final int DEFAULT_TIMEOUT_MS = 60*1000;
    public static final String SUCCESS_MSG = "成功发布:";
    public static final String WECHAT_LOGIN_URL = "https://mp.weixin.qq.com/cgi-bin/home";


    public static void main(String[] args) throws IOException {

        // 支持从命令行里面输入 publishPodcastDir，通过交互的方式提示用户输入
        String publishPodcastDir = "/Users/cenwenchu/Desktop/podCastItems/publish/";
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        System.out.print("请输入 播客发布目录 (默认 " + publishPodcastDir + "): ");
        String input = scanner.nextLine();
        if (!input.trim().isEmpty()) {
            publishPodcastDir = input.trim();
        }
        
        // 执行自动化操作
        PlayWrightUtil.Connection connection = PlayWrightUtil.connectAndAutomate();
        if (connection == null){
            System.out.println("无法连接到浏览器，程序退出");
            return;
        }

        PodCastPostToWechat task = new PodCastPostToWechat(connection.browser);


        // 从 publishPodcastDir 目录下，读取所有文件，然后分别发送到微信公众号
        java.util.List<String> podcastFilePaths = java.nio.file.Files.walk(java.nio.file.Paths.get(publishPodcastDir))
            .filter(p -> java.nio.file.Files.isRegularFile(p))
            .map(p -> p.toString())
            .collect(java.util.stream.Collectors.toList());
        
        // 发布播客到微信公众号
        for (String podcastFilePath : podcastFilePaths) {
            task.publishPodcastToWechat(podcastFilePath, true);
        }

        // 断开浏览器连接
        PlayWrightUtil.disconnectBrowser(connection.playwright, connection.browser);
    }

    public PodCastPostToWechat(Browser browser) {
        this.browser = browser;
    }

    private void log(String msg) {
        System.out.println(msg);
    }
    
    //根据指定文件，自动发布微信公众号文章
    public String publishPodcastToWechat(String podcastFilePath,boolean isDraft) throws IOException {

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
            return "文章不符合要求，无法发布";
        }


        //需要吧content里面的内容，如果有两行及以上的回车换行要压缩为一行
        article.setContent(article.getContent()
            .replaceAll("\n{2,}", "\n")
            .replace("（深度研究版）", "")
            .replace("（快速传播版）", "")
            .replace("#", ""));

        //System.out.println(article.getContent());

        // 发布播客
        String result = publishPodcast(context,page,article,isDraft);

        return result;
    }


    //发布播客
    private String publishPodcast(BrowserContext context,Page page,WechatArticle article,boolean isDraft) {
        
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
        //填写摘要
        publishPage.fill("//textarea[@name='digest']", article.getSummary());


        //点击一个div叫做原创声明，然后会打开新的浮层页面，然后继续操作浮层，最后点击按钮关闭浮层
        publishPage.click("//div[contains(@class,'js_unset_original_title') and contains(text(),'未声明')]");
        //在新打开的浮层页面上，输入作者
        publishPage.fill("//input[contains(@placeholder,'作者')]", article.getAuthor());
        //勾选确认checkbox
        publishPage.check("//i[@class='weui-desktop-icon-checkbox']");

        //点击浮层上的按钮叫做确定
        publishPage.click("//button[contains(text(),'确定')]");

        //填写分类
        publishPage.click("//div/span[contains(@class,'js_article_tags_content') and contains(text(),'未添加')]");
        //在新打开的浮层页面上，点击分类
        publishPage.click("//input[contains(@placeholder,'选择合集')]");

        //等待选项的出来
        publishPage.waitForSelector("//li[contains(@class,'select-opt-li')]");

        //点击以后，会展开一个下拉菜单，如果分类存在，则点击分类
        if (publishPage.isVisible("//li[contains(text(),'" + article.getCategory() + "')]")) {
            publishPage.click("//li[contains(text(),'" + article.getCategory() + "')]");

            //点击浮层上的按钮叫做确定
            publishPage.click("//button[contains(text(),'确认')]");

            System.out.println("设置了分类为："+article.getCategory());
        } 
        else
        {
            //分类不存在就关闭浮层,并且按钮可见
            publishPage.click("//h3[contains(text(),'合集')]/../button[contains(@class,'dialog__close-btn')]");
            
            System.out.println("分类不存在："+article.getCategory());
        }

        

        //修改一下留言的配置
        publishPage.click("//div[@class='setting-group__content']");
        publishPage.click("//label[contains(text(),'留言开关')]/..//div[contains(@class,'weui-desktop-switch__box')]");
        publishPage.click("//button[contains(text(),'确定')]");
        
        //鼠标移动到封面图片上传区域，然后上传图片
        // ElementHandle coverUploadArea = publishPage.waitForSelector("//div[contains(@class,'select-cover__mask')]");
        // //点击AI 配图
        // publishPage.click("//button[contains(@class,'js_cover_btn_area')]");

        //尝试点击一键排版
        Page operationPage = publishPage;
        // boolean isFormatPage = false;
        // try {
        //     // 检查"一键排版"按钮是否存在并可见
        //     if (publishPage.isVisible("//div[contains(text(),'一键排版')]")) {
        //         Page publishFromatNewPage = context.waitForPage(() -> {
        //             // 点击发布按钮
        //             publishPage.click("//div[contains(text(),'一键排版')]");
        //         });
                
        //         // 等待新页面加载完成
        //         publishFromatNewPage.waitForLoadState();
                
        //         // 点击使用此排版
        //         publishFromatNewPage.click("//button[contains(text(),'使用此排版')]");

        //         //等待页面加载完成，确保操作页面切换到新页面
        //         publishFromatNewPage.waitForSelector("//button/span[contains(text(),'草稿')]", 
        //             new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));
                
        //         // 切换操作页面为新打开的排版页面
        //         operationPage = publishFromatNewPage;
        //         isFormatPage = true;
                
        //         // 关闭原来的发布页面，因为操作已经转移到新页面
        //         publishPage.close();
        //     } else {
        //         log("一键排版按钮不可见，跳过");
        //     }
            
        // } catch (Exception e) {
        //     log("一键排版操作异常或按钮不存在: " + e.getMessage());
        //     // 发生异常时，确保 operationPage 仍然指向可用的页面 (publishPage)
        //     // 如果 publishPage 已经被关闭（虽然在异常块前只在成功切换时关闭），则需要小心
        //     // 但这里逻辑是如果成功打开新页面并切换了 operationPage 才会关闭 publishPage
        //     // 所以异常情况下 publishPage 应该还是打开的
        // }

        //点击发布按钮 (在当前操作页面上操作)
        if (isDraft) {
            operationPage.click("//button/span[contains(text(),'草稿')]");
        } else {
            operationPage.click("//button/span[contains(text(),'发表')]");
        }

        try {
            operationPage.waitForSelector("//div[contains(text(),'已保存') and @class='inner']", 
                new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));
            log("发布成功：" + article.getTitle());
        } catch (Exception e) {
            e.printStackTrace();
        }

        //关闭当前操作页面 (可能是原页面，也可能是排版后的新页面)
        operationPage.close();

        if (isDraft) {

            page.waitForSelector("//span[@title='内容管理']").click();

            ElementHandle draftBox = page.waitForSelector("//a[@title='草稿箱']");
            draftBox.click();

            //检查草稿箱是否有新的草稿
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

        return result;
    }

    //打开微信公众号后台，判断是否登陆，登陆后，进入发布页面
    public void openWechatPodcastBackground(Page page) {
        page.navigate(WECHAT_LOGIN_URL);
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);

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
