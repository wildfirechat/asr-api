package cn.wildfirechat.asr.controller;

import cn.wildfirechat.asr.pojo.PojoRecognizeReq;
import cn.wildfirechat.asr.service.AsrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
public class AsrController {
    private static final Logger LOG = LoggerFactory.getLogger(AsrController.class);
    @Autowired
    AsrService asrService;

    @GetMapping("/hello")
    public Object hello() {
        return "Hello from external controller";
    }

    @PostMapping(value = "/recognize", produces = "application/json;charset=UTF-8")
    public Object recognize(HttpServletRequest request, @RequestBody PojoRecognizeReq req) {
        return asrService.onRecognize(req.url, req.noLlm, req.noReuse, (String)request.getAttribute("app_id"));
    }
}
