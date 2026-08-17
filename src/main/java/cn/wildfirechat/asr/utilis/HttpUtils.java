/*
 * This file is part of the Wildfire Chat package.
 * (c) Heavyrain2012 <heavyrain.lee@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

package cn.wildfirechat.asr.utilis;

import cn.wildfirechat.asr.jpa.Application;
import okhttp3.*;
import org.apache.commons.codec.digest.DigestUtils;

public class HttpUtils {
    private static Headers getSignHeaders(String appId, String secret) {
        int nonce = (int)(Math.random() * 100000 + 3);
        long timestamp = System.currentTimeMillis();
        String str = nonce + "|" + secret + "|" + timestamp;
        String sign = DigestUtils.sha1Hex(str);
        Headers headers = new Headers.Builder().add("nonce", nonce+"").add("timestamp", ""+timestamp).add("sign", sign).add("x-app-id", appId +"").build();
        return headers;
    }

    public static void main(String[] args) {
        Headers headers = getSignHeaders("testapp1", "123456");
        System.out.println(headers.toString());
    }
}
