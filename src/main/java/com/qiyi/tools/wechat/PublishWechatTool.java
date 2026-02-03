package com.qiyi.tools.wechat;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.config.AppConfig;
import com.qiyi.podcast.service.PodCastPostToWechat;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.util.PlayWrightUtil;
import com.qiyi.util.PodCastUtil;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PublishWechatTool implements Tool {
    private static final ReentrantLock PUBLISH_LOCK = new ReentrantLock();

    public static final boolean PUBLISH_IS_DRAFT = false;

    @Override
    public String getName() {
        return "publish_wechat";
    }

    @Override
    public String getDescription() {
        return String.format("Publish podcast articles to WeChat Official Account. Parameters: isDraft (boolean, default %s).",
                PUBLISH_IS_DRAFT);
    }

    protected void disconnectBrowser(PlayWrightUtil.Connection connection) {
        if (connection != null) {
            PlayWrightUtil.disconnectBrowser(connection.playwright, connection.browser);
        }
    }

    @Override
    public String execute(JSONObject params, ToolContext context) {
        boolean isDraft = params != null && params.containsKey("isDraft") ? params.getBooleanValue("isDraft") : PUBLISH_IS_DRAFT;

        if (!PUBLISH_LOCK.tryLock()) {
            try {
                context.sendText("当前已有发布任务正在执行，请稍后再试。");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "Task locked";
        }

        try {
            String publishDirStr = getPodcastPublishDir();
            if (publishDirStr == null || publishDirStr.isEmpty()) {
                context.sendText("发布目录未配置，请检查 podcast.cfg");
                return "Config Error";
            }

            context.sendText("开始执行发布任务...");

            // 1. 准备发布文件
            stageFilesForPublishing(context);

            PlayWrightUtil.Connection connection = connectToBrowser();
            if (connection == null){
                context.sendText("无法连接到浏览器，任务终止");
                return "Browser Error";
            }

            String publishResult = "";
            try {
                // 2. 检查微信登录状态
                if (!checkWechatLogin(connection, context)) {
                    return "Login Failed";
                }

                // 3. 执行发布文件处理
                publishResult = processPublishFiles(connection, context, isDraft);

            } finally {
                disconnectBrowser(connection);
            }

            context.sendText("所有发布任务执行完毕");
            return "Publish Completed: " + publishResult;

        } catch (Exception e) {
            e.printStackTrace();
            try {
                context.sendText("发布任务执行异常: " + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return "Error: " + e.getMessage();
        } finally {
            PUBLISH_LOCK.unlock();
        }
    }

    protected String getPodcastSummaryDir() {
        return AppConfig.getInstance().getPodcastSummaryDir();
    }

    protected String getPodcastPublishDir() {
        return AppConfig.getInstance().getPodcastPublishDir();
    }

    protected String getPodcastPublishedDir() {
        return AppConfig.getInstance().getPodcastPublishedDir();
    }

    protected int getPodcastPublishBatchSize() {
        return AppConfig.getInstance().getPodcastPublishBatchSize();
    }

    protected PlayWrightUtil.Connection connectToBrowser() {
        return PlayWrightUtil.connectAndAutomate();
    }

    protected void stageFilesForPublishing(ToolContext context) {
        String summaryDirStr = getPodcastSummaryDir();
        String publishDirStr = getPodcastPublishDir();
        String publishedDirStr = getPodcastPublishedDir();
        int batchSize = getPodcastPublishBatchSize();

        if (summaryDirStr == null || publishDirStr == null || publishedDirStr == null) {
            System.err.println("目录配置不完整，无法执行文件准备");
            return;
        }

        Path summaryDir = Paths.get(summaryDirStr);
        Path publishDir = Paths.get(publishDirStr);
        Path publishedDir = Paths.get(publishedDirStr);

        if (!Files.exists(summaryDir)) {
            System.err.println("Summary 目录不存在: " + summaryDirStr);
            return;
        }

        try {
            if (!Files.exists(publishDir)) {
                Files.createDirectories(publishDir);
            }
            if (!Files.exists(publishedDir)) {
                Files.createDirectories(publishedDir);
            }

            // 获取 publish 目录下的文件集合 (避免重复拷贝)
            java.util.Set<String> existingPublishFiles = Stream.of(publishDir.toFile().list())
                    .collect(Collectors.toSet());

            // 计算当前发布目录下实际待发布的文件数量（过滤掉隐藏文件）
            long currentPendingCount = existingPublishFiles.stream()
                    .filter(name -> !name.startsWith("."))
                    .count();

            if (currentPendingCount >= batchSize) {
                System.out.println("发布目录已有 " + currentPendingCount + " 个文件，达到或超过批次大小 " + batchSize + "，不再补充新文件");
                return;
            }

            int needed = batchSize - (int)currentPendingCount;

            // 查找未发布的文件
            List<Path> candidates = Files.walk(summaryDir)
                    .filter(p -> Files.isRegularFile(p) && !p.getFileName().toString().startsWith("."))
                    .collect(Collectors.toList());
            
            // Note: In DingTalkUtil, logic was slightly truncated in reading but I can infer logic.
            // It filters out published files.
            // Let's implement robust logic: check if in publishedDir or publishDir.
            
            java.util.Set<String> publishedFiles = Stream.of(publishedDir.toFile().list())
                    .collect(Collectors.toSet());
            
            List<Path> toCopy = new ArrayList<>();
            for (Path p : candidates) {
                String name = p.getFileName().toString();
                if (!publishedFiles.contains(name) && !existingPublishFiles.contains(name)) {
                    toCopy.add(p);
                    if (toCopy.size() >= needed) break;
                }
            }
            
            if (toCopy.isEmpty()) {
                // DingTalkUtil.sendTextMessageToEmployees(notifyUsers, "没有需要发布的新文件");
                return;
            }

            int count = 0;
            for (Path p : toCopy) {
                Files.copy(p, publishDir.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
            
            context.sendText("已准备 " + count + " 个新文件到发布目录");

        } catch (Exception e) {
            e.printStackTrace();
            try {
                context.sendText("准备发布文件时出错: " + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    protected boolean checkWechatLogin(PlayWrightUtil.Connection connection, ToolContext context) {
        try {
            com.microsoft.playwright.BrowserContext browserContext = connection.browser.contexts().isEmpty() ? 
                    connection.browser.newContext() : connection.browser.contexts().get(0);
            com.microsoft.playwright.Page checkPage = browserContext.newPage();
            // 设置大视口，确保截图清晰
            checkPage.setViewportSize(1920, 1080);
            try {
                checkPage.navigate(PodCastPostToWechat.WECHAT_LOGIN_URL);
                checkPage.waitForLoadState(
                        com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                        new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                );
                if (!isWechatLoggedIn(checkPage)) {
                    context.sendText("检测到微信公众号未登录，请及时在服务器浏览器完成扫码登录，任务将继续执行等待。");
                    
                    // 尝试截图并发送
                    try {
                        // 增加等待时间，确保二维码完全加载
                        checkPage.waitForTimeout(3000); 
                        
                        String publishDirStr = getPodcastPublishDir();
                        String fileName = "login_screenshot_" + System.currentTimeMillis() + ".png";
                        Path screenshotPath = Paths.get(publishDirStr, fileName);
                        
                        // 尝试定位登录框截图，清晰度更高
                        com.microsoft.playwright.Locator loginFrame = checkPage.locator(".login_frame");
                        if (loginFrame.isVisible()) {
                            loginFrame.screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions().setPath(screenshotPath));
                        } else {
                            checkPage.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(screenshotPath));
                        }
                        
                        context.sendText("微信公众号未登录，请扫描下方二维码进行登录： --" + System.currentTimeMillis());
                        context.sendImage(screenshotPath.toFile());
                        
                        // 上传成功后删除本地文件
                        screenshotPath.toFile().delete();
                    } catch (Exception e) {
                        System.err.println("Failed to capture and send screenshot: " + e.getMessage());
                        e.printStackTrace();
                    }
                    
                    // 阻塞等待用户扫码登录
                    long maxWaitTime = 5 * 60 * 1000; // 5分钟超时
                    long startTime = System.currentTimeMillis();
                    boolean isLogged = false;
                    
                    while (!isLogged) {
                        if (System.currentTimeMillis() - startTime > maxWaitTime) {
                            context.sendText("微信登录超时，任务终止。");
                            return false;
                        }
                        
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            context.sendText("等待登录被中断，任务终止。");
                            return false;
                        }
                        isLogged = isWechatLoggedIn(checkPage);
                    }
                    
                    context.sendText("微信登录成功，继续执行任务。");
                }
                return true;
            } finally {
                if (!checkPage.isClosed()) {
                    checkPage.close();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    protected boolean isWechatLoggedIn(com.microsoft.playwright.Page page) {
        return PodCastUtil.isWechatLoggedIn(page);
    }

    protected PodCastPostToWechat createPodCastPostToWechat(com.microsoft.playwright.Browser browser) {
        return new PodCastPostToWechat(browser);
    }

    protected String processPublishFiles(PlayWrightUtil.Connection connection, ToolContext context, boolean isDraft) {
        StringBuilder executionResult = new StringBuilder();
        try {
             PodCastPostToWechat task = createPodCastPostToWechat(connection.browser);
             String publishDirStr = getPodcastPublishDir();
             Path publishDirPath = Paths.get(publishDirStr);
             
             if (!Files.exists(publishDirPath) || !Files.isDirectory(publishDirPath)) {
                 context.sendText("发布目录不存在或无效: " + publishDirStr);
                 return "Error: Invalid publish directory";
             }

             List<String> podcastFilePaths = Files.walk(publishDirPath)
                        .filter(p -> Files.isRegularFile(p) && !p.getFileName().toString().startsWith(".")) // 忽略隐藏文件
                        .map(p -> p.toString())
                        .collect(Collectors.toList());
             
             if (podcastFilePaths.isEmpty()) {
                 context.sendText("目录中没有找到待发布的文件: " + publishDirStr);
                 return "No files to publish";
             }
             
             for (String podcastFilePath : podcastFilePaths) {
                  try {
                      String result = task.publishPodcastToWechat(podcastFilePath, isDraft);
                      if (executionResult.length() > 0) executionResult.append("\n");
                      executionResult.append(new java.io.File(podcastFilePath).getName()).append(": ").append(result);

                      if (result.startsWith(PodCastPostToWechat.SUCCESS_MSG)){
                          context.sendText("文件 " + new java.io.File(podcastFilePath).getName() + "， " + result);

                          // 移动文件到已发布目录
                          try {
                              String publishedDirStr = getPodcastPublishedDir();
                              if (publishedDirStr != null) {
                                  Path publishedDir = Paths.get(publishedDirStr);
                                  if (!Files.exists(publishedDir)) {
                                      Files.createDirectories(publishedDir);
                                  }
                                  Path srcFile = Paths.get(podcastFilePath);
                                  Path destFile = publishedDir.resolve(srcFile.getFileName());
                                  Files.move(srcFile, destFile, StandardCopyOption.REPLACE_EXISTING);
                                  System.out.println("Moved published file to: " + destFile);
                              }
                          } catch (Exception moveEx) {
                               System.err.println("Failed to move published file: " + moveEx.getMessage());
                               moveEx.printStackTrace();
                               context.sendText("文件已发布但移动到已发布目录失败: " + moveEx.getMessage());
                          }
                      }
                      else
                          context.sendText("文件 " + new java.io.File(podcastFilePath).getName() + "，发布失败: " + result);
                      
                  } catch (Exception e) {
                      context.sendText("文件 " + new java.io.File(podcastFilePath).getName() + " 发布失败: " + e.getMessage());
                      e.printStackTrace();
                      if (executionResult.length() > 0) executionResult.append("\n");
                      executionResult.append(new java.io.File(podcastFilePath).getName()).append(": Error - ").append(e.getMessage());
                  }
             }
         }
         catch (Exception e) {
             e.printStackTrace();
             try {
                context.sendText("处理发布文件时出错: " + e.getMessage());
             } catch (Exception ex) {
                 ex.printStackTrace();
             }
             return "Error processing files: " + e.getMessage();
         }
         return executionResult.toString();
    }
}
