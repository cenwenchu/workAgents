package com.qiyi.podcast;

public class PodCastItem {
    public String title;
    public String linkString;
    public String channelName;
    public String dateTimeString;
    public boolean isProcessed;

    public String toString() {
        return "PodCastItem{" +
                "title='" + title + '\'' +
                ", linkString='" + linkString + '\'' +
                ", channelName='" + channelName + '\'' +
                ", dateTimeString='" + dateTimeString + '\'' +
                ", isProcessed=" + isProcessed +
                '}';
    }

}
