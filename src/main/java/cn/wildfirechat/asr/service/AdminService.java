package cn.wildfirechat.asr.service;

import cn.wildfirechat.asr.jpa.User;
import cn.wildfirechat.asr.jpa.UserRepository;
import cn.wildfirechat.asr.jpa.Application;
import cn.wildfirechat.asr.jpa.ApplicationRepository;
import cn.wildfirechat.asr.utilis.AdminResult;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.util.TextUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.*;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static cn.wildfirechat.asr.utilis.AdminResult.AdminCode.*;


@Service
public class AdminService {
    private static final Logger LOG = LoggerFactory.getLogger(AdminService.class);

    @Autowired
    ApplicationRepository applicationRepository;


    @Autowired
    private UserRepository userRepository;

    public AdminResult login(HttpServletResponse httpResponse, String account, String password) {
        Subject subject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken(account, password);

        try {
            subject.login(token);
        } catch (UnknownAccountException uae) {
            return AdminResult.error(ERROR_NOT_EXIST);
        } catch (IncorrectCredentialsException ice) {
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        } catch (LockedAccountException lae) {
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        } catch (ExcessiveAttemptsException eae) {
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        } catch (AuthenticationException ae) {
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        }

        if (subject.isAuthenticated()) {
            long timeout = subject.getSession().getTimeout();
        } else {
            token.clear();
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        }

        Object sessionId = subject.getSession().getId();
        httpResponse.setHeader("authToken", sessionId.toString());

        return AdminResult.ok();
    }

    public AdminResult updatePassword(String oldPassword, String newPassword) {
        Subject subject = SecurityUtils.getSubject();
        if (!subject.isAuthenticated()) {
            return AdminResult.error(ERROR_NOT_LOGIN);
        }
        String account = (String) subject.getPrincipal();
        Optional<User> optionalUser = userRepository.findByAccount(account);
        if (!optionalUser.isPresent()) {
            return AdminResult.error(ERROR_NOT_EXIST);
        }

        User user = optionalUser.get();
        String md5 = new Base64().encodeToString(DigestUtils.getDigest("MD5").digest((oldPassword + user.getSalt()).getBytes(StandardCharsets.UTF_8)));
        if (!md5.equals(user.getPasswordMd5())) {
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        }

        String newMd5 = new Base64().encodeToString(DigestUtils.getDigest("MD5").digest((newPassword + user.getSalt()).getBytes(StandardCharsets.UTF_8)));
        user.setPasswordMd5(newMd5);
        userRepository.save(user);

        return AdminResult.ok();
    }

    public String getUserId() {
        Subject subject = SecurityUtils.getSubject();
        if (subject.isAuthenticated()) {
            return (String) subject.getPrincipal();
        }
        return null;
    }

    public AdminResult getAccount() {
        Subject subject = SecurityUtils.getSubject();
        if (subject.isAuthenticated()) {
            return AdminResult.ok(subject.getPrincipal());
        }
        return AdminResult.error(ERROR_NOT_EXIST);
    }


    public Object createApplication(Application application) throws Exception {
        if(!TextUtils.isEmpty(application.appId)) {
            Optional<Application> optionalApp = applicationRepository.findById(application.appId);
            if (optionalApp.isPresent()) {
                return AdminResult.error(ERROR_ALREADY_EXIST);
            }
        } else {
            application.appId = UUID.randomUUID().toString();
        }

        applicationRepository.save(application);
        return AdminResult.ok(application.appId);
    }

    public Object updateApplication(Application application) throws Exception {
        if(TextUtils.isEmpty(application.appId)) {
            return AdminResult.error(ERROR_MISS_PARAMETER);
        }
        applicationRepository.save(application);
        return AdminResult.ok();
    }

    public Object deleteApplication(String appId) throws Exception {
        applicationRepository.deleteById(appId);
        return AdminResult.ok();
    }

    public Object getApplication(String appId) {
        Optional<Application> optionalApp = applicationRepository.findById(appId);
        return optionalApp.map(AdminResult::ok).orElseGet(() -> AdminResult.error(ERROR_NOT_EXIST));
    }

    public Object listApplication() {
        Iterable<Application> iterable = applicationRepository.findAll();
        List<Application> list = new ArrayList<>();
        for (Application application : iterable) {
            list.add(application);
        }
        return AdminResult.ok(list);
    }
}
