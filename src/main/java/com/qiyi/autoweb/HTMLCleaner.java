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

/**
 * HTML 清理与压缩工具
 * 负责移除无关标签/属性、截断长文本，并生成适合 LLM 的紧凑 HTML
 */
public class HTMLCleaner {

    // 只需要移除完全无用的标签，保留结构
    private static final Set<String> TAGS_TO_REMOVE = new HashSet<>(Arrays.asList(
            "script", "style", "noscript", "svg", "meta", "link", "head"
    ));

    // 保留对自动化定位有用的核心属性
    // id, class, name: 最基本的定位
    // type, value, placeholder: 输入框相关
    // role, aria-*: 可访问性相关，Playwright 推荐使用
    // href, src: 链接和资源
    // title, alt: 辅助文本
    // data-*: 有时用于测试定位 (如 data-testid)
    // for: label 关联
    private static final Set<String> ATTRIBUTES_TO_KEEP = new HashSet<>(Arrays.asList(
            "id", "class", "name", 
            "type", "value", "placeholder", 
            "href", "src", "action", "method",
            "role", "title", "alt", "target", "for"
    ));

    // 需要保留的空标签（自闭合或通常为空但有意义的标签）
    private static final Set<String> VOID_TAGS = new HashSet<>(Arrays.asList(
            "img", "input", "br", "hr", "textarea", "button", "select", "iframe"
    ));

    /**
     * 清理 HTML 内容，尽量保留结构与可定位信息
     * @param html 原始 HTML
     * @return 清理后的 HTML 片段
     */
    public static String clean(String html) {
        if (html == null || html.isEmpty()) return "";
        StorageSupport.log(null, "HTML_CLEAN", "before len=" + html.length(), null);

        Document doc = Jsoup.parse(html);

        doc.select("translate-tooltip-mtz,translate-selection-mtz,translate-text-mtz,translate-button-mtz").remove();
        doc.select("[src^=chrome-extension://],[href^=chrome-extension://],[src^=moz-extension://],[href^=moz-extension://],[src^=safari-extension://],[href^=safari-extension://]").remove();
        
        // 1. 移除不需要的标签
        doc.select(String.join(",", TAGS_TO_REMOVE)).remove();

        // 2. 移除注释
        removeComments(doc);

        // 3. 清理属性 & 截断长文本/数据
        Elements allElements = doc.getAllElements();
        for (Element el : allElements) {
            List<String> attributesToRemove = new ArrayList<>();
            for (Attribute attr : el.attributes()) {
                String key = attr.getKey().toLowerCase();
                String val = attr.getValue();

                // 截断过长的 data URI
                if ((key.equals("src") || key.equals("href")) && val.startsWith("data:") && val.length() > 100) {
                     attr.setValue(val.substring(0, 50) + "...(truncated)");
                     continue;
                }
                
                // 保留白名单属性
                if (ATTRIBUTES_TO_KEEP.contains(key)) continue;
                
                // 保留 aria- 开头的属性
                if (key.startsWith("aria-")) continue;
                
                // 保留 data-testid 等测试专用属性，或者其他可能的 data- 属性
                // 为了保险，保留所有 data- 属性，除非它们太长
                if (key.startsWith("data-")) {
                    if (val.length() > 50) { // 如果 data 属性值太长（可能是json数据），则移除
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
        }

        // 4. 截断长文本节点
        truncateLongText(doc.body());

        // 5. 移除完全空的无用容器节点
        pruneEmptyNodes(doc.body());

        // 6. 输出清理后的 HTML
        // 使用 prettyPrint(false) 可以压缩空白，但可能会影响文本内容的阅读（虽然 HTML 渲染时会合并空白）
        // 为了 LLM 阅读方便，保持一定的格式可能更好，或者压缩为单行。
        // 这里选择压缩为紧凑格式，因为 Token 数量是主要瓶颈。
        doc.outputSettings()
                .prettyPrint(false) // 关闭漂亮打印，减少换行和缩进
                .indentAmount(0);

        String cleaned = doc.body().html();
        
        // 进一步压缩连续空白
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        StorageSupport.log(null, "HTML_CLEAN", "after len=" + cleaned.length(), null);
        
        return cleaned;
    }

    /**
     * 递归截断过长的文本节点，避免单节点占用过多 token
     */
    private static void truncateLongText(Element element) {
        // 处理当前元素的直接文本节点
        for (org.jsoup.nodes.TextNode textNode : element.textNodes()) {
            String text = textNode.text();
            if (text.length() > 500) {
                textNode.text(text.substring(0, 500) + "...(truncated)");
            }
        }
        // 递归处理子元素
        for (Element child : element.children()) {
            truncateLongText(child);
        }
    }

    /**
     * 递归移除无属性、无子节点、无文本的空容器
     */
    private static void pruneEmptyNodes(Element element) {
        // 后序遍历：先处理子节点
        for (int i = element.children().size() - 1; i >= 0; i--) {
            pruneEmptyNodes(element.child(i));
        }

        // 根节点（body）不删除
        if (element.tagName().equals("body")) return;

        // 如果是保护标签，不删除
        if (VOID_TAGS.contains(element.tagName().toLowerCase())) return;

        // 如果有属性（清理后剩余的属性都是白名单内的），保留
        if (element.attributes().size() > 0) return;

        // 如果有子节点（经过剪枝后剩余的），保留
        if (!element.children().isEmpty()) return;

        // 如果有文本内容（非空白），保留
        if (element.hasText()) return;

        // 否则删除（无属性、无子节点、无文本的空容器）
        element.remove();
    }


    /**
     * 递归移除 HTML 注释节点
     */
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
