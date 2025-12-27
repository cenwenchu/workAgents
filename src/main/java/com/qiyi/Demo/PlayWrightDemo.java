package com.qiyi.Demo;

import java.util.List;
import java.util.regex.Pattern;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;

/*
 * Action list
 * Locator.check()
Locator.click()
Locator.dblclick()
Locator.setChecked()
Locator.tap()
Locator.uncheck()
Locator.hover()
Locator.dragTo()
Locator.screenshot()
Locator.fill()
Locator.clear()
Locator.selectOption()
Locator.selectText()
Locator.scrollIntoViewIfNeeded()
Locator.blur()
Locator.dispatchEvent()
Locator.focus()
Locator.press()
Locator.pressSequentially()
Locator.setInputFiles()
 * 
 */


/**
 * Hello world!
 *
 */
public class PlayWrightDemo 
{
    public static void main( String[] args )
    {
        
        
        System.out.println( "start playwrightdemo!" );

        PlayWrightDemo playwrightdemo = new PlayWrightDemo();

        try {
			playwrightdemo.search1688Click();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

        System.out.println( "end playwrightdemo!" );
        
    }

    public void search1688Click() throws InterruptedException
    {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            Page page = browser.newPage();
            page.navigate("https://1688.com");

            // Expect a title "to contain" a substring.
            assertThat(page).hasTitle(Pattern.compile("1688")); 

            // create a locator
            Locator searchInputLocator = page.locator("//div[@id='pc-home2024-search-tab']//div[@class='ali-search-box']//input[@id='alisearch-input']");

            searchInputLocator.fill("袜子");

            Locator searchLocator = page.locator("//div[@id='pc-home2024-search-tab']//div[@class='ali-search-box']//div[contains(text(),'搜')]");

            // Click the get started link.
            searchLocator.click(new Locator.ClickOptions().setForce(true));

            Page newPage = page.waitForPopup(() -> {
                // 点击操作会触发新标签页打开
            });
            
            // 确保新标签页完全加载
            newPage.waitForLoadState(LoadState.NETWORKIDLE);

            // 获取所有打开的页面
            List<Page> pages = page.context().pages();

            // 假设新页面的 URL 包含特定关键字
            newPage = null;
            for (Page p : pages) {
                if (p.url().contains("offer_search")) { // 根据 URL 判断
                    newPage = p;
                    break;
                }
            }
            
            if(newPage != null)
            {
                newPage.waitForTimeout(5000);

                Locator pageIndexLocator = newPage.locator("//div[@class='space-common-offerlist']");
                pageIndexLocator.hover();
                newPage.mouse().wheel(0, 50);
                
                newPage.waitForTimeout(1000);

                newPage.locator("//div[@class='pagination-container']").scrollIntoViewIfNeeded();
               
                newPage.waitForTimeout(3000);
            }
            
        }
    }

    public void shouldNotHaveAutomaticallyDetectableAccessibilityIssues() throws Exception {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
        
            page.navigate("https://1688.com/"); // 3
        
            AxeResults accessibilityScanResults = new AxeBuilder(page).analyze(); // 4
        
            
            System.out.println(accessibilityScanResults.getViolations().size());

        }
        
      }

    public void tryOpenBrowser()
    {
        try (Playwright playwright = Playwright.create()) {
            //Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false).setSlowMo(50));   
            Browser browser = playwright.chromium().launch();          
            Page page = browser.newPage();
            page.navigate("http://playwright.dev");
            System.out.println(page.title());

            page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("/Users/cenwenchu/Desktop/Demo/example.png")));

        }
    }

    public void tryClickLink()
    {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            Page page = browser.newPage();
            page.navigate("http://playwright.dev");

            // Expect a title "to contain" a substring.
            assertThat(page).hasTitle(Pattern.compile("Playwright")); 

            // create a locator
            Locator getStarted = page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Get Started"));

            // Expect an attribute "to be strictly equal" to the value.
            assertThat(getStarted).hasAttribute("href", "/docs/intro");

            // Click the get started link.
            getStarted.click();

            // Expects page to have a heading with the name of Installation.
            assertThat(page.getByRole(AriaRole.HEADING,
               new Page.GetByRoleOptions().setName("Installation"))).isVisible();
        }
    }


}
