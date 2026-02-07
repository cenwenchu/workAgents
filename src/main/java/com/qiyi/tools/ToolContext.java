package com.qiyi.tools;

/**
 * 工具执行上下文（与渠道无关的“会话信息”）。
 *
 * <p>常用于：</p>
 * <ul>
 *     <li>区分不同用户/企业的配置与权限</li>
 *     <li>给工具提供必要的调用范围信息（例如企业内通讯录查询）</li>
 * </ul>
 */
public interface ToolContext {
    String getUserId();

    String getEnterpriseId();
}
