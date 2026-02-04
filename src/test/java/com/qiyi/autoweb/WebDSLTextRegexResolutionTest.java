package com.qiyi.autoweb;

import com.microsoft.playwright.Locator;
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
}
