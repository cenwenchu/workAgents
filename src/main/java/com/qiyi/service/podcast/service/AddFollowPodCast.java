package com.qiyi.service.podcast.service;


import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.qiyi.config.AppConfig;
import com.qiyi.util.PodCastUtil;
import com.qiyi.util.AppLog;

public class AddFollowPodCast {

    Browser browser = null;

    String  searchUrl = "https://podwise.ai/dashboard/search";

    public AddFollowPodCast(Browser browser) {
        this.browser = browser;
    }

    /**
     * 根据播客名称数组，批量添加（关注）播客
     * 
     * @param podCastNames 播客名称数组，包含需要搜索和添加的播客名称
     */
    public void addPodCast(String[] podCastNames) {

       BrowserContext context = browser.contexts().isEmpty() ? 
            browser.newContext() : browser.contexts().get(0);
        
        try (Page page = context.newPage()) {
            page.navigate(searchUrl);
            
            page.waitForLoadState(
                    LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
            );
          
           if (!PodCastUtil.isLoggedIn(page)) {
            AppLog.info("用户未登录，请手动登录后继续");
            // 等待用户手动登录
            PodCastUtil.waitForManualLogin(page);
            }
            
            page.waitForLoadState(
                    LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
            );


            for(String podCastName : podCastNames) 
            {

                // 检查page url 是否为 searchUrl
                if (!page.url().equals(searchUrl)) {
                    page.navigate(searchUrl);
                    page.waitForLoadState(
                            LoadState.DOMCONTENTLOADED,
                            new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                    );
                }

                // 使用正确的等待方式
                ElementHandle podCastTab = page.waitForSelector(
                    "button[role='tab']:has-text('Podcasts')", 
                    new Page.WaitForSelectorOptions().setTimeout(10000)
                );
                
                if (podCastTab != null) {
                    String dataState = podCastTab.getAttribute("data-state");
                    if (!"active".equals(dataState)) {
                        podCastTab.click();
                    }
                }

                try{
                    ElementHandle searchInput = page.waitForSelector(
                        "//input[contains(@placeholder,'Name')]", 
                        new Page.WaitForSelectorOptions().setTimeout(10000)
                    );

                    searchInput.focus();
                    searchInput.fill(podCastName);
                    searchInput.press("Enter");

                    // 等待搜索结果加载完成
                    page.waitForSelector(
                        "//div//span[contains(text(),'podcast') and contains(text(),'found')]",
                        new Page.WaitForSelectorOptions().setTimeout(10000)
                    );

                    // 点击第一个搜索结果
                    ElementHandle firstResult = page.querySelector(
                        "//div[.//a[contains(@href,'/dashboard/podcasts')] and .//img[contains(@alt,'Podcast cover')]]"
                    );

                    if (firstResult == null) {
                        firstResult = page.querySelector(
                        "//div/a[contains(@href,'/dashboard/podcasts')]"
                        );
                    }

                    if (firstResult != null) {
                        firstResult.click();

                        // 等待添加按钮加载完成
                        page.waitForSelector(
                            "//button/span[contains(text(),'Pull Latest Episodes')]",
                            new Page.WaitForSelectorOptions().setTimeout(10000)
                        );


                        String selector1 = String.format(
                            "div h1:has-text('%s')", 
                                podCastName.replace("'", "\\'"));

                        ElementHandle podCastNameLabel = page.querySelector(selector1);


                        if (podCastNameLabel != null) {
                            AppLog.info("成功找到播客：" + podCastName);

                            ElementHandle followButton = page.querySelector(
                                "//button[contains(text(),'Follow')]"
                            );

                            if (followButton != null) {
                                followButton.click();

                                page.waitForSelector(
                                "//button/span[contains(text(),'Followed')]",
                                new Page.WaitForSelectorOptions().setTimeout(10000)
                                );

                                AppLog.info("成功关注播客：" + podCastName);
                            }
                            else
                            {
                                AppLog.info("播客已关注：" + podCastName);
                            } 
                        }
                        else
                        {
                            AppLog.info("未找到播客：" + podCastName);
                        }   
                        
                        ElementHandle backButton = page.querySelector(
                                "//button[contains(@title,'Back')]"
                            );

                        if (backButton != null) {
                            backButton.click();
                            // 等待返回后页面加载完成
                            page.waitForLoadState(
                                    LoadState.DOMCONTENTLOADED,
                                    new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                            );
                        }
                    }
                    else
                    {
                        AppLog.info("未找到播客：" + podCastName);
                    }
                }
                catch(Exception ex)
                {
                    AppLog.info("添加播客失败：" + podCastName);
                    AppLog.error(ex);
                }
                

            }// end for each podCastName  
        }
    }
    

    
}
