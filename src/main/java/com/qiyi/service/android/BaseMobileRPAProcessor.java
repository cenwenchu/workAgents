package com.qiyi.service.android;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebElement;

import com.google.common.collect.ImmutableMap;
import com.qiyi.util.AppLog;
import io.appium.java_client.AppiumBy;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.android.nativekey.KeyEvent;
import io.appium.java_client.android.options.UiAutomator2Options;

/**
 * 
 * 处理器的抽象类
 * @author cenwenchu
 *
 */
public abstract class BaseMobileRPAProcessor implements IMobileRPAProcessor {
	static int WAIT_INTERVAL = 300;
	
	protected AndroidDriver driver;
	protected String appPackage;
	protected String appActivity;	

	public String getAppPackage() {
		return appPackage;
	}

	public void setAppPackage(String appPackage) {
		this.appPackage = appPackage;
	}

	public String getAppActivity() {
		return appActivity;
	}

	public void setAppActivity(String appActivity) {
		this.appActivity = appActivity;
	}

	@Override
	public AndroidDriver getDriver() {
		// TODO Auto-generated method stub
		return driver;
	}

	@Override
	public void setDriver(AndroidDriver driver) {
		// TODO Auto-generated method stub
		this.driver = driver;
	}

	@Override
	public void initDriver(String udid, String appiumServerUrl)
			throws MalformedURLException {
		
		UiAutomator2Options options = new UiAutomator2Options()
			    .setUdid(udid)
			    .setNoReset(true)
			    .setLocaleScript("zh-Hans-CN")	
			    .setAppPackage(appPackage)
			    .setAppActivity(appActivity);
    	
    	driver = new AndroidDriver(
			    new URL(appiumServerUrl), options);
			driver.setSetting("allowInvisibleElements", true);
			driver.activateApp(appPackage);

	}
	
	@Override
	public void quitDriver() {
		
		if (driver !=  null)
    	{
    		driver.quit();
    	}
	}
	
	/**
     * 拖动元素
     * @param element 以哪个屏幕的对象作为拖动的起点
     * @param percent 拖动的宽度，主要是根据element的高和宽来乘以这个比例。例如高 1000pix，这个数字如果是0.3，则拖动 1000 * 0.3 = 300 pix
     * @param direction 拖动方向
     * @param offsetX x的偏移量
     * @param offsetY y的偏移量
     */
    public void drag(WebElement element,double percent,Direction direction,int offsetX,int offsetY)
    {
    	drag(element.getDomAttribute("bounds"),percent,direction,offsetX,offsetY);
    }
    
	/**
	 * 拖动区域
	 * @param bounds 以哪个屏幕的位置作为拖动的起点，格式如 "[0,0][1080,1920]"
	 * @param percent 拖动的宽度，主要是根据element的高和宽来乘以这个比例。例如高 1000pix，这个数字如果是0.3，则拖动 1000 * 0.3 = 300 pix
	 * @param direction 拖动方向
	 * @param offsetX x的偏移量
     * @param offsetY y的偏移量
	 */
	public void drag(String bounds,double percent,Direction direction,int offsetX,int offsetY)
    {
		
    	bounds = bounds.replace("[", "");
		bounds = bounds.replace("]", ",");

    	int left = Integer.valueOf(bounds.split(",")[0]);
    	int top = Integer.valueOf(bounds.split(",")[1]);
    	int right = Integer.valueOf(bounds.split(",")[2]);
    	int down = Integer.valueOf(bounds.split(",")[3]);
    	
    	double startX = 0;
    	double startY = 0;
    	double endX = 0;
    	double endY = 0;
    	int speed = 700;
    	
    	switch (direction) 
    	{
    		case UP:
    			startX = left+offsetX;
    			startY = down-offsetY;
    			endX = left+offsetX;
    			endY = down-offsetY-(down-top)*percent > 0 ? down-offsetY-(down-top)*percent:0;
    			break;
    			
    		case DOWN:
    			startX = left+offsetX;
    			startY = top+offsetY;
    			endX = left+offsetX;
    			endY = top+offsetY+(down-top)*percent;

    			break;
    			
    		case LEFT:
    			startX = right-offsetX;
        		startY = top+offsetY+(down-top)/2;
        		endX = right-offsetX-(right-left)*percent >0 ? right-offsetX-(right-left)*percent:0;
        		endY = top+offsetY+(down-top)/2;
        		speed = 0;
    			break;
    			
    		case RIGHT:
    			startX = left+offsetX;
    			startY = top+offsetY+(down-top)/2;
    			endX = left+offsetX+(right-left)*percent;
    			endY = top+offsetY+(down-top)/2;
    			break;
    	
    	}
    	
    	if (speed > 0)
    		driver.executeScript("mobile: dragGesture", ImmutableMap.of(
			    "startX",startX,
			    "startY",startY,
			    "endX", endX,
			    "endY", endY,
			    "speed",speed
			));
    	else
    		driver.executeScript("mobile: dragGesture", ImmutableMap.of(
    			    "startX",startX,
    			    "startY",startY,
    			    "endX", endX,
    			    "endY", endY
    			));
    	
    	AppLog.info("drag , startX:" + startX + ",startY:" + startY + ",endX:" + endX + ",endY:" + endY);
    }
	

