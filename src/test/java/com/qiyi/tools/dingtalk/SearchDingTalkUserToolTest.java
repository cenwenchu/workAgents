package com.qiyi.tools.dingtalk;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.service.dingtalk.DingTalkDepartment;
import com.qiyi.service.dingtalk.DingTalkUser;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class SearchDingTalkUserToolTest {

    @Mock
    private ToolContext context;

    @Mock
    private ToolMessenger messenger;

    private SearchDingTalkUserTool tool;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = spy(new SearchDingTalkUserTool());
    }

    @Test
    public void testGetName() {
        assertEquals("SearchDingTalkUserTool", tool.getName());
    }

    @Test
    public void testExecuteMissingName() {
        JSONObject params = new JSONObject();
        String result = tool.execute(params, context, messenger);
        assertEquals("请输入要搜索的用户名关键词 (name)", result);
    }

    @Test
    public void testExecuteNoMatch() throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", "NonExistentUser");

        // Mock empty departments or departments with no matching users
        List<DingTalkDepartment> depts = new ArrayList<>();
        DingTalkDepartment dept = new DingTalkDepartment("1", "DeptA", "0");
        dept.setUserList(Collections.singletonList(new DingTalkUser("Alice", "user1")));
        depts.add(dept);

        doReturn(depts).when(tool).getAllDepartments();

        String result = tool.execute(params, context, messenger);
        assertEquals("未找到包含 'NonExistentUser' 的用户。", result);
        verify(messenger).sendText(startsWith("未找到包含"));
    }

    @Test
    public void testExecuteMatch() throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", "Ali");

        List<DingTalkDepartment> depts = new ArrayList<>();
        DingTalkDepartment dept = new DingTalkDepartment("1", "DeptA", "0");
        // Alice contains Ali
        dept.setUserList(Collections.singletonList(new DingTalkUser("Alice", "user1")));
        depts.add(dept);

        doReturn(depts).when(tool).getAllDepartments();

        String result = tool.execute(params, context, messenger);
        assertTrue(result.contains("找到 1 位用户"));
        assertTrue(result.contains("姓名: Alice"));
        assertTrue(result.contains("UserId: user1"));
        assertTrue(result.contains("部门: DeptA"));
        
        verify(messenger).sendText(contains("找到 1 位用户"));
    }
    
    @Test
    public void testExecuteMultipleMatches() throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", "Test");

        List<DingTalkDepartment> depts = new ArrayList<>();
        DingTalkDepartment dept1 = new DingTalkDepartment("1", "DeptA", "0");
        dept1.setUserList(Collections.singletonList(new DingTalkUser("TestUser1", "user1")));
        
        DingTalkDepartment dept2 = new DingTalkDepartment("2", "DeptB", "1");
        dept2.setUserList(Collections.singletonList(new DingTalkUser("TestUser2", "user2")));
        
        depts.add(dept1);
        depts.add(dept2);

        doReturn(depts).when(tool).getAllDepartments();

        String result = tool.execute(params, context, messenger);
        assertTrue(result.contains("找到 2 位用户"));
        assertTrue(result.contains("TestUser1"));
        assertTrue(result.contains("TestUser2"));
        
        verify(messenger).sendText(contains("找到 2 位用户"));
    }
}
