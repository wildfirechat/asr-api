package cn.wildfirechat.asr.service;

import cn.wildfirechat.common.ErrorCode;
import cn.wildfirechat.pojos.OutputApplicationUserInfo;
import cn.wildfirechat.sdk.AdminConfig;
import cn.wildfirechat.sdk.UserAdmin;
import cn.wildfirechat.sdk.model.IMResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;

/**
 * 客户端鉴权：客户端从 IM 服务获取认证码（authCode），本服务调用 IM 服务的管理接口校验认证码，得到用户 ID。
 * <p>
 * 认证码由 IM 服务签发，1 分钟内有效，客户端每次请求前重新获取。
 */
@Service
public class AuthService {
    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    @Value("${im.admin_url}")
    private String mAdminUrl;

    @Value("${im.admin_secret}")
    private String mAdminSecret;

    @PostConstruct
    void init() {
        AdminConfig.initAdmin(mAdminUrl, mAdminSecret);
    }

    /**
     * 校验认证码
     *
     * @return 用户 ID，认证码为空、无效或者校验出错时返回 null
     */
    public String verifyAuthCode(String authCode) {
        if (!StringUtils.hasText(authCode)) {
            return null;
        }
        try {
            IMResult<OutputApplicationUserInfo> result = UserAdmin.applicationGetUserInfo(authCode);
            if (result != null && result.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS && result.getResult() != null) {
                return result.getResult().getUserId();
            }
            LOG.warn("verify authCode failed, code={}, msg={}", result == null ? null : result.getCode(), result == null ? null : result.getMsg());
        } catch (Exception e) {
            LOG.error("verify authCode exception", e);
        }
        return null;
    }
}
