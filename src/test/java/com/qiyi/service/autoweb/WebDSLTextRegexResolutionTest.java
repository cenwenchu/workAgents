package com.qiyi.service.autoweb;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;

import java.lang.reflect.Method;
import java.util.function.Consumer;

public class WebDSLTextRegexResolutionTest {

    @Test
    public void tryResolveClickTargetByText_shouldSupportRegexLookingPlainText() throws Exception {
        Page page = Mockito.mock(Page.class);
        Consumer<String> logger = s -> {};
        WebDSL web = new WebDSL(page, logger);

        Locator empty = Mockito.mock(Locator.class);
        Mockito.when(empty.first()).thenReturn(empty);
        Mockito.when(empty.count()).thenReturn(0);
        Mockito.when(empty.isVisible()).thenReturn(false);
        Mockito.doNothing().when(empty).waitFor(Mockito.any());

        Locator regexMatch = Mockito.mock(Locator.class);
        Mockito.when(regexMatch.first()).thenReturn(regexMatch);
        Mockito.when(regexMatch.count()).thenReturn(1);
        Mockito.when(regexMatch.isVisible()).thenReturn(true);
        Mockito.doNothing().when(regexMatch).waitFor(Mockito.any());

        Mockito.when(page.locator(Mockito.anyString())).thenAnswer(invocation -> {
            String selector = invocation.getArgument(0);
            if ("role=button[name=/搜\\s*索/]".equals(selector)) return regexMatch;
            return empty;
        });

        Method m = WebDSL.class.getDeclaredMethod("tryResolveClickTargetByText", String.class);
        m.setAccessible(true);
        Locator out = (Locator) m.invoke(web, "搜\\s*索");

        Assertions.assertSame(regexMatch, out);
    }

    @Test
    public void waitFor_shouldNormalizeTextRegexSelector() {
        Page page = Mockito.mock(Page.class);
        Consumer<String> logger = s -> {};
        WebDSL web = new WebDSL(page, logger);

        Locator match = Mockito.mock(Locator.class);
        Mockito.when(match.first()).thenReturn(match);
        Mockito.doNothing().when(match).waitFor(Mockito.any());

        String expected = "text=/共\\s*\\d+\\s*条/";
        Mockito.when(page.locator(Mockito.anyString())).thenAnswer(invocation -> {
            String selector = invocation.getArgument(0);
            Assertions.assertEquals(expected, selector);
            return match;
        });

        web.waitFor("text=共\\d+条");
    }

    @Test
    public void clickButton_shouldEscapeQuotesAndPreferRoleButton() {
        Page page = Mockito.mock(Page.class);
        Consumer<String> logger = s -> {};
        WebDSL web = Mockito.spy(new WebDSL(page, logger));
        Mockito.doNothing().when(web).click(anyString());

        Locator empty = Mockito.mock(Locator.class);
        Mockito.when(empty.first()).thenReturn(empty);
        Mockito.when(empty.count()).thenReturn(0);
        Mockito.when(empty.isVisible()).thenReturn(false);
        Mockito.doNothing().when(empty).waitFor(any());

        Locator match = Mockito.mock(Locator.class);
        Mockito.when(match.first()).thenReturn(match);
        Mockito.when(match.count()).thenReturn(1);
        Mockito.when(match.isVisible()).thenReturn(true);
        Mockito.doNothing().when(match).waitFor(any());

        String expected = "role=button[name=\"He said \\\"OK\\\"\"]";
        Mockito.when(page.locator(Mockito.anyString())).thenAnswer(invocation -> {
            String selector = invocation.getArgument(0);
            if (expected.equals(selector)) return match;
            return empty;
        });

        web.clickButton("He said \"OK\"");
        Mockito.verify(web).click(expected);
    }

