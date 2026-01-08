package com.qiyi.android;

import java.net.MalformedURLException;
import java.util.List;

import org.openqa.selenium.WebElement;
import io.appium.java_client.android.AndroidDriver;

/**
 * 基础处理器的接口类
 * @author cenwenchu
 *
 */
public interface IMobileRPAProcessor {
	
	public enum Direction
	{
		UP,DOWN,LEFT,RIGHT
	}
	
	public enum SortBy
	{
		DEFAULT,SALE,TIME
	}

	public AndroidDriver getDriver();
	
	public void setDriver(AndroidDriver driver);
	
	public String getAppPackage();

	public void setAppPackage(String appPackage);

	public String getAppActivity();

	public void setAppActivity(String appActivity);
	
	
	/**
	 * 初始化Driver的会话，用于和客户端机器互动
	 * @param 设备id（getDeviceList 可以获取）
	 * @param appiumServerUrl
	 * @return
	 * @throws MalformedURLException
	 */
	public void initDriver(String udid,String appiumServerUrl) throws MalformedURLException;
	
	/**
     * 结束会话，释放client机器
     * @param driver
     */
	public void quitDriver();
	
	/**
     * 拖动
     * @param driver
     * @param 以哪个屏幕的对象作为拖动的起点
     * @param 拖动的宽度，主要是根据elemnt的高和宽来乘以这个比例。例如高 1000pix，这个数字如果是0.3，则拖动 1000 * 0.3 = 300 pix
     * @param x的偏移量
     * @param y的偏移量
     * @param 拖动方向
     */
    public void drag(WebElement element,double percent,Direction direction,int offsetX,int offsetY);
    
    /**
	 * 拖动
	 * @param driver
	 * @param 以哪个屏幕的位置作为拖动的起点
	 * @param 拖动的宽度，主要是根据elemnt的高和宽来乘以这个比例。例如高 1000pix，这个数字如果是0.3，则拖动 1000 * 0.3 = 300 pix
	 * @param x的偏移量
     * @param y的偏移量
     * @param 拖动方向
	 */
	public void drag(String bounds,double percent,Direction direction,int offsetX,int offsetY);
	
	/**
     * 向上或者向下滚动屏幕（暂时还没支持左右，左右请用drag）
     * @param percent 拖动全屏幕的比例
     * @param direction 拖动方向
     * @param beginYPosition 拖动开始的的屏幕位置，0< <1;
     */
	public void scroll(double percent,Direction direction,double beginYPosition);
    
    /**
	 * 找到android的elments 列表 通过xpath
	 * @param xpath
	 * @param 是否需要等待，主要用于某些界面需要渲染时间，当找不到对象，可以循环等待waitSeconds秒来获取对象（每一秒会检查一次，直至waitSeconds用完）
	 * @return
	 * @throws Exception
	 */
	public List<WebElement> findElementsAndWait(String xpath,int waitSeconds) throws Exception;
	
	/**
	 * 通过 Element来找子的elments 列表 通过xpath
	 * @param element 这个对象替代掉了根节点，他就是最高节点，高于这个节点的对象是无法找到的，就算用/..
	 * @param xpath
	 * @param 是否需要等待，主要用于某些界面需要渲染时间，当找不到对象，可以循环等待waitSeconds秒来获取对象（每一秒会检查一次，直至waitSeconds用完）
	 * @return
	 * @throws Exception
	 */
	public List<WebElement> findElementsByElementAndWait(WebElement element,String xpath,int waitSeconds) throws Exception;
	
	/**
	 * 通过 Element来找子的单个element 通过xpath
	 * @param element 这个对象替代掉了根节点，他就是最高节点，高于这个节点的对象是无法找到的，就算用/..
	 * @param xpath
	 * @param 是否需要等待，主要用于某些界面需要渲染时间，当找不到对象，可以循环等待waitSeconds秒来获取对象（每一秒会检查一次，直至waitSeconds用完）
	 * @return
	 * @throws Exception
	 */
	public WebElement findElementByElementAndWait(WebElement element,String xpath,int waitSeconds) throws Exception;
	
	/**
	 * 找子的单个element 通过xpath
	 * @param xpath
	 * @param 是否需要等待，主要用于某些界面需要渲染时间，当找不到对象，可以循环等待waitSeconds秒来获取对象（每一秒会检查一次，直至waitSeconds用完）
	 * @return
	 * @throws Exception
	 */
	public WebElement findElementAndWait(String xpath,int waitSeconds) throws Exception;
	
	/**
     * 通过xpath 找到单个对象，如果找到的话，并且点击一下
     * @param xpath
     * @param 是否需要等待，主要用于某些界面需要渲染时间，当找不到对象，可以循环等待waitSeconds秒来获取对象（每一秒会检查一次，直至waitSeconds用完）
     * @return
     * @throws Exception
     */
    public WebElement findElementAndWaitToClick(String xpath,int waitSeconds) throws Exception;
    
    
    /**
     * 寻找一个对象通过xpath，然后如果找不到，可以向上或者向下拖拉屏幕
     * @param xpath
     * @param waitSeconds 是否需要等待，主要用于某些界面需要渲染时间，当找不到对象，可以循环等待waitSeconds秒来获取对象（每一秒会检查一次，直至waitSeconds用完）
     * @param direction 向上或者向下scoll屏幕
     * @param percent 拖动的高度，主要是根据elemnt的高和宽来乘以这个比例。例如高 1000pix，这个数字如果是0.3，则拖动 1000 * 0.3 = 300 pix
     * @param scrollTimes 最多拖动几次结束
     * @param beginYPosition 拖动开始的位置，默认填写0，如果要指定拖动开始的Y的位置，请设置 >0 and <1 的数量，表示从屏幕百分之多少开始拖动,0默认从屏幕90%开始。
     * @return
     * @throws Exception
     */
    public WebElement findElementAndScroll(String xpath,int waitSeconds,Direction direction,double percent,int scrollTimes,double beginYPosition) throws Exception;
    
    
    /**
     * 寻找多个对象通过xpath，然后如果找不到，可以向上或者向下拖拉屏幕
     * @param xpath
     * @param waitSeconds 是否需要等待，主要用于某些界面需要渲染时间，当找不到对象，可以循环等待waitSeconds秒来获取对象（每一秒会检查一次，直至waitSeconds用完）
     * @param direction 向上或者向下scoll屏幕
     * @param percent 拖动的高度，主要是根据elemnt的高和宽来乘以这个比例。例如高 1000pix，这个数字如果是0.3，则拖动 1000 * 0.3 = 300 pix
     * @param scrollTimes 最多拖动几次结束
     * @param beginYPosition 拖动开始的位置，默认填写0，如果要指定拖动开始的Y的位置，请设置 >0 and <1 的数量，表示从屏幕百分之多少开始拖动,0默认从屏幕90%开始。
     * @return
     * @throws Exception
     */
    public List<WebElement> findElementsAndScroll(String xpath,int waitCounts,Direction direction,double percent,int scrollTimes,double beginYPosition) throws Exception;
    
    /**
     * 回到应用首页
     * @param 应用首页的xpath
     * @throws InterruptedException
     */
    public void backToMainPage(String mainPageXpath) throws InterruptedException;
    
    
	
}
