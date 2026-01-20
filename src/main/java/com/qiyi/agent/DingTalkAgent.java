package com.qiyi.agent;

import java.util.Arrays;
import java.util.List;

import com.qiyi.dingtalk.DingTalkDepartment;
import com.qiyi.dingtalk.DingTalkUser;
import com.qiyi.util.DingTalkUtil;

public class DingTalkAgent {


    public static void main(String[] args) throws Exception {

        java.io.File lockFile = new java.io.File(System.getProperty("java.io.tmpdir"), "DingTalkAgent.lock");
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(lockFile, "rw");
        java.nio.channels.FileChannel channel = raf.getChannel();
        java.nio.channels.FileLock acquiredLock = null;
        try {
            acquiredLock = channel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            acquiredLock = null;
        }
        if (acquiredLock == null) {
            System.err.println("DingTalkAgent 已在运行，禁止重复启动");
            raf.close();
            return;
        }
        final java.nio.channels.FileLock lockRef = acquiredLock;
        final java.nio.channels.FileChannel channelRef = channel;
        final java.io.RandomAccessFile rafRef = raf;
        final java.io.File lockFileRef = lockFile;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                lockRef.release();
                channelRef.close();
                rafRef.close();
                lockFileRef.delete();
            } catch (Exception ignored) {}
        }));

        //启动机器人监听
        com.qiyi.tools.ToolManager.init();
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

        // 使用配置的管理员用户列表替代硬编码用户
        if (DingTalkUtil.PODCAST_ADMIN_USERS != null && !DingTalkUtil.PODCAST_ADMIN_USERS.isEmpty()) {
            for (String adminUserId : DingTalkUtil.PODCAST_ADMIN_USERS) {
                DingTalkUser targetUser = null;
                // 尝试从已加载的部门列表中查找用户详情以便显示名称
                for (DingTalkDepartment dept : allDepartments) {
                    if (dept.getUserList() != null) {
                        for (DingTalkUser u : dept.getUserList()) {
                            if (u.getUserid().equals(adminUserId)) {
                                targetUser = u;
                                break;
                            }
                        }
                    }
                    if (targetUser != null) break;
                }

                if (targetUser != null) {
                    System.out.println("用户: " + targetUser.getName() + ", ID: " + targetUser.getUserid());
                } else {
                    System.out.println("用户ID: " + adminUserId + " (未在部门列表中找到详情)");
                }

                // 0.发送文本给单用户
                DingTalkUtil.sendTextMessageToEmployees(Arrays.asList(adminUserId), "新的一天，看看我帮啥忙～");
            }
        } else {
            System.out.println("未配置 podcast.admin.users，请在 podcast.cfg 中配置");
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
