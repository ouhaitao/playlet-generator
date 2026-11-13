package org.playlet.api.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常 → 友好 JSON（单用户本地部署：错误消息直接给人看）。
 * 业务校验失败（IllegalArgument/IllegalState）→ 400 + message；未预料异常 → 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onBusiness(RuntimeException e) {
        return Map.of("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> onNotFound(NotFoundException e) {
        return Map.of("message", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> onUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return Map.of("message", "服务器内部错误: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }
}
