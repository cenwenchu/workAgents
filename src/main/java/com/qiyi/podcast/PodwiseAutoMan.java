/**
 * 
 */
package com.qiyi.podcast;


import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;

//先要运行这个启动可信任浏览器
//nohup /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --remote-debugging-port=9222 --user-data-dir="/tmp/chrome-debug" > /tmp/chrome-debug.log 2>&1 &

//lsof -ti:9222 | xargs kill -9  杀死进程

/**
 * 
 */
public class PodwiseAutoMan {

    Playwright playwright = null;
    Browser browser = null;

    //两个模型枚举
    public enum ModelType {
        ALL,
        DEEPSEEK,
        GEMINI
    }


	public static void main(String[] args) {

        
        PodwiseAutoMan autoMan = new PodwiseAutoMan();

        // 执行自动化操作
        autoMan.connectAndAutomate();

        //下载关注的播客节目的文本文件
         DownLoadPodCastTask downLoadPodCastTask = new DownLoadPodCastTask(autoMan.browser,"/Users/cenwenchu/Desktop/podcastItems/");
         downLoadPodCastTask.performAutomationDownloadTasks(10,5,true);

         //对于下载的文件，通过调用gemini的api来做翻译和中文摘要
         downLoadPodCastTask.processDownloadedFiles(2,ModelType.ALL,false,true);



        // 从本地文件中读取播客列表,并且搜索和关注这些播客
        //AddPodCastTask addPodCastTask = new AddPodCastTask(autoMan.browser);
        //String[] podCastNames = PodCastUtil.readPodCastNamesFromFile("/Users/cenwenchu/Desktop/podcasts.txt");
        //addPodCastTask.addPodCast(podCastNames);

         
         autoMan.disconnectBrowser();

	}

    public void connectAndAutomate() {
        
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
                 
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public void disconnectBrowser() {
        if (playwright != null) {
                playwright.close();
            }

        PodCastUtil.killChromeProcess(9222);
    }

}