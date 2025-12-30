package com.qiyi;

import java.io.IOException;
import com.qiyi.podcast.PlayWrightUtil;
import com.qiyi.podcast.PodCastToWechat;

public class PodCastToWechatTaskTest {

    
    public static void main(String[] args) throws IOException {

        // 执行自动化操作
        PlayWrightUtil.Connection connection = PlayWrightUtil.connectAndAutomate();
        if (connection == null){
            System.out.println("无法连接到浏览器，程序退出");
            return;
        }


        PodCastToWechat task = new PodCastToWechat(connection.browser);

        task.publishPodcastToWechat("/Users/cenwenchu/Desktop/podCastItems/summary/2026年投资趋势：DeFi、代币化、资本形成、投机与人工智能_summary.txt", true);

        PlayWrightUtil.disconnectBrowser(connection.playwright, connection.browser);
    }
    
}
