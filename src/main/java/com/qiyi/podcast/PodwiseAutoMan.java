/**
 * 
 */
package com.qiyi.podcast;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;

//先要运行这个启动可信任浏览器
//nohup /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --remote-debugging-port=9222 --user-data-dir="/tmp/chrome-debug" > /tmp/chrome-debug.log 2>&1 &

//lsof -ti:9222 | xargs kill -9  杀死进程

/**
 * 
 */
public class PodwiseAutoMan {

    public final static String DownloadDir = "/Users/cenwenchu/Desktop/podItems/";

    private final static int MaxProcessCount = 10;
    private final static int ProcessSummaryCount = 1;

    //两个模型枚举
    public enum ModelType {
        DEEPSEEK,
        GEMINI
    }


	public static void main(String[] args) {

        // 执行自动化操作
        //new PodwiseAutoMan().connectAndAutomate();

        //对于下载的文件，通过调用gemini的api来做翻译和中文摘要
        new PodwiseAutoMan().processDownloadedFiles(ProcessSummaryCount,ModelType.DEEPSEEK,true);
		
	}

    public void connectAndAutomate() {
        Playwright playwright = null;
        Browser browser = null;
        
        try {
            playwright = Playwright.create();
            
            // 1. 获取 Chrome 的 WebSocket 调试 URL
            String wsEndpoint = PodCastUtil.getChromeWsEndpoint(9222);
            
            if (wsEndpoint == null) {
                
                PodCastUtil.startChromeBrowser();

                wsEndpoint = PodCastUtil.getChromeWsEndpoint(9222);

                if (wsEndpoint == null) 
                {
                    System.out.println("未找到运行的 Chrome 实例，请先以调试模式启动 Chrome");
                    System.out.println("启动命令：chrome --remote-debugging-port=9222");

                    return;
                }
                
            }
            
            System.out.println("连接到: " + wsEndpoint);
            
            // 2. 连接到现有浏览器
            browser = playwright.chromium().connectOverCDP(wsEndpoint);
            
            // 3. 获取默认上下文
            BrowserContext context = browser.contexts().isEmpty() ? 
                browser.newContext() : browser.contexts().get(0);
            
            // 4. 使用现有页面或创建新页面
            Page page;
            page = context.newPage();
            System.out.println("创建新页面");
            
            // 5. 导航到目标网站
            page.navigate("https://podwise.ai/dashboard/episodes");
            
            // 6. 检查是否已登录
            if (!PodCastUtil.isLoggedIn(page)) {
               System.out.println("用户未登录，请手动登录后继续");
                // 等待用户手动登录
                PodCastUtil.waitForManualLogin(page);
            }

            performAutomationTasks(page,context);
                 
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (playwright != null) {
                playwright.close();
            }

            // 7. 杀死占用 9222 端口的进程
            PodCastUtil.killChromeProcess(9222);
        }
    }
    
    
    private void performAutomationTasks(Page page,BrowserContext context) {

        List<PodCastItem> itemList = new ArrayList<>();
        List<String> itemNameList = new ArrayList<>();

        try {
            //从本地文件夹载入已经处理过的item
            loadProcessedItems(itemNameList); 

            // 点击libaray
            ElementHandle libraryButton = page.waitForSelector(
                "//div/span[contains(text(),'Library')]", 
                new Page.WaitForSelectorOptions().setTimeout(10000)
            );
            
            if (libraryButton != null) {
                System.out.println("找到Library按钮: " + libraryButton);
                libraryButton.click();

				ElementHandle followingBtn = page.waitForSelector(
                	"//div/button[contains(text(),'Following')]", 
                	new Page.WaitForSelectorOptions().setTimeout(10000)
            	);

				if (followingBtn !=  null)
				{
					followingBtn.evaluate("node => node.click()");
					
					String preciseXpath = """
                    //div[
                       ./img[contains(@alt, 'Podcast Cover')] 
                      and .//a[contains(@href, 'dashboard')]
                    ]
                  """;

				   page.waitForSelector(
        						preciseXpath,
        					new Page.WaitForSelectorOptions().setTimeout(10000));

                    processNodeList(itemList,itemNameList,page,preciseXpath);

					downloadPodcasts(itemList,context,true);
				}
            }
            
        } catch (Exception e) {
            System.out.println("自动化任务出错: " + e.getMessage());
        }
    }

