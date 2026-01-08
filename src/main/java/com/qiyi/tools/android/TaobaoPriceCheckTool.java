package com.qiyi.tools.android;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.android.AndroidDeviceManager;
import com.qiyi.util.DingTalkUtil;
import com.qiyi.util.LLMUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TaobaoPriceCheckTool extends AndroidBaseTool {

    @Override
    public String getName() {
        return "taobao_price_check";
    }

    @Override
    public String getDescription() {
        return "打开淘宝应用，点击闪购，搜索指定商品，并截图分析最低价格。支持参数：product_name（商品名称，默认瑞幸咖啡生椰拿铁）。";
    }

    @Override
    public String execute(JSONObject params, String senderId, List<String> atUserIds) {
        String serial = null;
        try {
            serial = getDeviceSerial(params);
            AndroidDeviceManager adb = AndroidDeviceManager.getInstance();
            List<String> users = new ArrayList<>();
            if (senderId != null) users.add(senderId);

            // 1. 打开淘宝
            DingTalkUtil.sendTextMessageToEmployees(users, "正在打开淘宝...");
            // Use monkey to start the app as it is more robust than specifying activity
            adb.executeShell(serial, "monkey -p com.taobao.taobao -c android.intent.category.LAUNCHER 1");
            
            // Check if app is started
            boolean isAppOpen = false;
            for (int i = 0; i < 10; i++) {
                Thread.sleep(1000);
                String output = adb.executeShell(serial, "dumpsys window | grep mCurrentFocus");
                if (output != null && output.contains("com.taobao.taobao")) {
                    isAppOpen = true;
                    break;
                }
            }
            
            if (!isAppOpen) {
                return reportError(users, "打开淘宝失败，请检查设备状态");
            }
            
            Thread.sleep(2000);

            // 2. Prepare Product Name
            String productName = null;
            if (params != null) {
                productName = params.getString("product_name");
            }
            if (productName == null || productName.trim().isEmpty()) {
                productName = "瑞幸咖啡生椰拿铁";
            }
            
            // 3. Search Process (UI Interaction)
            DingTalkUtil.sendTextMessageToEmployees(users, "正在搜索: " + productName);

            // Dismiss potential popups first
            dismissPopup(serial);
            Thread.sleep(1000);

            // Click "闪购" as per original flow (helps ensuring we are on home/interactive state)
            clickElementByText(serial, "闪购");
            Thread.sleep(2000);

            if (!clickSearchEntrance(serial)) {
                return reportError(users, "未找到搜索入口");
            }
            Thread.sleep(2000); // Wait for search page transition
            
            // Handle popups on the search page (New Page Occlusion)
            //dismissPopup(serial);
            
            // Ensure input field is focused
            //findAndClickInputField(serial);
            //Thread.sleep(1000);
            
            //adb.inputText(serial, productName);
            // String broadcastCmd = String.format("am broadcast -a ADB_INPUT_TEXT --es msg '%s'", 
            //                        productName.replace("'", "\\'"));
            // adb.executeShell(serial, broadcastCmd);
            // Thread.sleep(1000);



            
            if (!clickSearchButton(serial)) {
                return reportError(users, "未找到搜索按钮");
            }
            
            Thread.sleep(5000); // Wait for results
            dismissPopup(serial);

            // 6. 截图
            DingTalkUtil.sendTextMessageToEmployees(users, "正在截图分析...");
            String remotePng1 = "/sdcard/taobao_price_1.png";
            String localPng1 = "taobao_price_1_" + System.currentTimeMillis() + ".png";
            adb.screencap(serial, remotePng1);
            adb.pullFile(serial, remotePng1, localPng1);
            Thread.sleep(800);
            adb.swipe(serial, 500, 1600, 500, 400, 300);
            Thread.sleep(1200);
            String remotePng2 = "/sdcard/taobao_price_2.png";
            String localPng2 = "taobao_price_2_" + System.currentTimeMillis() + ".png";
            adb.screencap(serial, remotePng2);
            adb.pullFile(serial, remotePng2, localPng2);

            // 7. 调用大模型分析
            File imageFile1 = new File(localPng1);
            File imageFile2 = new File(localPng2);
            if (!imageFile1.exists() && !imageFile2.exists()) {
                 return reportError(users, "截图下载失败");
            }
            
            String prompt = "请综合分析两张淘宝搜索结果截图，找到所有显示的商品中，价格最低的商品（排除广告）。返回该商品的价格和名称。";
            java.io.File[] imgs;
            if (imageFile1.exists() && imageFile2.exists()) {
                imgs = new java.io.File[]{imageFile1, imageFile2};
            } else if (imageFile1.exists()) {
                imgs = new java.io.File[]{imageFile1};
            } else {
                imgs = new java.io.File[]{imageFile2};
            }
            String analysisResult;
            if (imgs.length == 1) {
                analysisResult = LLMUtil.analyzeImageWithGemini(imgs[0], prompt);
            } else {
                analysisResult = LLMUtil.analyzeImagesWithGemini(imgs, prompt);
            }
            
            // 8. 返回结果
            String finalResult = "分析结果：\n" + analysisResult;
            DingTalkUtil.sendTextMessageToEmployees(users, finalResult);
            
            // Clean up
            if (imageFile1.exists()) imageFile1.delete();
            if (imageFile2.exists()) imageFile2.delete();
            adb.executeShell(serial, "rm " + remotePng1);
            adb.executeShell(serial, "rm " + remotePng2);

            return finalResult;

        } catch (Exception e) {
            e.printStackTrace();
            return reportError(senderId != null ? java.util.Collections.singletonList(senderId) : null, "任务执行失败: " + e.getMessage());
        }
    }

    private boolean dismissPopup(String serial) throws Exception {
        AndroidDeviceManager adb = AndroidDeviceManager.getInstance();
        String dumpPath = "/sdcard/window_dump.xml";
        adb.executeShell(serial, "uiautomator dump --compressed " + dumpPath);
        String localDump = "window_dump_popup_" + System.currentTimeMillis() + ".xml";
        Thread.sleep(500);
        adb.pullFile(serial, dumpPath, localDump);
        File xmlFile = new File(localDump);
        boolean handled = false;
        if (xmlFile.exists() && xmlFile.length() > 50) {
            try {
                DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                Document doc = dBuilder.parse(xmlFile);
                doc.getDocumentElement().normalize();
                NodeList nList = doc.getElementsByTagName("*");
                for (int i = 0; i < nList.getLength(); i++) {
                    Node node = nList.item(i);
                    if (node.getNodeType() == Node.ELEMENT_NODE) {
                        Element element = (Element) node;
                        String rid = element.getAttribute("resource-id");
                        if ("com.taobao.taobao:id/poplayer_inner_view".equals(rid) || 
                            "com.taobao.taobao:id/layermanager_penetrate_webview_container_id".equals(rid)) {
                            String boundsStr = element.getAttribute("bounds");
                            int[] b = parseBounds(boundsStr);
                            if (b != null) {
                                // Try BACK button first (most reliable for system/app overlays)
                                adb.executeShell(serial, "input keyevent 4");
                                Thread.sleep(800);
                                
                                // Also try to tap top-right corner (common for close buttons)
                                // Adjusted Y to 120 to avoid status bar (assuming status bar < 100px)
                                int tx = b[2] - 50;
                                int ty = b[1] + 120;
                                adb.tap(serial, tx, ty);
                                
                                handled = true;
                                System.out.println("Dismissed popup: " + rid);
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {
            }
        }
        if (xmlFile.exists()) xmlFile.delete();
        adb.executeShell(serial, "rm " + dumpPath);
        return handled;
    }

    private boolean clickSearchBar(String serial) throws Exception {
        AndroidDeviceManager adb = AndroidDeviceManager.getInstance();
        String dumpPath = "/sdcard/window_dump.xml";
        File xmlFile = null;
        String localDump = null;
        
        // Single attempt
        adb.executeShell(serial, "uiautomator dump --compressed " + dumpPath);
        localDump = "window_dump_bar_" + System.currentTimeMillis() + ".xml";
        Thread.sleep(1000);
        adb.pullFile(serial, dumpPath, localDump);
        xmlFile = new File(localDump);
        
        if (!xmlFile.exists() || xmlFile.length() < 50) {
            if (xmlFile != null && xmlFile.exists()) xmlFile.delete();
            adb.executeShell(serial, "rm " + dumpPath);
            return false;
        }

        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(xmlFile);
            doc.getDocumentElement().normalize();
            
            NodeList nList = doc.getElementsByTagName("node");
            Element targetLayout = null;
            for (int i = 0; i < nList.getLength() && targetLayout == null; i++) {
                Node node = nList.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;
                    String cls = element.getAttribute("class");
                    String rid = element.getAttribute("resource-id");
                    if ("android.widget.FrameLayout".equals(cls) && "com.taobao.taobao:id/search_view".equals(rid)) {
                        Element viewEl = findDescendantWithClassAndAttr(element, "android.view.View", "content-desc", "搜索栏", "搜索栏");
                        if (viewEl != null) {
                            Node parent = viewEl.getParentNode();
                            if (parent != null && parent.getNodeType() == Node.ELEMENT_NODE) {
                                Element parentEl = (Element) parent;
                                Element scrollEl = findChildByClass(parentEl, "android.widget.ScrollView");
                                if (scrollEl != null) {
                                    Element linearEl = findChildByClass(scrollEl, "android.widget.LinearLayout");
                                    if (linearEl != null) {
                                        String boundsStr = linearEl.getAttribute("bounds");
                                        int[] bounds = parseBounds(boundsStr);
                                        if (bounds != null) {
                                            adb.tap(serial, (bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2);
                                            return true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            for (int i = 0; i < nList.getLength(); i++) {
                Node node = nList.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;
                    String contentDesc = element.getAttribute("content-desc");
                    String boundsStr = element.getAttribute("bounds");
                    
                    // Match "搜索栏" or similar
                    if ("搜索栏".equals(contentDesc) || (contentDesc != null && contentDesc.contains("搜索栏"))) {
                         int[] bounds = parseBounds(boundsStr);
                         if (bounds != null) {
                             System.out.println("Found Search Bar: " + contentDesc);
                             adb.tap(serial, (bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2);
                             return true;
                         }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (xmlFile != null && xmlFile.exists()) xmlFile.delete();
            adb.executeShell(serial, "rm " + dumpPath);
        }
        return false;
    }

    private void printChildren(Element parent) {
        if (parent == null) return;
        NodeList children = parent.getChildNodes();
        System.out.println("Children of " + parent.getAttribute("class") + ":");
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) node;
                System.out.println(" - " + el.getAttribute("class") + " (index=" + i + ")");
            }
        }
    }

    private Element getChildByClass(Element parent, String className, int index) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        int count = 0;
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) node;
                if (className.equals(element.getAttribute("class"))) {
                    count++;
                    if (count == index) {
                        return element;
                    }
                }
            }
        }
        return null;
    }

    private boolean clickSearchButton(String serial) throws Exception {
        AndroidDeviceManager adb = AndroidDeviceManager.getInstance();
        String dumpPath = "/sdcard/window_dump.xml";
        File xmlFile = null;
        String localDump = null;
        boolean found = false;
        
        for (int attempt = 0; attempt < 2; attempt++) {
            adb.executeShell(serial, "uiautomator dump --compressed " + dumpPath);
            localDump = "window_dump_btn_" + System.currentTimeMillis() + ".xml";
            Thread.sleep(1000);
            adb.pullFile(serial, dumpPath, localDump);
            xmlFile = new File(localDump);
            
            if (xmlFile.exists() && xmlFile.length() > 50) break;
            if (xmlFile.exists()) xmlFile.delete();
            adb.executeShell(serial, "rm " + dumpPath);
            xmlFile = null;
        }
        
        if (xmlFile == null) return false;

        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(xmlFile);
            doc.getDocumentElement().normalize();

            // Find //android.view.View[@content-desc="搜索"]
            NodeList nodes = doc.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                String desc = el.getAttribute("content-desc");
                
                // Relaxed: Match content-desc "搜索" regardless of class
                if (desc != null && (desc.equals("搜索") || desc.contains("搜索"))) {
                    String boundsStr = el.getAttribute("bounds");
                    int[] bounds = parseBounds(boundsStr);
                    if (bounds != null) {
                        System.out.println("Found Search Button via relaxed content-desc match: " + desc);
                        adb.tap(serial, (bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2);
                        found = true;
                        return true;
                    }
                }
            }
            
            System.out.println("Failed to find Search Button via content-desc match.");

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (found) {
                if (xmlFile != null && xmlFile.exists()) xmlFile.delete();
            } else {
                try {
                    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                    Document doc = dBuilder.parse(xmlFile);
                    Element best = findBestElement(doc, null, new String[]{"搜索"}, null, null, 700);
                    if (best != null) {
                        int[] b = parseBounds(best.getAttribute("bounds"));
                        if (b != null) {
                            System.out.println("Found Search Button via scored match.");
                            tapCenter(adb, serial, b);
                            found = true;
                            if (xmlFile != null && xmlFile.exists()) xmlFile.delete();
                            adb.executeShell(serial, "rm " + dumpPath);
                            return true;
                        }
                    }
                } catch (Exception ex) {
                }
                if (xmlFile != null && xmlFile.exists()) System.out.println("Search Button check failed. Dump file saved at: " + xmlFile.getAbsolutePath());
            }
            adb.executeShell(serial, "rm " + dumpPath);
        }
        
        return false;
    }

    private boolean clickSearchEntrance(String serial) throws Exception {
        AndroidDeviceManager adb = AndroidDeviceManager.getInstance();
        String dumpPath = "/sdcard/window_dump.xml";
        File xmlFile = null;
        String localDump = null;
        boolean found = false;
        
        // Single attempt is usually enough if app is stable, but keep simple retry
        for (int attempt = 0; attempt < 2; attempt++) {
            adb.executeShell(serial, "uiautomator dump --compressed " + dumpPath);
            localDump = "window_dump_entry_" + System.currentTimeMillis() + ".xml";
            Thread.sleep(1000);
            adb.pullFile(serial, dumpPath, localDump);
            xmlFile = new File(localDump);
            
            if (xmlFile.exists() && xmlFile.length() > 50) break;
            if (xmlFile.exists()) xmlFile.delete();
            adb.executeShell(serial, "rm " + dumpPath);
            xmlFile = null;
        }
        
        if (xmlFile == null) return false;

        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(xmlFile);
            doc.getDocumentElement().normalize();

            // Find the root element: //android.widget.FrameLayout[@resource-id="com.taobao.taobao:id/search_view"]
            Element searchView = null;
            NodeList nodes = doc.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                // Strict match
                if ("android.widget.FrameLayout".equals(el.getAttribute("class")) && 
                    "com.taobao.taobao:id/search_view".equals(el.getAttribute("resource-id"))) {
                    searchView = el;
                    break;
                }
            }
            
            // Fallback: Relaxed match (ignore class, loose ID match)
            if (searchView == null) {
                System.out.println("Strict match for search_view failed. Trying relaxed match...");
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element el = (Element) nodes.item(i);
                    String rid = el.getAttribute("resource-id");
                    if (rid != null && rid.contains("search_view")) {
                        System.out.println("Found relaxed match: " + el.getNodeName() + " id=" + rid + " class=" + el.getAttribute("class"));
                        searchView = el;
                        break;
                    }
                }
            }

            if (searchView != null) {
                System.out.println("Found search_view root. Traversing strict path...");
                
                // Path specified by user:
                // /android.widget.FrameLayout
                Element el1 = getChildByClass(searchView, "android.widget.FrameLayout", 1);
                // /android.widget.LinearLayout
                Element el2 = getChildByClass(el1, "android.widget.LinearLayout", 1);
                // /android.widget.FrameLayout
                Element el3 = getChildByClass(el2, "android.widget.FrameLayout", 1);
                // /android.widget.FrameLayout[2]
                Element el4 = getChildByClass(el3, "android.widget.FrameLayout", 2);
                // /android.widget.LinearLayout
                Element el5 = getChildByClass(el4, "android.widget.LinearLayout", 1);
                // /android.widget.FrameLayout[2]
                Element el6 = getChildByClass(el5, "android.widget.FrameLayout", 2);
                // /android.widget.ScrollView
                Element el7 = getChildByClass(el6, "android.widget.ScrollView", 1);
                // /android.widget.LinearLayout
                Element target = getChildByClass(el7, "android.widget.LinearLayout", 1);

                if (target != null) {
                    String boundsStr = target.getAttribute("bounds");
                    int[] bounds = parseBounds(boundsStr);
                    if (bounds != null) {
                        System.out.println("Found Search Input via strict XPath.");
                        adb.tap(serial, (bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2);
                        found = true;
                        return true;
                    }
                } else {
                    System.out.println("Failed to find Search Input via strict XPath.");
                    if (el1 == null) System.out.println("Debug: Failed at step 1 (FrameLayout)");
                    else if (el2 == null) System.out.println("Debug: Failed at step 2 (LinearLayout)");
                    else if (el3 == null) System.out.println("Debug: Failed at step 3 (FrameLayout)");
                    else if (el4 == null) System.out.println("Debug: Failed at step 4 (FrameLayout[2])");
                    else if (el5 == null) System.out.println("Debug: Failed at step 5 (LinearLayout)");
                    else if (el6 == null) System.out.println("Debug: Failed at step 6 (FrameLayout[2])");
                    else if (el7 == null) System.out.println("Debug: Failed at step 7 (ScrollView)");
                }
            } else {
                 System.out.println("Failed to find search_view root. Dumping first 10 nodes for debug:");
                 for (int i = 0; i < Math.min(10, nodes.getLength()); i++) {
                     Element el = (Element) nodes.item(i);
                     System.out.println("Node " + i + ": " + el.getNodeName() + " id=" + el.getAttribute("resource-id") + " class=" + el.getAttribute("class"));
                 }
            }
            
            // Global fallback: match content-desc "搜索栏" anywhere
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                String desc = el.getAttribute("content-desc");
                if (desc != null && (desc.equals("搜索栏") || desc.contains("搜索栏"))) {
                    String boundsStr = el.getAttribute("bounds");
                    int[] bounds = parseBounds(boundsStr);
                    if (bounds != null) {
                        System.out.println("Found Search Input via global content-desc match: " + desc);
                        tapCenter(adb, serial, bounds);
                        found = true;
                        return true;
                    }
                }
            }
            
            if (!found) {
                Element best = findBestElement(doc, new String[]{"search_view"}, new String[]{"搜索栏"}, null, new String[]{"android.view.View","android.widget.FrameLayout","android.widget.LinearLayout"}, 500);
                if (best != null) {
                    int[] b = parseBounds(best.getAttribute("bounds"));
                    if (b != null) {
                        System.out.println("Found Search Input via scored match.");
                        tapCenter(adb, serial, b);
                        found = true;
                        return true;
                    }
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (found) {
                if (xmlFile != null && xmlFile.exists()) xmlFile.delete();
            } else {
                if (xmlFile != null && xmlFile.exists()) {
                    System.out.println("Search Entrance check failed. Dump file saved at: " + xmlFile.getAbsolutePath());
                }
            }
            adb.executeShell(serial, "rm " + dumpPath);
        }
        
        return false;
    }

    private boolean findAndClickInputField(String serial) throws Exception {
        AndroidDeviceManager adb = AndroidDeviceManager.getInstance();
        String dumpPath = "/sdcard/window_dump.xml";
        File xmlFile = null;
        String localDump = null;
        boolean found = false;
        
        adb.executeShell(serial, "uiautomator dump --compressed " + dumpPath);
        localDump = "window_dump_input_" + System.currentTimeMillis() + ".xml";
        Thread.sleep(1000);
        adb.pullFile(serial, dumpPath, localDump);
        xmlFile = new File(localDump);
        
        if (!xmlFile.exists() || xmlFile.length() < 50) return false;
        
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(xmlFile);
            doc.getDocumentElement().normalize();
            
            // Try to find EditText using robust scorer
            // Features: class=EditText, text contains "搜索" (sometimes hint text), top area
            Element target = findBestElement(doc, 
                new String[]{ "search_src_text", "search_edit_frame" }, // rid candidates
                new String[]{ "搜索" }, // content-desc/text candidates
                new String[]{ "android.widget.EditText" }, // preferred class
                new String[]{ "android.widget.EditText" }, // class candidates
                500 // top area limit
            );
            
            if (target != null) {
                String boundsStr = target.getAttribute("bounds");
                int[] bounds = parseBounds(boundsStr);
                if (bounds != null) {
                    System.out.println("Found Input Field via robust match.");
                    tapCenter(adb, serial, bounds);
                    found = true;
                    return true;
                }
            }

            // Fallback: simple loop if scorer missed (unlikely but safe)
            NodeList nList = doc.getElementsByTagName("*");
            for (int i = 0; i < nList.getLength(); i++) {
                Node node = nList.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;
                    if ("android.widget.EditText".equals(element.getAttribute("class"))) {
                        String boundsStr = element.getAttribute("bounds");
                        int[] bounds = parseBounds(boundsStr);
                        if (bounds != null) {
                            System.out.println("Found EditText via class fallback.");
                            tapCenter(adb, serial, bounds);
                            found = true;
                            return true;
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (found) {
                if (xmlFile != null && xmlFile.exists()) xmlFile.delete();
            } else {
                 if (xmlFile != null && xmlFile.exists()) {
                     System.out.println("Input Field check failed. Dump file saved at: " + xmlFile.getAbsolutePath());
                 }
            }
            adb.executeShell(serial, "rm " + dumpPath);
        }
        return false;
    }
    
    private Element findDescendantWithClassAndAttr(Element root, String className, String attrName, String equalsVal, String containsVal) {
        if (root == null) return null;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) child;
                String cls = el.getAttribute("class");
                String attr = el.getAttribute(attrName);
                if (className.equals(cls)) {
                    if ((equalsVal != null && equalsVal.equals(attr)) || (containsVal != null && attr != null && attr.contains(containsVal))) {
                        return el;
                    }
                }
                Element deeper = findDescendantWithClassAndAttr(el, className, attrName, equalsVal, containsVal);
                if (deeper != null) return deeper;
            }
        }
        return null;
    }
    
    private Element findChildByClass(Element parent, String className) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) child;
                String cls = el.getAttribute("class");
                if (className.equals(cls)) return el;
            }
        }
        return null;
    }

 

    private boolean clickElementByText(String serial, String text) throws Exception {
        AndroidDeviceManager adb = AndroidDeviceManager.getInstance();
        int[] bounds = null;
        for (int attempt = 0; attempt < 5 && bounds == null; attempt++) {
            String dumpPath = "/sdcard/window_dump.xml";
            // Use --compressed to potentially reduce size and --nice to be less intrusive
            // Also handle the idle state issue which is common in dynamic apps like Taobao
            String dumpOut = adb.executeShell(serial, "uiautomator dump --compressed " + dumpPath);
            
            if (dumpOut != null && (dumpOut.contains("ERROR") || dumpOut.contains("Exception"))) {
                System.out.println("Dump failed (attempt " + attempt + "): " + dumpOut);
                // If idle state error, try to proceed anyway or wait a bit
                Thread.sleep(1000);
            }
            
            String localDump = "window_dump_" + System.currentTimeMillis() + ".xml";
            Thread.sleep(1000); // Increased wait for file flush
            adb.pullFile(serial, dumpPath, localDump);
            File xmlFile = new File(localDump);
            
            if (xmlFile.exists()) {
                if (xmlFile.length() > 50) {
                    bounds = findElementBounds(xmlFile, text);
                } else {
                    System.out.println("Dump file too small: " + xmlFile.length());
                    // Try alternative dump if primary fails
                    if (attempt == 4) {
                         System.out.println("Trying legacy dump method...");
                         adb.executeShell(serial, "uiautomator dump " + dumpPath);
                         Thread.sleep(1000);
                         adb.pullFile(serial, dumpPath, localDump);
                         xmlFile = new File(localDump);
                         if (xmlFile.exists() && xmlFile.length() > 50) {
                             bounds = findElementBounds(xmlFile, text);
                         }
                    }
                }
            } else {
                 System.out.println("Local dump file not found: " + localDump);
            }
            if (xmlFile.exists()) {
                if (bounds != null) {
                    xmlFile.delete();
                } else if (attempt < 4) {
                    xmlFile.delete();
                } else {
                    System.out.println("Element '" + text + "' not found. Dump file saved at: " + xmlFile.getAbsolutePath());
                }
            }
            adb.executeShell(serial, "rm " + dumpPath);
            if (bounds == null) {
                Thread.sleep(500);
            }
        }
        if (bounds != null) {
            int centerX = (bounds[0] + bounds[2]) / 2;
            int centerY = (bounds[1] + bounds[3]) / 2;
            adb.tap(serial, centerX, centerY);
            return true;
        }
        return false;
    }

    private String getXPath(Element element) {
        StringBuilder xpath = new StringBuilder();
        Node current = element;
        while (current != null && current.getNodeType() == Node.ELEMENT_NODE) {
            Element el = (Element) current;
            String tagName = el.getAttribute("class");
            if (tagName == null || tagName.isEmpty()) {
                tagName = el.getNodeName();
            }
            
            // Calculate index
            int index = 1;
            Node sibling = current.getPreviousSibling();
            while (sibling != null) {
                if (sibling.getNodeType() == Node.ELEMENT_NODE) {
                    Element sibEl = (Element) sibling;
                    String sibTagName = sibEl.getAttribute("class");
                    if (sibTagName == null || sibTagName.isEmpty()) {
                        sibTagName = sibEl.getNodeName();
                    }
                    if (sibTagName.equals(tagName)) {
                        index++;
                    }
                }
                sibling = sibling.getPreviousSibling();
            }
            
            xpath.insert(0, "/" + tagName + "[" + index + "]");
            current = current.getParentNode();
        }
        return "/" + xpath.toString();
    }

    private int[] findElementBounds(File xmlFile, String text) {
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(xmlFile);
            doc.getDocumentElement().normalize();

            NodeList nList = doc.getElementsByTagName("*");
            
            // Special handling for "闪购" with multi-pass strategy
            if ("闪购".equals(text)) {
                // Pass 1: Strict Match (TextView + Exact Text)
                for (int i = 0; i < nList.getLength(); i++) {
                    Node node = nList.item(i);
                    if (node.getNodeType() == Node.ELEMENT_NODE) {
                        Element element = (Element) node;
                        String nodeText = element.getAttribute("text");
                        String className = element.getAttribute("class");
                        
                        if ("android.widget.TextView".equals(className) && "闪购".equals(nodeText)) {
                             String boundsStr = element.getAttribute("bounds");
                             System.out.println("Strict Match for 闪购: " + getXPath(element));
                             return parseBounds(boundsStr);
                        }
                    }
                }
                
                // Pass 2: Relaxed Match (Any Class + Contains Text)
                // User mentioned it might be in action_bar_root, so we shouldn't be too strict on class
                for (int i = 0; i < nList.getLength(); i++) {
                    Node node = nList.item(i);
                    if (node.getNodeType() == Node.ELEMENT_NODE) {
                        Element element = (Element) node;
                        String nodeText = element.getAttribute("text");
                        String contentDesc = element.getAttribute("content-desc");
                        
                        if ((nodeText != null && nodeText.contains("闪购")) || 
                            (contentDesc != null && contentDesc.contains("闪购"))) {
                             String boundsStr = element.getAttribute("bounds");
                             System.out.println("Relaxed Match for 闪购: " + getXPath(element));
                             return parseBounds(boundsStr);
                        }
                    }
                }
                
                return null; // Failed to find "闪购"
            }

            // Standard search for other texts
            for (int i = 0; i < nList.getLength(); i++) {
                Node node = nList.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;
                    String nodeText = element.getAttribute("text");
                    String contentDesc = element.getAttribute("content-desc");
                    
                    if ((nodeText != null && nodeText.contains(text)) || 
                        (contentDesc != null && contentDesc.contains(text))) {
                        String boundsStr = element.getAttribute("bounds");
                        return parseBounds(boundsStr);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return null;
    }

    private int[] parseBounds(String bounds) {
        // [x1,y1][x2,y2]
        try {
            Pattern p = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");
            Matcher m = p.matcher(bounds);
            if (m.find()) {
                return new int[]{
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4))
                };
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    
    private void tapCenter(AndroidDeviceManager adb, String serial, int[] b) throws Exception {
        int cx = (b[0] + b[2]) / 2;
        int cy = (b[1] + b[3]) / 2;
        adb.tap(serial, cx, cy);
    }
    
    private Element findBestElement(Document doc, String[] ridContains, String[] descContains, String[] textContains, String[] classEquals, Integer maxTopY) {
        NodeList nodes = doc.getElementsByTagName("*");
        Element best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            int score = 0;
            String rid = el.getAttribute("resource-id");
            String desc = el.getAttribute("content-desc");
            String text = el.getAttribute("text");
            String cls = el.getAttribute("class");
            String clickable = el.getAttribute("clickable");
            String enabled = el.getAttribute("enabled");
            if (ridContains != null && rid != null) {
                for (String k : ridContains) {
                    if (k != null && rid.contains(k)) score += 3;
                }
            }
            if (descContains != null && desc != null) {
                for (String k : descContains) {
                    if (k != null && desc.contains(k)) score += 3;
                }
            }
            if (textContains != null && text != null) {
                for (String k : textContains) {
                    if (k != null && text.contains(k)) score += 2;
                }
            }
            if (classEquals != null && cls != null) {
                for (String k : classEquals) {
                    if (k != null && cls.equals(k)) score += 2;
                }
            }
            int[] b = null;
            if (maxTopY != null) {
                b = parseBounds(el.getAttribute("bounds"));
                if (b != null && b[1] < maxTopY) score += 1;
            }
            if ("true".equals(clickable)) score += 1;
            if ("true".equals(enabled)) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = el;
            }
        }
        if (bestScore >= 2) return best;
        return null;
    }

    private String reportError(List<String> users, String msg) {
        if (users != null && !users.isEmpty()) {
            try {
                DingTalkUtil.sendTextMessageToEmployees(users, msg);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return msg;
    }

    private void openSearchPage(String serial, String productName) throws Exception {
        String encodedName = URLEncoder.encode(productName, "UTF-8");
        // Try taobao scheme first which forces the app to open search results
        // URI format: taobao://s.taobao.com/search?q=EncodedName
        String uri = "taobao://s.taobao.com/search?q=" + encodedName;
        
        System.out.println("Attempting to open search via Intent: " + uri);
        
        List<String> cmd = new ArrayList<>();
        cmd.add("am");
        cmd.add("start");
        cmd.add("-a");
        cmd.add("android.intent.action.VIEW");
        cmd.add("-d");
        cmd.add(uri);
        
        // Use executeShell with List to safely handle arguments
        AndroidDeviceManager.getInstance().executeShell(serial, cmd);
    }
}
