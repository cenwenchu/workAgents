package com.qiyi.service.dingtalk;

import java.util.List;

public class DingTalkDepartment {

    private String deptId;
    private String name;
    private String parentId;
    private List<DingTalkUser> userList;

    public DingTalkDepartment()
    {

    }

    public DingTalkDepartment(String deptId, String name, String parentId) {
        this.deptId = deptId;
        this.name = name;
        this.parentId = parentId;
    }
        
    public List<DingTalkUser> getUserList() {
        return userList;
    }

    public void setUserList(List<DingTalkUser> userList) {
        this.userList = userList;
    }

    public String getDeptId() {
        return deptId;
    }

    public String getName() {
        return name;
    }   

    public String getParentId() {
        return parentId;
    }   

    public void setDeptId(String deptId) {
        this.deptId = deptId;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }
}
