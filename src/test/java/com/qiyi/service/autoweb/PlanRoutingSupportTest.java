package com.qiyi.service.autoweb;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public class PlanRoutingSupportTest {

    @Test
    public void parsePlanFromText_shouldHandleLineCommentedPlan() {
        String text = String.join("\n",
                "// PLAN_START",
                "// Step 1:",
                "// - Description: 进入订单管理页面",
                "// - Target URL: https://example.com/orders",
                "// - Entry Point Action: Direct URL",
                "// - Status: CONFIRMED",
                "// PLAN_END",
                ""
        );

        AutoWebAgent.PlanParseResult parsed = PlanRoutingSupport.parsePlanFromText(text);
        Assertions.assertTrue(parsed.confirmed);
        Assertions.assertNotNull(parsed.steps);
        Assertions.assertEquals(1, parsed.steps.size());
        Assertions.assertEquals(1, parsed.steps.get(0).index);
        Assertions.assertEquals("进入订单管理页面", parsed.steps.get(0).description);
        Assertions.assertEquals("https://example.com/orders", parsed.steps.get(0).targetUrl);
        Assertions.assertEquals("Direct URL", parsed.steps.get(0).entryAction);
        Assertions.assertEquals("CONFIRMED", parsed.steps.get(0).status);
    }

    @Test
    public void parsePlanFromText_shouldHandleBlockCommentedPlan() {
        String text = String.join("\n",
                "/*",
                " * PLAN_START",
                " * Step 2:",
                " * - Description: 筛选待发货订单",
                " * - Target URL: CURRENT_PAGE",
                " * - Entry Point Action: 点击待发货筛选",
                " * - Status: CONFIRMED",
                " * PLAN_END",
                " */",
                "web.open(\"https://example.com\")",
                ""
        );

        AutoWebAgent.PlanParseResult parsed = PlanRoutingSupport.parsePlanFromText(text);
        Assertions.assertTrue(parsed.confirmed);
        Assertions.assertNotNull(parsed.steps);
        Assertions.assertEquals(1, parsed.steps.size());
        Assertions.assertEquals(2, parsed.steps.get(0).index);
        Assertions.assertEquals("筛选待发货订单", parsed.steps.get(0).description);
        Assertions.assertEquals("CURRENT_PAGE", parsed.steps.get(0).targetUrl);
        Assertions.assertEquals("点击待发货筛选", parsed.steps.get(0).entryAction);
        Assertions.assertEquals("CONFIRMED", parsed.steps.get(0).status);
    }

    @Test
    public void normalizeGeneratedGroovy_shouldKeepPlanMarkersInsideBlockComment() {
        String code = String.join("\n",
                "/*",
                "// PLAN_START",
                "* Step 1:",
                "* - Description: 进入订单管理页面",
                "* - Target URL: https://example.com/orders",
                "* - Entry Point Action: Direct URL",
                "* - Status: CONFIRMED",
                "// PLAN_END",
                "*/",
                "web.log(\"ok\")",
                ""
        );

        String normalized = AutoWebAgent.normalizeGeneratedGroovy(code);
        Assertions.assertTrue(normalized.contains("PLAN_START"));
        Assertions.assertTrue(normalized.contains("PLAN_END"));
        Assertions.assertFalse(normalized.contains("// PLAN_START"));
        Assertions.assertFalse(normalized.contains("// PLAN_END"));
    }

    @Test
    public void normalizeGeneratedGroovy_shouldRewriteClickListboxToSelectDropdown() {
        String code = String.join("\n",
                "// Step 2",
                "web.log('开始筛选平台为“淘宝”的商品...')",
                "web.click('text=\"资料已完善的平台\"')",
                "web.wait(500)",
                "// 在展开的下拉列表中选择“淘宝”",
                "web.click('div[role=\"listbox\"] >> text=\"淘宝\"')",
                "web.wait(500)",
                ""
        );

        String normalized = AutoWebAgent.normalizeGeneratedGroovy(code);
        Assertions.assertTrue(normalized.contains("web.selectDropdown(\"资料已完善的平台\", \"淘宝\")"));
        Assertions.assertFalse(normalized.contains("div[role=\"listbox\"] >> text=\"淘宝\""));
        Assertions.assertFalse(normalized.contains("web.click('text=\"资料已完善的平台\"')"));
    }

    @Test
    public void readCachedHtml_shouldTreatEmptyA11yCacheAsMiss() throws Exception {
        String oldUserDir = System.getProperty("user.dir");
        Path tmp = Files.createTempDirectory("autoweb-cache-test");
        try {
            System.setProperty("user.dir", tmp.toAbsolutePath().toString());
            HtmlSnapshotDao.writeCachedHtml(
                    1,
                    "https://example.com",
                    "Direct URL",
                    AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT,
                    true,
                    "{\n  \"ariaSnapshotText\": \"\"\n}",
                    "{\n  \"ariaSnapshotText\": \"\"\n}"
            );
            AutoWebAgent.HtmlSnapshot snap = HtmlSnapshotDao.readCachedHtml(
                    1,
                    "https://example.com",
                    "Direct URL",
                    AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT,
                    true
            );
            Assertions.assertNull(snap);
        } finally {
            System.setProperty("user.dir", oldUserDir == null ? "" : oldUserDir);
        }
    }

    @Test
    public void readCachedHtml_shouldTreatDocumentOnlyA11yCacheAsMiss() throws Exception {
        String oldUserDir = System.getProperty("user.dir");
        Path tmp = Files.createTempDirectory("autoweb-cache-test");
        try {
            System.setProperty("user.dir", tmp.toAbsolutePath().toString());
            HtmlSnapshotDao.writeCachedHtml(
                    1,
                    "https://example.com",
                    "Direct URL",
                    AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT,
                    true,
                    "{\n  \"ariaSnapshotText\": \"- document\"\n}",
                    "{\n  \"ariaSnapshotText\": \"- document\"\n}"
            );
            AutoWebAgent.HtmlSnapshot snap = HtmlSnapshotDao.readCachedHtml(
                    1,
                    "https://example.com",
                    "Direct URL",
                    AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT,
                    true
            );
            Assertions.assertNull(snap);
        } finally {
            System.setProperty("user.dir", oldUserDir == null ? "" : oldUserDir);
        }
    }

    @Test
    public void readCachedHtml_shouldKeepA11yCacheWhenAxTreePresent() throws Exception {
        String oldUserDir = System.getProperty("user.dir");
        Path tmp = Files.createTempDirectory("autoweb-cache-test");
        try {
            System.setProperty("user.dir", tmp.toAbsolutePath().toString());
            String payload = "{\n  \"ariaSnapshotText\": \"\",\n  \"axTree\": {\"nodes\": []}\n}";
            HtmlSnapshotDao.writeCachedHtml(
                    1,
                    "https://example.com",
                    "Direct URL",
                    AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT,
                    true,
                    payload,
                    payload
            );
            AutoWebAgent.HtmlSnapshot snap = HtmlSnapshotDao.readCachedHtml(
                    1,
                    "https://example.com",
                    "Direct URL",
                    AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT,
                    true
            );
            Assertions.assertNotNull(snap);
            Assertions.assertTrue(snap.cleanedHtml.contains("axTree"));
        } finally {
            System.setProperty("user.dir", oldUserDir == null ? "" : oldUserDir);
        }
    }

    @Test
    public void readCachedHtml_shouldReuseCacheAcrossDifferentQueryParams() throws Exception {
        String oldUserDir = System.getProperty("user.dir");
        Path tmp = Files.createTempDirectory("autoweb-cache-test");
        try {
            System.setProperty("user.dir", tmp.toAbsolutePath().toString());

            AutoWebAgent.HtmlSnapshot written = HtmlSnapshotDao.writeCachedHtml(
                    1,
                    "https://example.com/orders?a=1",
                    "Direct URL",
                    AutoWebAgent.HtmlCaptureMode.RAW_HTML,
                    true,
                    "<html><body>ok</body></html>",
                    "<html><body>ok</body></html>"
            );
            Assertions.assertNotNull(written);

            AutoWebAgent.HtmlSnapshot read = HtmlSnapshotDao.readCachedHtml(
                    2,
                    "https://example.com/orders?b=2",
                    "Direct URL",
                    AutoWebAgent.HtmlCaptureMode.RAW_HTML,
                    true
            );
            Assertions.assertNotNull(read);
            Assertions.assertEquals(written.cacheKey, read.cacheKey);
            Assertions.assertEquals("https://example.com/orders", read.url);
        } finally {
            System.setProperty("user.dir", oldUserDir == null ? "" : oldUserDir);
        }
    }

    @Test
    public void readCachedHtml_shouldReuseCacheAcrossDifferentEntryActions() throws Exception {
        String oldUserDir = System.getProperty("user.dir");
        Path tmp = Files.createTempDirectory("autoweb-cache-test");
        try {
            System.setProperty("user.dir", tmp.toAbsolutePath().toString());

            AutoWebAgent.HtmlSnapshot written = HtmlSnapshotDao.writeCachedHtml(
                    1,
                    "https://example.com/orders?a=1",
                    "From Menu Click",
                    AutoWebAgent.HtmlCaptureMode.RAW_HTML,
                    true,
                    "<html><body>ok</body></html>",
                    "<html><body>ok</body></html>"
            );
            Assertions.assertNotNull(written);

            AutoWebAgent.HtmlSnapshot read = HtmlSnapshotDao.readCachedHtml(
                    2,
                    "https://example.com/orders?b=2",
                    "Direct URL",
                    AutoWebAgent.HtmlCaptureMode.RAW_HTML,
                    true
            );
            Assertions.assertNotNull(read);
            Assertions.assertEquals("https://example.com/orders", read.url);
            Assertions.assertTrue(read.cleanedHtml.contains("ok"));
        } finally {
            System.setProperty("user.dir", oldUserDir == null ? "" : oldUserDir);
        }
    }
}
