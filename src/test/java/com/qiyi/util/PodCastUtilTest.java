package com.qiyi.util;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PodCastUtilTest {

    @Test
    public void waitForManualLoginShouldPollUntilLoggedIn() {
        Page page = mock(Page.class);
        ElementHandle signOut = mock(ElementHandle.class);

        when(page.isClosed()).thenReturn(false);
        when(page.querySelector(anyString())).thenReturn(null, null, signOut);
        doNothing().when(page).waitForTimeout(anyDouble());
        doNothing().when(page).waitForLoadState(any(LoadState.class), any(Page.WaitForLoadStateOptions.class));

        long startMs = System.currentTimeMillis();
        PodCastUtil.waitForManualLogin(page);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertTrue(elapsedMs < 2000);
        verify(page, atLeast(3)).querySelector(anyString());
        verify(page, atLeast(1)).waitForTimeout(anyDouble());
        verify(page, atLeast(1)).waitForLoadState(any(LoadState.class), any(Page.WaitForLoadStateOptions.class));
    }
}