    private void loadProcessedItems(List<String> itemNameList)
    {
        try {
            // 从本地文件夹载入已经处理过的item
            File folder = new File(DownloadDir);
            if (!folder.exists()) {
                folder.mkdirs();
            }
            else
            {
                //读取目录下所有文件,pdf类型的文件
                File[] files = folder.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile() && file.getName().endsWith(".pdf") && file.getName().contains("_")) {
                            itemNameList.add(file.getName().replace(".pdf", "").split("_")[1]);
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("读取已处理项文件时出错: " + e.getMessage());
        }
    }


    private void processNodeList(List<PodCastItem> itemList,List<String> itemNameList,Page page,String preciseXpath)
    {

        int processCount = 0;
        int tryTimes = 0;

        List<ElementHandle> elements = page.querySelectorAll(preciseXpath);

        do{
            
            if (elements.size() > processCount)
            {
                // 遍历所有元素
                for (int i = processCount; i < elements.size(); i++) {
                    ElementHandle element = elements.get(i);
                    
                    //判断element里面是否有div包含子元素span的文字内容为 Ready
                    boolean hasReadySpan = false;
                    try {
                        // 使用querySelector查找包含span且文本为Ready的div元素
                        ElementHandle readyDiv = element.querySelector("//div/span[contains(text(),'Ready')]");
                        if (readyDiv != null) {
                            hasReadySpan = true;
                            //System.out.println("找到包含'Ready'状态的元素");
                        }
                    } catch (Exception e) {
                        System.out.println("检查Ready状态时出错: " + e.getMessage());
                    }
                        
                    PodCastItem item = new PodCastItem();

                    parseLinkChild(element,item);
                    parseChannelChild(element,item);
                    item.isProcessed = hasReadySpan;

                    if (!itemNameList.contains(item.title))
                    {
                        itemList.add(item);

                        itemNameList.add(item.title);

                        System.out.println("new item: " + item.channelName +  "," + item.title +  "," + item.linkString+ "," + item.isProcessed); 
                    }
                    else
                    {
                        System.out.println("重复item: " + item.channelName +  "," + item.title); 
                    }
  
                }

                processCount = elements.size(); 
                System.out.println("处理了元素总数: " + processCount);
            }
            else{
                tryTimes += 1;
            }

            ElementHandle element = elements.get(elements.size() - 1);

            try {
                // 确保元素可见
                element.evaluate("element => element.scrollIntoViewIfNeeded()");
                System.out.println("已将最后一个元素滚动到可见区域");
                
                // 等待元素稳定
                Thread.sleep(1000);
                
                // 开始下拉滚动
                System.out.println("等待底部内容加载...");

                // 方法A: 等待页面高度变化
                PodCastUtil.waitForHeightStabilized(page, 10); // 最多等10秒

                // 等待页面加载新内容
                // 优化：使用更宽松的加载状态，并设置超时时间
                try {                  
                    // 然后等待特定元素出现（使用原来的XPath选择器），表示新内容已加载
                    page.waitForSelector(preciseXpath, new Page.WaitForSelectorOptions().setTimeout(3000));
                    
                    System.out.println("下拉滚动完成，页面已加载新内容");
                } catch (Exception e) {
                    // 如果超时，继续执行，不要等待太长时间
                    System.out.println("页面加载超时，继续执行");
                }
                
                // 短暂等待，确保页面有足够时间处理
                Thread.sleep(500);
                
                // 更新元素列表，包含新加载的元素
                elements = page.querySelectorAll(preciseXpath);
                System.out.println("更新后元素总数: " + elements.size());
                
            } catch (Exception e) {
                System.out.println("滚动操作时出错: " + e.getMessage());
                e.printStackTrace();
            }
        } while (tryTimes <= 3 && processCount < MaxProcessCount) ;
        
    }


    private void downloadPodcasts(List<PodCastItem> itemList,BrowserContext context,boolean needTranslateCN){
        for (PodCastItem item : itemList) {
            if (item.isProcessed) {
                // 下载 podcast

                String podItemUrl = "https://podwise.ai" + item.linkString;

                Page downloadPage = context.newPage();
                downloadPage.navigate(podItemUrl);
                downloadPage.waitForLoadState(LoadState.NETWORKIDLE);

                downloadPage.bringToFront();  // 1. 将页面带到前台
                   
                try{
                    // 等待页面稳定
                    Thread.sleep(1000); 

                    ElementHandle exportDiv = downloadPage.querySelector("//button/span[contains(text(),'Export')]");

                    if (exportDiv != null)
                    {
                        exportDiv.evaluate("element => element.scrollIntoViewIfNeeded()");
                        // 等待元素可交互
                        Thread.sleep(500);

                        exportDiv.click(new ElementHandle.ClickOptions().setForce(true));
                        //exportDiv.evaluate("element => element.click()");

                        ElementHandle pdfButton = downloadPage.waitForSelector(
                        "//button/span[contains(text(),'PDF')]", 
                            new Page.WaitForSelectorOptions().setTimeout(5000)
                        );

                        if (pdfButton != null)
                        {
                            pdfButton.click();

                            ElementHandle downloadBtn = downloadPage.waitForSelector(
                                "//button[contains(text(),'Download')]", 
                            new Page.WaitForSelectorOptions().setTimeout(5000)
                            );

                            if(downloadBtn != null)
                            {

                                // 等待下载完成,英文版
                                Download download = downloadPage.waitForDownload(() -> {
                                    downloadBtn.click(); // 替换为实际的下载按钮选择器
                                });
                    
                                    // 指定保存路径
                                String downloadPath = DownloadDir + item.channelName + "_" + item.title + ".pdf"; // 指定完整路径和文件名
                                download.saveAs(Paths.get(downloadPath));

                                 // 获取下载信息 
                                System.out.println("下载URL: " + download.url());
                                System.out.println("保存路径: " + downloadPath);


                                if (needTranslateCN)
                                {
                                    try
                                    {
                                        ElementHandle langBtn = downloadPage.waitForSelector(
                                            "//button[contains(text(),'Original')]", 
                                            new Page.WaitForSelectorOptions().setTimeout(5000)
                                        );

                                        if (langBtn != null)
                                        {
                                            langBtn.click();

                                            ElementHandle cnBtn = downloadPage.querySelector(
                                                "//button[span[contains(text(),'简体中文')] and span[contains(text(),'Select')]]"
                                            );
                                            
                                            boolean isCnTranslated = false;

                                            if (cnBtn != null)
                                            {
                                                cnBtn.click();
                                                isCnTranslated = true;
                                            }
                                            else
                                            {
                                                cnBtn = downloadPage.querySelector(
                                                    "//button/span[contains(text(),'简体中文')]"
                                                );

                                                if (cnBtn != null)
                                                {
                                                    cnBtn.click();
                                                }   

                                                try
                                                {
                                                    cnBtn = downloadPage.waitForSelector(
                                                    "//button[span[contains(text(),'简体中文')] and span[contains(text(),'Select')]]",
                                                        new Page.WaitForSelectorOptions().setTimeout(5*60*1000));    
                                                        
                                                    cnBtn.click();
                                                    isCnTranslated = true;
                                                }
                                                catch (Exception ex)
                                                {
                                                    System.out.println("点击简体中文按钮失败: " + ex.getMessage());
                                                    ex.printStackTrace();
                                                }
                                            }
 
                                            if (isCnTranslated)
                                            {
                                                ElementHandle newDownloadBtn = downloadPage.waitForSelector(
                                                        "//button[contains(text(),'Download')]", 
                                                    new Page.WaitForSelectorOptions().setTimeout(5000)
                                                    );

                                                download = downloadPage.waitForDownload(() -> {
                                                    newDownloadBtn.click(); // 替换为实际的下载按钮选择器
                                                });

                                                downloadPath = DownloadDir + "CN/CN_" + item.channelName + "_" + item.title + ".pdf"; // 指定完整路径和文件名
                                                download.saveAs(Paths.get(downloadPath));

                                                System.out.println("中文保存路径: " + downloadPath);
                                            }
                                        }
                                    }
                                    catch (Exception ex)
                                    {
                                        System.out.println("切换语言按钮点击失败: " + ex.getMessage());
                                        ex.printStackTrace();
                                    }
                                }//不需要下载中文
                                       
                            }

                        }

                    }
                }
                catch(Exception ex)
                {
                    ex.printStackTrace();
                }
                finally{
                    downloadPage.close();
                }  

                System.out.println(item.channelName +  "," + item.title +  "," + item.linkString+ " is processed");
            }
            else
            {
                System.out.println(item.channelName +  "," + item.title +  "," + item.linkString+ " is not processed");
            }
                
        }//end for loop
    }

	private void parseLinkChild(ElementHandle element,PodCastItem item) {
        try {
            // 方法1: 使用 querySelector 查找直接子元素中的第一个a标签
            ElementHandle firstLink = element.querySelector(":scope > a, :scope a:first-child");
            
            if (firstLink == null) {
                // 如果不是直接子元素，可能是子孙元素中的第一个
                firstLink = element.querySelector("a");
            }
            
            if (firstLink != null) {
                String href = firstLink.getAttribute("href");
                String linkText = (String) firstLink.evaluate("element => element.textContent.trim()");
                
				item.title = linkText;
				item.linkString = href;
                
                // 获取更多链接信息
                Object linkDetails = firstLink.evaluate("""
                    link => {
                        return {
                            fullUrl: link.href,
                            protocol: link.protocol,
                            hostname: link.hostname,
                            pathname: link.pathname,
                            search: link.search,
                            hash: link.hash,
                            target: link.target,
                            rel: link.rel,
                            isExternal: link.hostname !== window.location.hostname
                        };
                    }
                """);
            } else {
                System.out.println("未找到超链接子节点");
            }
            
        } catch (Exception e) {
            System.out.println("解析超链接时出错: " + e.getMessage());
        }
    }

	private void parseChannelChild(ElementHandle element,PodCastItem item) {
        try 
		{
			ElementHandle divChild = element.querySelector("//img[contains(@alt,'Podcast cover')]/../span");

            if (divChild != null) {
                String spanText = (String) divChild.evaluate("span => span.textContent.trim()");
                //System.out.println("channel name: " + spanText);

					item.channelName = spanText;
            }
            
            
        } catch (Exception e) {
            System.out.println("解析子节点时出错: " + e.getMessage());
        }
	}

    private void processDownloadedFiles(int count,ModelType modelType,boolean needGenerateImage) {

        int processedCount = 0;

        try {
            // 确保下载目录存在
            java.io.File dir = new java.io.File(DownloadDir);
            java.io.File outputDir = new java.io.File(DownloadDir+"summary/");
            if (!dir.exists() || !dir.isDirectory()) {
                System.out.println("下载目录不存在: " + DownloadDir);
                return;
            }

            if (!outputDir.exists() || !outputDir.isDirectory()) {
                outputDir.mkdirs();
            }
            
            // 遍历目录中的 PDF 文件
            java.io.File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));
            if (files == null || files.length == 0) {
                System.out.println("下载目录中没有 PDF 文件");
                return;
            }
            
            System.out.println("找到 " + files.length + " 个 PDF 文件，开始生成中文摘要...");
            
            for (java.io.File pdfFile : files) {
                if (processedCount >= count) {
                    break;
                }
                
                processedCount++;
                
                String pdfFileName = pdfFile.getName();
                System.out.println("正在处理文件: " + pdfFileName);
                
                // 构建输出文件名：在 .pdf 前添加 .cn 后缀
                String outputFileName = pdfFileName.replace(".pdf", "_cn_summary.txt");
                String outputFilePath = outputDir.getPath() + "/" + outputFileName;
                
                // 检查摘要文件是否已存在
                java.io.File outputFile = new java.io.File(outputFilePath);
                if (outputFile.exists()) {
                    System.out.println("摘要文件已存在，跳过: " + outputFileName);
                }
                else
                {
                    try {
                    // 调用 Gemini API 生成中文摘要
                        String summary = null;
                        
                        if(modelType == ModelType.GEMINI)
                        {
                            summary = PodCastUtil.generateSummaryWithGemini(pdfFile);
                        }
                        else if(modelType == ModelType.DEEPSEEK)
                        {
                            summary = PodCastUtil.generateSummaryWithDeepSeek(pdfFile);
                        }
                        
                        // 保存摘要到文件
                        if (summary != null && !summary.isEmpty()) {
                            try (java.io.FileWriter writer = new java.io.FileWriter(outputFilePath)) {
                                writer.write(summary);
                            }
                            System.out.println("成功生成摘要文件: " + outputFileName);
                        } else {
                            System.out.println("生成摘要失败，跳过: " + pdfFileName);
                        }

                        // 添加适当的延迟，避免 API 调用过于频繁
                        Thread.sleep(1000);
                        
                    } catch (Exception e) {
                        System.out.println("处理文件时出错 " + pdfFileName + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }

                if(needGenerateImage)
                {
                    // 调用 Gemini API 生成图片摘要
                    PodCastUtil.generateImageWithGemini(outputFile.getAbsolutePath(),DownloadDir+"image/");
                }

            }
            
            System.out.println("所有文件处理完成");
            
        } catch (Exception e) {
            System.out.println("处理下载文件时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
    

}