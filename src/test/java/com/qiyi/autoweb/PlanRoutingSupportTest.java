package com.qiyi.autoweb;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
}
