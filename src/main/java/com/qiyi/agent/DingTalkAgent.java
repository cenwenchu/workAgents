package com.qiyi.agent;

import java.util.Arrays;
import java.util.List;

import com.qiyi.dingtalk.DingTalkDepartment;
import com.qiyi.dingtalk.DingTalkUser;
import com.qiyi.util.DingTalkUtil;

public class DingTalkAgent {


    public static void main(String[] args) throws Exception {

        //启动机器人监听
        DingTalkUtil.startRobotMsgCallbackConsumer();

        // 获取所有部门列表
        List<DingTalkDepartment> allDepartments = DingTalkUtil.getAllDepartments(true,true);
        System.out.println("获取到部门总数: " + allDepartments.size());
        for (DingTalkDepartment dept : allDepartments) {
            System.out.println("部门: " + dept.getName() + ", ID: " + dept.getDeptId() + ", 用户数量: " + dept.getUserList().size());

            for(DingTalkUser user : dept.getUserList())
            {
                System.out.println("用户: " + user.getName() + ", ID: " + user.getUserid());
            }
        }

        DingTalkUser user = DingTalkUtil.findUserFromDepartmentByName("岑文初");
        if (user != null) {
            System.out.println("用户: " + user.getName() + ", ID: " + user.getUserid());

            //0.发送文本给单用户
            DingTalkUtil.sendTextMessageToEmployees(Arrays.asList(user.getUserid()),"发送单聊测试消息！");

            // 1. 发送文本消息
            DingTalkUtil.sendTextMessageToGroup("测试文本消息：钉钉，让进步发生", Arrays.asList(user.getUserid()), false);

            // 2. 发送 Link 消息 (带图片)
            DingTalkUtil.sendLinkMessageToGroup(
                    "时代的火车向前开",
                    "这个即将发布的新版本，创始人xx称它为红树林。",
                    "https://www.dingtalk.com/",
                    "https://img.alicdn.com/tfs/TB1NwmBEL9TBuNjy1zbXXXpepXa-2400-1218.png"
            );

            // 3. 发送 Markdown 消息 (支持正文插入图片)
            String markdownText = "#### 杭州天气 \n" +
                    "> 9度，西北风1级，空气良89，相对温度73%\n\n" +
                    "> ![screenshot](https://img.alicdn.com/tfs/TB1NwmBEL9TBuNjy1zbXXXpepXa-2400-1218.png)\n" +
                    "> ###### 10点20分发布 [天气](https://www.dingtalk.com/) \n";
            DingTalkUtil.sendMarkdownMessageToGroup("杭州天气", markdownText, Arrays.asList(user.getUserid()), false);
            
            // 4. 发送 ActionCard (独立跳转卡片)
            DingTalkUtil.sendActionCardMessageToGroup(
                    "乔布斯 20 年前想打造一间苹果咖啡厅",
                    "![screenshot](https://img.alicdn.com/tfs/TB1NwmBEL9TBuNjy1zbXXXpepXa-2400-1218.png) \n\n ### 乔布斯 20 年前想打造的苹果咖啡厅 \n\n Apple Store 的设计正从原来满满的科技感走向生活化，而其生活化的走向其实可以追溯到 20 年前苹果一个建立咖啡馆的计划",
                    "阅读全文",
                    "https://www.dingtalk.com/"
            );
        } else {
            System.out.println("未找到用户: 岑文初");
        }

        //交互的读取控制台消息，来判断是否要暂停机器人消息回调
        // DingTalkUtil.stopRobotMsgCallbackConsumer();

        System.out.println("\n机器人监听已启动。在控制台输入 'exit' 并回车以停止程序...");
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        while (true) {
            if (scanner.hasNextLine()) {
                String input = scanner.nextLine();
                if ("exit".equalsIgnoreCase(input.trim())) {
                    DingTalkUtil.stopRobotMsgCallbackConsumer();
                    System.out.println("程序已退出。");
                    break;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        scanner.close();
        System.exit(0);
    }
    
}
