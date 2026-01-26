package com.qiyi.autoweb;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HTMLCleaner {

    // 只需要移除完全无用的标签，保留结构
    private static final Set<String> TAGS_TO_REMOVE = new HashSet<>(Arrays.asList(
            "script", "style", "noscript", "svg", "meta", "link", "iframe", "head"
    ));

    // 保留对自动化定位有用的核心属性
    // id, class, name: 最基本的定位
    // type, value, placeholder: 输入框相关
    // role, aria-*: 可访问性相关，Playwright 推荐使用
    // href, src: 链接和资源
    // title, alt: 辅助文本
    // data-*: 有时用于测试定位 (如 data-testid)
    private static final Set<String> ATTRIBUTES_TO_KEEP = new HashSet<>(Arrays.asList(
            "id", "class", "name", 
            "type", "value", "placeholder", 
            "href", "src", "action", "method",
            "role", "title", "alt", "target"
    ));

    public static String clean(String html) {
        if (html == null || html.isEmpty()) return "";

        Document doc = Jsoup.parse(html);
        
        // 1. 移除不需要的标签
        doc.select(String.join(",", TAGS_TO_REMOVE)).remove();

        // 2. 移除注释
        removeComments(doc);

        // 3. 清理属性
        Elements allElements = doc.getAllElements();
        for (Element el : allElements) {
            List<String> attributesToRemove = new ArrayList<>();
            for (Attribute attr : el.attributes()) {
                String key = attr.getKey().toLowerCase();
                
                // 保留白名单属性
                if (ATTRIBUTES_TO_KEEP.contains(key)) continue;
                
                // 保留 aria- 开头的属性
                if (key.startsWith("aria-")) continue;
                
                // 保留 data-testid 等测试专用属性，或者其他可能的 data- 属性
                // 为了保险，保留所有 data- 属性，除非它们太长
                if (key.startsWith("data-")) {
                    if (attr.getValue().length() > 50) { // 如果 data 属性值太长（可能是json数据），则移除
                        attributesToRemove.add(key);
                    }
                    continue;
                }

                // 移除其他所有属性 (style, onclick, onmouseover, width, height, etc.)
                attributesToRemove.add(key);
            }
            
            for (String attrKey : attributesToRemove) {
                el.removeAttr(attrKey);
            }
            
            // 如果是空标签且没有重要属性（除了结构性标签如 div, span, p, table, tr, td 等），可能考虑移除？
            // 暂时不移除空标签，因为可能是占位符或者等待动态加载的容器
        }

        // 4. 输出清理后的 HTML
        // 使用 prettyPrint(false) 可以压缩空白，但可能会影响文本内容的阅读（虽然 HTML 渲染时会合并空白）
        // 为了 LLM 阅读方便，保持一定的格式可能更好，或者压缩为单行。
        // 这里选择压缩为紧凑格式，因为 Token 数量是主要瓶颈。
        doc.outputSettings()
                .prettyPrint(false) // 关闭漂亮打印，减少换行和缩进
                .indentAmount(0);

        String cleaned = doc.body().html();
        
        // 进一步压缩连续空白
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        
        return cleaned;
    }

    private static void removeComments(org.jsoup.nodes.Node node) {
        for (int i = 0; i < node.childNodeSize();) {
            org.jsoup.nodes.Node child = node.childNode(i);
            if (child.nodeName().equals("#comment"))
                child.remove();
            else {
                removeComments(child);
                i++;
            }
        }
    }
}
