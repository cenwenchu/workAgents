package com.qiyi;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.qiyi.util.LLMUtil.ModelType;
import com.qiyi.util.PFileUtil;
import com.qiyi.service.podcast.service.FileService;
import com.qiyi.service.podcast.service.PodCastPostToWechat;
import com.qiyi.util.PlayWrightUtil;
import com.qiyi.service.wechat.WechatArticle;
import com.qiyi.util.AppLog;

public class PodCastToWechatTaskTest {

    
    public static void main(String[] args) throws Exception {

        FileService fileService = new FileService();
        ModelType modelType = ModelType.DEEPSEEK;

        PFileUtil.batchRenameChineseFiles("/Users/cenwenchu/Desktop/test/",modelType, 50);

        // 执行自动化操作
        PlayWrightUtil.Connection connection = PlayWrightUtil.connectAndAutomate();
        if (connection == null){
            AppLog.info("无法连接到浏览器，程序退出");
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

    public void testPublishPodcast() throws Exception {
        // 支持从命令行里面输入 publishPodcastDir，通过交互的方式提示用户输入
        String publishPodcastDir = "/Users/cenwenchu/Desktop/podCastItems/publish/";
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        AppLog.info("请输入 播客发布目录 (默认 " + publishPodcastDir + "): ");
        String input = scanner.nextLine();
        if (!input.trim().isEmpty()) {
            publishPodcastDir = input.trim();
        }
        
        // 执行自动化操作
        PlayWrightUtil.Connection connection = PlayWrightUtil.connectAndAutomate();
        if (connection == null){
            AppLog.info("无法连接到浏览器，程序退出");
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
            try
            {
                task.publishPodcastToWechat(podcastFilePath, true);
            }
            catch(Exception ex){
                AppLog.info("发布播客到微信公众号失败：" + podcastFilePath);
                AppLog.error(ex);
            }
        }

        // 断开浏览器连接
        PlayWrightUtil.disconnectBrowser(connection.playwright, connection.browser);
    
    }
    
}