	@Override
    public void scroll(double percent,Direction direction,double beginYPosition)
    {
    	double _beginYPosition = 0.9;
    	
    	if (beginYPosition < 1 && beginYPosition > 0)
    		_beginYPosition = beginYPosition;
    		
    	
    	// 获取设备屏幕大小
		Dimension screenSize = driver.manage().window().getSize();
		int screenWidth = screenSize.getWidth();
		int screenHeight = screenSize.getHeight();
    	
		// 定义起始点和终止点
		int startX = screenWidth / 2;
		int startY = screenHeight;
		int endX = screenWidth / 2;
		int endY = screenHeight;
		
		if (direction == Direction.UP)
		{
			startY = (int) (screenHeight * _beginYPosition);
			if (_beginYPosition - percent >= 0)
				endY = (int) (screenHeight * (_beginYPosition - percent));
			else
				endY = (int) (screenHeight * 0.3);
		}
		
		if (direction == Direction.DOWN)
		{
			endY = (int) (screenHeight * _beginYPosition);
			if (_beginYPosition - percent >= 0)
				startY = (int) (screenHeight * (_beginYPosition - percent));
			else
				startY = (int) (screenHeight * 0.3);
		}
	
		driver.executeScript("mobile: dragGesture", ImmutableMap.of(
			    "startX",startX,
			    "startY",startY,
			    "endX", endX,
			    "endY", endY,
			    "speed",700
			));
		
		AppLog.info("scroll , startX:" + startX + ",startY:" + startY + ",endX:" + endX + ",endY:" + endY);
    }
	
	
    
    /**
	 * 找到android的elments 列表 通过xpath
	 * @param xpath
	 * @param waitCounts 是否需要等待，主要用于某些界面需要渲染时间，当找不到对象，可以循环等待waitCounts单位来获取对象（每x微秒会检查一次，直至waitCounts用完）
	 * @return
	 * @throws Exception
	 */
	public List<WebElement> findElementsAndWait(String xpath,int waitCounts) throws Exception
    {
    	List<WebElement> el = null;
    	
    	int waitTimes = waitCounts;
    	Exception e = null;
    	
    	while (waitTimes >= 0)
    	{
    		try
    		{
    			el = driver.findElements(AppiumBy.xpath(xpath)); 
    		}
    		catch(Exception ex)
    		{
    			e = ex;
    		}
    		
    		if (el == null || (el !=  null && el.size() == 0))
    		{
    			waitTimes -= 1;
    			Thread.sleep(WAIT_INTERVAL);
    		}
    		else
    		{
    			break;
    		}
    	}
    	
    	if (el == null && e != null)
    		throw e;
	    
	    return el;
    	
    }
	
	/**
	 * 通过 Element来找子的elments 列表 通过xpath
	 * @param element 这个对象替代掉了根节点，他就是最高节点，高于这个节点的对象是无法找到的，就算用/..
	 * @param xpath
	 * @param waitCounts 是否需要等待，主要用于某些界面需要渲染时间，当找不到对象，可以循环等待waitCounts单位来获取对象（每x微秒会检查一次，直至waitCounts用完）
	 * @return
	 * @throws Exception
	 */
	public List<WebElement> findElementsByElementAndWait(WebElement element,String xpath,int waitCounts) throws Exception
	{
		List<WebElement> el = null;
		
		int waitTimes = waitCounts;
    	Exception e = null;
    	
    	while (waitTimes >= 0)
    	{
    		try
    		{
    			el = element.findElements(AppiumBy.xpath(xpath)); 
    		}
    		catch(Exception ex)
    		{
    			e = ex;
    		}
    		
    		if (el == null || (el !=  null && el.size() == 0))
    		{
    			waitTimes -= 1;
    			Thread.sleep(WAIT_INTERVAL);
    		}
    		else
    		{
    			break;
    		}
    	}
    	
    	if (el == null && e != null)
    		throw e;
	    
	    return el;
	}
	
	/**
	 * 通过 Element来找子的单个element 通过xpath
	 * @param element 这个对象替代掉了根节点，他就是最高节点，高于这个节点的对象是无法找到的，就算用/..
	 * @param xpath
	 * @param waitCounts 是否需要等待，主要用于某些界面需要渲染时间，当找不到对象，可以循环等待waitCounts单位来获取对象（每x微秒会检查一次，直至waitCounts用完）
	 * @return
	 * @throws Exception
	 */
	public WebElement findElementByElementAndWait(WebElement element,String xpath,int waitCounts) throws Exception
    {
    	WebElement el = null;
    	
    	int waitTimes = waitCounts;
    	Exception e = null;
    	
    	while (waitTimes >= 0)
    	{
    		try
    		{
    			el = element.findElement(AppiumBy.xpath(xpath)); 
    		}
    		catch(Exception ex)
    		{
    			e = ex;
    		}
    		
    		if (el == null)
    		{
    			waitTimes -= 1;
    			Thread.sleep(WAIT_INTERVAL);
    		}
    		else
    		{
    			break;
    		}
    	}
    	
    	if (el == null && e != null)
    		throw e;
	    
	    return el;
    	
    }
	
