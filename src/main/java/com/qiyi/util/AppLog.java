package com.qiyi.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AppLog {
    private static final Logger LOG = LoggerFactory.getLogger("workAgents");

    private AppLog() {
    }

    public static void info(Object msg) {
        LOG.info("{}", msg);
    }

    public static void warn(Object msg) {
        LOG.warn("{}", msg);
    }

    public static void error(Object msg) {
        if (msg instanceof Throwable) {
            Throwable t = (Throwable) msg;
            LOG.error("{}", t.getMessage(), t);
            return;
        }
        LOG.error("{}", msg);
    }

    public static void error(String msg, Throwable t) {
        LOG.error(msg, t);
    }
}
