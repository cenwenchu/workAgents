package com.qiyi.component.impl;

import com.qiyi.component.AgentComponent;
import com.qiyi.component.ComponentId;
import com.qiyi.component.ComponentStatus;
import com.qiyi.config.AppConfig;
import com.qiyi.service.futu.FutuOpenD;

public final class FutuComponent implements AgentComponent {
    private static final ComponentId ID = ComponentId.FUTU;

    @Override
    public ComponentId id() {
        return ID;
    }

    @Override
    public String description() {
        return "Futu OpenD connection/runtime";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String configurationHint() {
        return "Optional config: futu.opend.host / futu.opend.port (default 127.0.0.1:11111)";
    }

    @Override
    public void start() {
        AppConfig cfg = AppConfig.getInstance();
        String host = cfg.getFutuOpenDHost();
        int port = cfg.getFutuOpenDPort();
        FutuOpenD.getInstance().connect(host, port);
    }

    @Override
    public void stop() {
        FutuOpenD.getInstance().disconnect();
    }

    @Override
    public ComponentStatus status() {
        boolean connected = FutuOpenD.getInstance().isConnected();
        if (connected) {
            return ComponentStatus.running(ID.id(), "connected");
        }
        return ComponentStatus.stopped(ID.id(), "disconnected");
    }
}
