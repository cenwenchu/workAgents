package com.qiyi.service.dingtalk;

public class DingTalkUser {

    private String name;
    private String userid;

    public DingTalkUser()
    {
        
    }

    public DingTalkUser(String name, String userid) {
        this.name = name;
        this.userid = userid;
    }

    public String getName() {
        return name;
    }

    public String getUserid() {
        return userid;
    }
    
    public void setName(String name) {
        this.name = name;
    }

    public void setUserid(String userid) {
        this.userid = userid;
    }
}
