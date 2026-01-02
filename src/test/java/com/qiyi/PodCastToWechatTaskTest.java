package com.qiyi;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.qiyi.podcast.tools.PodCastPostToWechat;
import com.qiyi.util.PlayWrightUtil;
import com.qiyi.wechat.WechatArticle;

public class PodCastToWechatTaskTest {

    
    public static void main(String[] args) throws Exception {

        // 执行自动化操作
        PlayWrightUtil.Connection connection = PlayWrightUtil.connectAndAutomate();
        if (connection == null){
            System.out.println("无法连接到浏览器，程序退出");
            return;
        }


        PodCastPostToWechat task = new PodCastPostToWechat(connection.browser);

        WechatArticle article = new WechatArticle();
        article.setTitle("测试播客标题");
        article.setAuthor("测试作者");
        article.setContent("这是测试播客的内容");
        article.setSummary("这是测试播客的摘要");
        article.setCategory("测试分类");

        BrowserContext context = connection.browser.contexts().isEmpty() ? connection.browser.newContext() : connection.browser.contexts().get(0);
        Page page = context.newPage();

        task.openWechatPodcastBackground(page);

        task.publishPodcast(context, page, article, false,"/Users/cenwenchu/Desktop/podCastItems/publish/");
        PlayWrightUtil.disconnectBrowser(connection.playwright, connection.browser);
    }
    
}