    @Test
    public void clickTab_shouldPreferRoleTabByName() {
        Page page = Mockito.mock(Page.class);
        Consumer<String> logger = s -> {};
        WebDSL web = Mockito.spy(new WebDSL(page, logger));
        Mockito.doNothing().when(web).click(anyString());

        Locator empty = Mockito.mock(Locator.class);
        Mockito.when(empty.first()).thenReturn(empty);
        Mockito.when(empty.count()).thenReturn(0);
        Mockito.when(empty.isVisible()).thenReturn(false);
        Mockito.doNothing().when(empty).waitFor(any());

        Locator match = Mockito.mock(Locator.class);
        Mockito.when(match.first()).thenReturn(match);
        Mockito.when(match.count()).thenReturn(1);
        Mockito.when(match.isVisible()).thenReturn(true);
        Mockito.doNothing().when(match).waitFor(any());

        String expected = "role=tab[name=\"待发货\"]";
        Mockito.when(page.locator(Mockito.anyString())).thenAnswer(invocation -> {
            String selector = invocation.getArgument(0);
            if (expected.equals(selector)) return match;
            return empty;
        });

        web.clickTab("待发货");
        Mockito.verify(web).click(expected);
    }

    @Test
    public void click_shouldPromoteTextMatchToClickableAncestor() {
        Page page = Mockito.mock(Page.class);
        Consumer<String> logger = s -> {};
        WebDSL web = new WebDSL(page, logger);

        Locator textLoc = Mockito.mock(Locator.class);
        Mockito.when(textLoc.first()).thenReturn(textLoc);
        Mockito.when(textLoc.count()).thenReturn(1);
        Mockito.when(textLoc.isVisible()).thenReturn(true);
        Mockito.doNothing().when(textLoc).waitFor(Mockito.any());
        Mockito.doNothing().when(textLoc).scrollIntoViewIfNeeded();
        Mockito.doNothing().when(textLoc).hover();

        Locator clickable = Mockito.mock(Locator.class);
        Mockito.when(clickable.first()).thenReturn(clickable);
        Mockito.when(clickable.count()).thenReturn(1);
        Mockito.when(clickable.isVisible()).thenReturn(true);
        Mockito.doNothing().when(clickable).waitFor(Mockito.any());
        Mockito.doNothing().when(clickable).scrollIntoViewIfNeeded();
        Mockito.doNothing().when(clickable).click(Mockito.any());
        Mockito.doNothing().when(clickable).click();

        Mockito.when(textLoc.locator(Mockito.startsWith("xpath=ancestor-or-self"))).thenReturn(clickable);

        Mockito.when(page.locator(Mockito.anyString())).thenAnswer(invocation -> {
            String selector = invocation.getArgument(0);
            if ("text=\"订单\"".equals(selector)) return textLoc;
            return Mockito.mock(Locator.class);
        });
        Mockito.doNothing().when(page).waitForTimeout(Mockito.anyInt());

        web.click("text=\"订单\"");
        Mockito.verify(clickable).click();
        Mockito.verify(textLoc, Mockito.never()).click();
    }

    @Test
    public void ensureFrame_shouldRecoverBySavedNameEvenWhenDetached() {
        Page page = Mockito.mock(Page.class);
        Consumer<String> logger = s -> {};

        Frame detached = Mockito.mock(Frame.class);
        Mockito.when(detached.page()).thenReturn(page);
        Mockito.when(detached.name()).thenReturn("epaasDialogFrame");
        Mockito.when(detached.url()).thenReturn("https://www.erp321.com/epaas-dialog-frame.html");
        Mockito.when(detached.isDetached()).thenReturn(true);

        WebDSL web = new WebDSL(detached, logger);

        Frame recovered = Mockito.mock(Frame.class);
        Mockito.when(recovered.isDetached()).thenReturn(false);
        Mockito.when(recovered.name()).thenReturn("epaasDialogFrame");

        Mockito.when(page.frames()).thenReturn(java.util.Arrays.asList(recovered));

        Locator loc = Mockito.mock(Locator.class);
        Mockito.when(loc.count()).thenReturn(1);
        Mockito.when(recovered.locator(Mockito.anyString())).thenReturn(loc);
        Mockito.when(page.locator(Mockito.anyString())).thenThrow(new RuntimeException("Should not use page context"));

        Assertions.assertEquals(1, web.count("div"));
        Mockito.verify(recovered).locator(Mockito.anyString());
    }
}
