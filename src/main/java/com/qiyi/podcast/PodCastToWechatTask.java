package com.qiyi.podcast;

import java.io.IOException;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;

public class PodCastToWechatTask {

    private Browser browser;
    private static final int DEFAULT_TIMEOUT_MS = 60*1000;

    public PodCastToWechatTask(Browser browser) {
        this.browser = browser;
    }

    private void log(String msg) {
        System.out.println(msg);
    }
    
    //根据指定文件，自动发布微信公众号文章
    public void publishPodcastToWechat(String podcastFilePath,boolean isDraft) throws IOException {

        if (browser == null) {
            log("浏览器未连接，请先连接浏览器");
            return;
        }

        // 创建新页面
        BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
        Page page = context.newPage();
        page.setDefaultTimeout(DEFAULT_TIMEOUT_MS);
        
        log("创建新页面");

        // 打开微信公众号后台
        openWechatPodcastBackground(page);

        // 填写播客信息
        WechatArticle article = PodCastUtil.generateWechatArticleFromDeepseek(podcastFilePath);

        // 验证文章是否符合要求
        if (!PodCastUtil.validateArticle(article)) {
            log("文章不符合要求，无法发布");
            return;
        }


        //需要吧content里面的内容，如果有两行及以上的回车换行要压缩为一行
        article.setContent(article.getContent()
            .replaceAll("\n{2,}", "\n")
            .replace("（深度研究版）", "")
            .replace("（快速传播版）", "")
            .replace("#", ""));
        
        System.out.println(article.getContent());

        // 发布播客
        publishPodcast(context,page,article,isDraft);
    }


    //发布播客
    private void publishPodcast(BrowserContext context,Page page,WechatArticle article,boolean isDraft) {
        
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

        //点击发布按钮
        if (isDraft) {
            publishPage.click("//button/span[contains(text(),'草稿')]");
        } else {
            publishPage.click("//button/span[contains(text(),'发表')]");
        }

        try {
            publishPage.waitForSelector("//div[contains(text(),'已保存') and @class='inner']", 
                new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));
            log("发布成功：" + article.getTitle());
        } catch (Exception e) {
            e.printStackTrace();
        }

        //关闭publishPage，回到打开前的页面
        publishPage.close();

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
            }
        }
    }

    //打开微信公众号后台，判断是否登陆，登陆后，进入发布页面
    public void openWechatPodcastBackground(Page page) {
        page.navigate("https://mp.weixin.qq.com/cgi-bin/home");

        if (!PodCastUtil.isWechatLoggedIn(page)) {
            log("用户未登录，请手动登录后继续");
            PodCastUtil.waitForManualLogin(page);
        }

        //点击首页的按钮，进入发布页面
        page.click("//div//a[contains(@title,'首页')]");

    }
    
}
