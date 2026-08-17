package cn.wildfirechat.asr.controller;

import cn.wildfirechat.asr.jpa.Application;
import cn.wildfirechat.asr.pojo.PojoApplicationId;
import cn.wildfirechat.asr.pojo.LoginRequest;
import cn.wildfirechat.asr.pojo.UpdatePasswordRequest;
import cn.wildfirechat.asr.service.AdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private static final Logger LOG = LoggerFactory.getLogger(AdminController.class);

    @Autowired
    AdminService adminService;

    @GetMapping("/hello")
    public Object hello() {
        return "Hello from admin controller";
    }

    /*
    管理后台登陆
     */
    @PostMapping(value = "login", produces = "application/json;charset=UTF-8")
    public Object login(@RequestBody LoginRequest request, HttpServletResponse response) {
        return adminService.login(response, request.getAccount(), request.getPassword());
    }

    /*
    管理后台修改密码
     */
    @PostMapping(value = "update_pwd", produces = "application/json;charset=UTF-8")
    public Object updatePwd(@RequestBody UpdatePasswordRequest request) {
        return adminService.updatePassword(request.oldPassword, request.newPassword);
    }

    /*
    获取当前用户ID，管理后台和客户端都可以使用
     */
    @PostMapping(value = "account", produces = "application/json;charset=UTF-8")
    public Object getAccount() {
        return adminService.getAccount();
    }

    @Transactional
    @PostMapping(value = "/create_app", produces = "application/json;charset=UTF-8")
    public Object createApplication(@RequestBody Application application) throws Exception {
        return adminService.createApplication(application);
    }

    @Transactional
    @PostMapping(value = "/update_app", produces = "application/json;charset=UTF-8")
    public Object updateApplication(@RequestBody Application application) throws Exception {
        return adminService.updateApplication(application);
    }

    @Transactional
    @PostMapping(value = "/delete_app", produces = "application/json;charset=UTF-8")
    public Object deleteApplication(@RequestBody PojoApplicationId appId) throws Exception {
        return adminService.deleteApplication(appId.appId);
    }

    @PostMapping(value = "/get_app", produces = "application/json;charset=UTF-8")
    public Object getApplication(@RequestBody PojoApplicationId appId) {
        return adminService.getApplication(appId.appId);
    }

    @PostMapping(value = "/list_app", produces = "application/json;charset=UTF-8")
    public Object listApplication() {
        return adminService.listApplication();
    }
}
