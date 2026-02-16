package com.qiyi.service.autoweb;

import com.microsoft.playwright.Locator;

public final class AntDesignSupport implements WebDSL.UiFrameworkSupport {
    private static boolean clickAntDateCellIfPresent(WebDSL web, Locator dd, String dateTitle) {
        if (dd == null) return false;
        String t = dateTitle == null ? "" : dateTitle.trim();
        if (t.isEmpty()) return false;
        try {
            String sel = "td.ant-picker-cell[title=\"" + t + "\"] .ant-picker-cell-inner, td[title=\"" + t + "\"] .ant-picker-cell-inner";
            Locator cell = dd.locator(sel).first();
            web.waitForLocatorAttached(cell, 120);
            if (cell.count() > 0 && cell.isVisible()) {
                web.highlight(cell);
                try { cell.click(); } catch (Exception e) { web.tryDomClickFallback(cell); }
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean tryNavigateMonthAndClick(WebDSL web, Locator dd, String dateTitle, boolean goPrevFirst) {
        java.time.LocalDate target = null;
        try { target = java.time.LocalDate.parse(dateTitle); } catch (Exception ignored) { target = null; }
        if (target == null) return false;

        java.time.LocalDate now = null;
        try { now = java.time.LocalDate.now(); } catch (Exception ignored) { now = null; }
        boolean goPrev = goPrevFirst;
        if (now != null) goPrev = target.isBefore(now);

        boolean ok = tryNavigateMonthAndClickOnce(web, dd, dateTitle, goPrev);
        if (ok) return true;
        return tryNavigateMonthAndClickOnce(web, dd, dateTitle, !goPrev);
    }

    private static boolean tryNavigateMonthAndClickOnce(WebDSL web, Locator dd, String dateTitle, boolean goPrev) {
        if (clickAntDateCellIfPresent(web, dd, dateTitle)) return true;
        String navSel = goPrev ? ".ant-picker-header-prev-btn" : ".ant-picker-header-next-btn";
        int maxSteps = 24;
        for (int i = 0; i < maxSteps; i++) {
            try {
                Locator navs = dd.locator(navSel);
                int n = 0;
                try { n = navs.count(); } catch (Exception ignored) { n = 0; }
                if (n <= 0) break;
                Locator nav = goPrev ? navs.first() : navs.nth(Math.max(0, n - 1));
                web.waitForLocatorAttached(nav, 120);
                if (nav.count() > 0 && nav.isVisible()) {
                    try { nav.click(); } catch (Exception e) { web.tryDomClickFallback(nav); }
                    try { web.page().waitForTimeout(80); } catch (Exception ignored) {}
                } else {
                    break;
                }
            } catch (Exception ignored) {
                break;
            }
            if (clickAntDateCellIfPresent(web, dd, dateTitle)) return true;
        }
        return false;
    }

    @Override
    public boolean isDatePickerOverlayVisible(WebDSL web) {
        try {
            Locator o = web.locatorInCurrentContext(".ant-picker-dropdown").first();
            return o != null && o.count() > 0 && o.isVisible();
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public boolean tryPickDateTimeFromOverlay(WebDSL web, String text) {
        if (!isDatePickerOverlayVisible(web)) return false;
        String dateTitle = web.normalizeAntDateTitleFromText(text);
        String[] hms = web.normalizeHmsFromText(text);
        boolean hasDate = !(dateTitle == null || dateTitle.isEmpty());
        boolean hasTime = (hms != null);
        if (!hasDate && !hasTime) return false;

        Locator dropdowns = web.locatorInCurrentContext(".ant-picker-dropdown");
        Locator dd = null;
        try {
            int n = dropdowns.count();
            for (int i = 0; i < n; i++) {
                try {
                    Locator c = dropdowns.nth(i);
                    if (c.count() > 0 && c.isVisible()) {
                        dd = c;
                        break;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        if (dd == null) return false;

        try {
            Locator antPanel = dd.locator(".ant-picker-panel, .ant-picker-panel-container").first();
            web.waitForLocatorAttached(antPanel, 120);
            if (antPanel.count() == 0) return false;
        } catch (Exception ignored) {
            return false;
        }

        boolean clickedAny = false;
        if (hasDate) {
            try {
                clickedAny = clickAntDateCellIfPresent(web, dd, dateTitle);
                if (!clickedAny) clickedAny = tryNavigateMonthAndClick(web, dd, dateTitle, true);
            } catch (Exception ignored) {}
        }

        try {
            Locator cols = dd.locator(".ant-picker-time-panel-column");
            int cn = 0;
            int budget = 500;
            try { budget = Math.min(1500, Math.max(200, web.defaultTimeoutMs() / 10)); } catch (Exception ignored) { budget = 500; }
            long deadline = System.currentTimeMillis() + budget;
            while (System.currentTimeMillis() < deadline) {
                try { cn = cols.count(); } catch (Exception ignored) { cn = 0; }
                if (cn > 0) break;
                try { web.page().waitForTimeout(50); } catch (Exception ignored) {}
            }

            if (hms == null) {
                if (hasDate && cn > 0) {
                    boolean ok = true;
                    boolean timeClickedAny = false;
                    if (cn > 0) {
                        boolean c0 = web.clickAntTimeCellInColumn(cols.nth(0), "00");
                        timeClickedAny = c0 || timeClickedAny;
                        ok = c0 && ok;
                    }
                    if (cn > 1) {
                        boolean c1 = web.clickAntTimeCellInColumn(cols.nth(1), "00");
                        timeClickedAny = c1 || timeClickedAny;
                        ok = c1 && ok;
                    }
                    if (cn > 2) {
                        boolean c2 = web.clickAntTimeCellInColumn(cols.nth(2), "00");
                        timeClickedAny = c2 || timeClickedAny;
                        ok = c2 && ok;
                    }
                    clickedAny = timeClickedAny || clickedAny;
                    if (!ok) return false;
                }
            } else {
                if (cn == 0) return false;

                boolean requiredOk = true;
                boolean timeClickedAny = false;

                if (hms[0] != null && cn > 0) {
                    boolean ok = web.clickAntTimeCellInColumn(cols.nth(0), hms[0]);
                    timeClickedAny = ok || timeClickedAny;
                    requiredOk = ok && requiredOk;
                }
                if (hms[1] != null && cn > 1) {
                    boolean ok = web.clickAntTimeCellInColumn(cols.nth(1), hms[1]);
                    timeClickedAny = ok || timeClickedAny;
                    requiredOk = ok && requiredOk;
                }
                if (hms[2] != null && cn > 2) {
                    boolean ok = web.clickAntTimeCellInColumn(cols.nth(2), hms[2]);
                    timeClickedAny = ok || timeClickedAny;
                    requiredOk = ok && requiredOk;
                }
                if (hms[2] == null && cn > 2) {
                    boolean ok = web.clickAntTimeCellInColumn(cols.nth(2), "00");
                    timeClickedAny = ok || timeClickedAny;
                    requiredOk = ok && requiredOk;
                }

                clickedAny = timeClickedAny || clickedAny;
                if (!requiredOk) return false;
            }
        } catch (Exception ignored) {}

        return clickedAny;
    }

    @Override
    public boolean tryConfirmDatePicker(WebDSL web) {
        return false;
    }
}
