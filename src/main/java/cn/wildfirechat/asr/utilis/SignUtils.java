package cn.wildfirechat.asr.utilis;

import cn.wildfirechat.asr.jpa.Application;
import org.apache.catalina.connector.RequestFacade;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SignUtils {
    public static Map<String, String> getSign(String appId, String secret) {
        Map<String, String> headers = new HashMap<>();
        String nonce = UUID.randomUUID().toString();
        String timestamp = System.currentTimeMillis() + "";
        String str2Sign = nonce + "|" + secret + "|" + timestamp;
        String sign = DigestUtils.sha1Hex(str2Sign);

        headers.put("nonce", nonce);
        headers.put("timestamp", timestamp);
        headers.put("sign", sign);
        headers.put("x-app-id", appId);

        return headers;
    }

    public static void main(String[] args) {
        Map<String, String> headers = getSign("testapp1", "123456");
        System.out.println(headers);
    }
}
