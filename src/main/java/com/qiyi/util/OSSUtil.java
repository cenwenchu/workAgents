package com.qiyi.util;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.qiyi.config.AppConfig;

import java.io.File;
import java.net.URL;
import java.util.Date;

public class OSSUtil {

    public static String uploadFile(File file) {
        if (file == null || !file.exists()) {
            return null;
        }

        AppConfig config = AppConfig.getInstance();
        String endpoint = config.getAliyunOssEndpoint();
        String accessKeyId = config.getAliyunOssAccessKeyId();
        String accessKeySecret = config.getAliyunOssAccessKeySecret();
        String bucketName = config.getAliyunOssBucketName();

        if (endpoint == null || accessKeyId == null || accessKeySecret == null || bucketName == null) {
            System.err.println("Aliyun OSS configuration is missing.");
            return null;
        }

        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

        try {
            // Use a unique object name to avoid conflicts, or just overwrite?
            // Let's prepend timestamp
            String objectName = "workAgents/" + System.currentTimeMillis() + "_" + file.getName();
            
            ossClient.putObject(bucketName, objectName, file);
            
            // Generate a presigned URL valid for 1 hour
            Date expiration = new Date(new Date().getTime() + 3600 * 1000);
            URL url = ossClient.generatePresignedUrl(bucketName, objectName, expiration);
            
            return url.toString();
        } catch (Exception e) {
            System.err.println("OSS Upload Error: " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }
}
