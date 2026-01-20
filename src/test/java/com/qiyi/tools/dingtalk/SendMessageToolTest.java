package com.qiyi.tools.dingtalk;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.dingtalk.DingTalkDepartment;
import com.qiyi.dingtalk.DingTalkUser;
import com.qiyi.tools.ToolContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class SendMessageToolTest {

    @Mock
    private ToolContext context;
    
    @Mock
    private ToolContext senderContext;

    private SendMessageTool tool;

    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        tool = spy(new SendMessageTool());
        
        when(context.getSenderId()).thenReturn("sender1");
        when(context.getAtUserIds()).thenReturn(new ArrayList<>());
        when(context.withAtUserIds(anyList())).thenReturn(senderContext);
        
        // Mock protected methods
        doNothing().when(tool).sendTextMessageToEmployees(anyList(), anyString());
    }

    @Test
    public void testGetName() {
        assertEquals("send_message", tool.getName());
    }

    @Test
    public void testExecuteEmptyContent() {
        JSONObject params = new JSONObject();
        String result = tool.execute(params, context);
        assertEquals("Error: Empty content", result);
        verify(senderContext).sendText("未提供消息内容，未执行发送。");
    }

    @Test
    public void testExecuteWithDepartments() throws Exception {
        JSONObject params = new JSONObject();
        params.put("content", "Hello");
        params.put("departments", "DeptA");

        DingTalkDepartment dept = new DingTalkDepartment();
        dept.setDeptId("1");
        dept.setName("DeptA");
        DingTalkUser user = new DingTalkUser();
        user.setUserid("user1");
        user.setName("User One");
        dept.setUserList(Collections.singletonList(user));

        doReturn(Collections.singletonList(dept)).when(tool).getAllDepartments();

        String result = tool.execute(params, context);
        
        assertTrue(result.contains("Message Sent to 1 users"));
        verify(tool).sendTextMessageToEmployees(argThat(list -> list.contains("user1")), contains("Hello"));
    }
    
    @Test
    public void testExecuteWithNames() throws Exception {
        JSONObject params = new JSONObject();
        params.put("content", "Hello");
        params.put("names", "UserOne");

        DingTalkDepartment dept = new DingTalkDepartment();
        dept.setDeptId("1");
        dept.setName("DeptA");
        DingTalkUser user = new DingTalkUser();
        user.setUserid("user1");
        user.setName("UserOne");
        dept.setUserList(Collections.singletonList(user));

        doReturn(Collections.singletonList(dept)).when(tool).getAllDepartments();

        String result = tool.execute(params, context);
        
        assertTrue(result.contains("Message Sent to 1 users"), "Result should contain success message. Actual: " + result);
        verify(tool).sendTextMessageToEmployees(argThat(list -> list.contains("user1")), contains("Hello"));
    }
}