	/**
	 * 找子的单个element 通过xpath
	 * @param xpath
	 * @param waitCounts 是否需要等待，主要用于某些界面需要渲染时间，当找不到对象，可以循环等待waitCounts单位来获取对象（每x微秒会检查一次，直至waitCounts用完）
	 * @return
	 * @throws Exception
	 */
	public WebElement findElementAndWait(String xpath,int waitCounts) throws Exception
    {
    	WebElement el = null;
    	
    	int waitTimes = waitCounts;
    	Exception e = null;
    	
    	while (waitTimes >= 0)
    	{
    		try
    		{
    			el = driver.findElement(AppiumBy.xpath(xpath)); 
    		}
    		catch(Exception ex)
    		{
    			e = ex;
    		}
    		
    		if (el == null)
    		{
    			waitTimes -= 1;
    			Thread.sleep(WAIT_INTERVAL);
    		}
    		else
    		{
    			break;
    		}
    	}
    	
    	if (el == null && e != null)
    		throw e;
	    
	    return el;
    	
    }
	
	 /**
     * 通过xpath 找到单个对象，如果找到的话，并且点击一下
     * @param xpath
     * @param waitCounts 是否需要等待，主要用于某些界面需要渲染时间，当找不到对象，可以循环等待waitCounts单位来获取对象（每x微秒会检查一次，直至waitCounts用完）
     * @return
     * @throws Exception
     */
    public WebElement findElementAndWaitToClick(String xpath,int waitCounts) throws Exception
    {
    	WebElement el = findElementAndWait(xpath,waitCounts);
    	
    	el.click();
	    
	    return el;
    }
    
    /**
     * 寻找一个对象通过xpath，然后如果找不到，可以向上或者向下拖拉屏幕
     * @param xpath
     * @param waitCounts 是否需要等待，主要用于某些界面需要渲染时间，当找不到对象，可以循环等待waitCounts单位来获取对象（每x微秒会检查一次，直至waitCounts用完）
     * @param direction 向上或者向下scoll屏幕
     * @param percent 拖动的高度，主要是根据elemnt的高和宽来乘以这个比例。例如高 1000pix，这个数字如果是0.3，则拖动 1000 * 0.3 = 300 pix
     * @param scrollTimes 最多拖动几次结束
     * @param beginYPosition 拖动开始的位置，默认填写0，如果要指定拖动开始的Y的位置，请设置 >0 and <1 的数量，表示从屏幕百分之多少开始拖动,0默认从屏幕90%开始。
     * @return
     * @throws Exception
     */
    public WebElement findElementAndScroll(String xpath,int waitCounts,Direction direction,double percent,int scrollTimes,double beginYPosition) throws Exception
    {
    	List<WebElement> eles;
		int ix = scrollTimes;

		do
		{
			eles = findElementsAndWait(xpath,waitCounts);			
			
			if (eles.size() > 0 && eles.get(0).isDisplayed())
			{
				return eles.get(0);
			}
				
			ix -= 1;
			this.scroll(percent,direction,beginYPosition);
			
			Thread.sleep(100);
		}
		while(ix > 0);
		
		throw new java.lang.RuntimeException("findElementAndWait error ,not find xpath : " + xpath);
    	
    }
    
    public List<WebElement> findElementsAndScroll(String xpath,int waitCounts,Direction direction,double percent,int scrollTimes,double beginYPosition) throws Exception
    {
    	List<WebElement> eles;
		int ix = scrollTimes;

		do
		{
			eles = findElementsAndWait(xpath,waitCounts);			
			
			if (eles.size() > 0 && eles.get(0).isDisplayed())
			{
				return eles;
			}
				
			ix -= 1;
			this.scroll(percent,direction,beginYPosition);
			
			Thread.sleep(100);
		}
		while(ix > 0);
		
		return eles;
    }
    
    /**
     * 回到应用首页
     * @param 应用首页的xpath
     * @throws InterruptedException
     */
    public void backToMainPage(String mainPageXpath) throws InterruptedException
    {
    	boolean isMainPage = false;
		int times = 8;
		while(!isMainPage && times > 0)
		{
			
			try
			{	//检索当前页面是否有发现的bar（底部第三个）来判断是否在首页，并且点击发现。
				findElementAndWaitToClick(mainPageXpath,1);
				
				break;
			}
			catch(Exception ex)
			{}
			
			//如果不存在“发现”，则调用android的会退
			driver.pressKey(new KeyEvent().withKey(AndroidKey.BACK));
			
			Thread.sleep(1500);
			times -= 1;
		}
    }

}
