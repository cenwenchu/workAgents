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

public class CreateEventToolTest {

    @Mock
    private ToolContext context;
    
    @Mock
    private ToolContext senderContext;

    private CreateEventTool tool;

    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        tool = spy(new CreateEventTool());
        
        when(context.getSenderId()).thenReturn("sender1");
        when(context.getAtUserIds()).thenReturn(new ArrayList<>());
        when(context.withAtUserIds(anyList())).thenReturn(senderContext);
        
        // Mock protected methods
        doReturn("unionId_sender1").when(tool).getUnionIdByUserId("sender1");
        doReturn("unionId_user1").when(tool).getUnionIdByUserId("user1");
        doNothing().when(tool).sendTextMessageToEmployees(anyList(), anyString());
        doReturn("eventId123").when(tool).createCalendarEvent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    public void testGetName() {
        assertEquals("create_event", tool.getName());
    }

    @Test
    public void testExecuteMissingTime() {
        JSONObject params = new JSONObject();
        params.put("summary", "Meeting");
        // missing startTime and endTime
        
        String result = tool.execute(params, context);
        assertEquals("Error: No attendees", result); // Fail because no attendees first
        
        // Add attendees
        params.put("attendees", "UserOne");
        // Mock department lookup
        DingTalkDepartment dept = new DingTalkDepartment();
        dept.setName("DeptA"); // Set name to ensure it's processed
        dept.setUserList(Collections.singletonList(new DingTalkUser("user1", "UserOne")));
        try {
            doReturn(Collections.singletonList(dept)).when(tool).getAllDepartments();
        } catch (Exception e) {}
        
        result = tool.execute(params, context);
        assertEquals("Error: Missing time", result);
    }

    @Test
    public void testExecuteSuccess() throws Exception {
        JSONObject params = new JSONObject();
        params.put("summary", "Meeting");
        params.put("startTime", "2023-10-01 10:00:00");
        params.put("endTime", "2023-10-01 11:00:00");
        params.put("description", "Desc");
        params.put("location", "Room 1");
        params.put("attendees", "UserOne");

        DingTalkDepartment dept = new DingTalkDepartment();
        dept.setDeptId("1");
        dept.setName("DeptA");
        DingTalkUser user = new DingTalkUser();
        user.setUserid("user1");
        user.setName("UserOne");
        dept.setUserList(Collections.singletonList(user));

        doReturn(Collections.singletonList(dept)).when(tool).getAllDepartments();
        
        // Ensure getUnionIdByUserId is mocked correctly for both sender and user
        doReturn("unionId_sender1").when(tool).getUnionIdByUserId("sender1");
        doReturn("unionId_user1").when(tool).getUnionIdByUserId("user1");

        String result = tool.execute(params, context);
        
        assertTrue(result.contains("日程创建成功"), "Result should contain success message. Actual: " + result);
        assertTrue(result.contains("EventID: eventId123"), "Result should contain EventID. Actual: " + result);
        
        verify(tool).createCalendarEvent(eq("unionId_sender1"), eq("Meeting"), eq("Desc"), anyString(), anyString(), eq("Room 1"), anyList());
    }
}
