package com.qiyi.service.autoweb;

import com.microsoft.playwright.Locator;

public final class DefaultDatePickerSupport implements WebDSL.UiFrameworkSupport {
    @Override
    public boolean isDatePickerOverlayVisible(WebDSL web) {
        try {
            String[] overlays = new String[] {
                    ".ant-picker-dropdown",
                    ".ant-calendar-picker-container",
                    ".el-picker-panel",
                    ".layui-laydate"
            };
            for (String sel : overlays) {
                try {
                    Locator o = web.locatorInCurrentContext(sel).first();
                    if (o != null && o.count() > 0 && o.isVisible()) return true;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public boolean tryPickDateTimeFromOverlay(WebDSL web, String text) {
        return false;
    }

    @Override
    public boolean tryConfirmDatePicker(WebDSL web) {
        if (!isDatePickerOverlayVisible(web)) return false;
        try {
            String[] candidates = new String[] {
                    ".ant-picker-dropdown .ant-picker-ok button",
                    ".ant-calendar-picker-container .ant-calendar-ok-btn",
                    ".el-picker-panel .el-picker-panel__footer button:has-text(\"确定\")",
                    ".el-picker-panel .el-picker-panel__footer .el-button--primary",
                    ".layui-laydate .laydate-footer-btns .laydate-btns-confirm",
                    "role=button[name=\"确定\"]",
                    "role=button[name=\"确认\"]",
                    "role=button[name=\"OK\"]",
                    "text=\"确定\"",
                    "text=\"确认\""
            };
            for (String sel : candidates) {
                try {
                    Locator btn = web.locatorInCurrentContext(sel).first();
                    if (btn != null && btn.count() > 0 && btn.isVisible()) {
                        web.highlight(btn);
                        try {
                            int timeoutMs = 1500;
                            try {
                                timeoutMs = Math.min(5000, Math.max(1500, web.defaultTimeoutMs() / 2));
                            } catch (Exception ignored) { timeoutMs = 1500; }
                            if (!web.waitForClickable(btn, timeoutMs)) continue;
                            btn.click();
                        } catch (Exception e) {
                            web.tryDomClickFallback(btn);
                        }
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }
}
