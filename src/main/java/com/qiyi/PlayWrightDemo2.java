package com.qiyi;



import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.function.Consumer;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;

public class PlayWrightDemo2 {

    public static void main(String[] args)
    {
        System.out.println( "start playwrightdemo2!" );

        PlayWrightDemo2 playwrightdemo2 = new PlayWrightDemo2();

        try {
            //playwrightdemo2.testEvents();
			//playwrightdemo2.testRunJS();
            playwrightdemo2.testContext();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

        System.out.println( "end playwrightdemo2!" );
    }

    public void testEvents()
    {
        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            Page page = browser.newPage();   
         

            page.onRequest(request -> System.out.println("Request sent: " + request.url()));
            Consumer<Request> listener = request -> System.out.println("Request finished: " + request.url());
            page.onRequestFinished(listener);
            page.navigate("https://baidu.com");

            // Remove previously added listener, each on* method has corresponding off*
            page.offRequestFinished(listener);
            page.navigate("https://163.com");

            page.waitForTimeout(3000);

           
            page.waitForTimeout(5000);
        }
        
    }

    public void testRunJS()
    {
        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            Page page = browser.newPage();      

            page.onConsoleMessage(msg -> {
                System.out.println("Console: " + msg.text());
            });

            page.addInitScript(Paths.get("src/main/resources/mocks/preload.js"));

            page.navigate("https://1688.com");

            String href = (String) page.evaluate("document.location.href");

            int status = (int) page.evaluate("async () => {\n" 
                + "  const response = await fetch(location.href);\n" +
                "  return response.status;\n" +
                "}");

            page.evaluate("array => array.length", Arrays.asList(1, 2, 3));

            System.out.println(href + status);

        }
        
    }

    public void testContext()
    {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));

            Page page;

            Path stateFilePath = Paths.get("state.json");

            if (Files.exists(stateFilePath)) {
                // 文件存在，继续使用
                // Create a new context with the saved storage state.
                BrowserContext context = browser.newContext(
                new Browser.NewContextOptions().setStorageStatePath(Paths.get("state.json")));
    
                page = context.newPage();
            } else {
                // 文件不存在，处理逻辑
                page = browser.newPage();

                System.out.println("state.json 文件不存在");
            }

            
            page.navigate("https://1688.com");

            
            // 等待登录按钮出现，最多等待 3 分钟
            page.waitForSelector("//div[contains(@class,'loginAvatar')]/img", new Page.WaitForSelectorOptions().setTimeout(180000));

            browser.contexts().get(0).storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get("state.json")));

           
            // create a locator
            Locator searchInputLocator = page.locator("//div[@id='pc-home2024-search-tab']//div[@class='ali-search-box']//input[@id='alisearch-input']");

            searchInputLocator.fill("袜子");

            Locator searchLocator = page.locator("//div[@id='pc-home2024-search-tab']//div[@class='ali-search-box']//div[contains(text(),'搜')]");

            // Click the get started link.
            searchLocator.click(new Locator.ClickOptions().setForce(true));

            Page newPage = page.waitForPopup(() -> {
                // 点击操作会触发新标签页打开
            });

            System.out.println("browser size: " +  browser.contexts().size());
            

            newPage.waitForTimeout(3000);
        }
            
            
    }

}
