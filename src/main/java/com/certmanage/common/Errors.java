package com.certmanage.common;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DuplicateKeyException;
import java.util.Map;
@RestControllerAdvice
public class Errors {
 @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class) ResponseEntity<?> status(org.springframework.web.server.ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"记录不存在或操作不允许":e.getReason()));}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.unprocessableEntity().body(Map.of("message",e.getMessage()==null?"输入不合法":e.getMessage()));}
 @ExceptionHandler(DuplicateKeyException.class) ResponseEntity<?> duplicate(){return ResponseEntity.status(409).body(Map.of("message","记录已存在"));}
 @ExceptionHandler(org.springframework.security.core.AuthenticationException.class) ResponseEntity<?> auth(){return ResponseEntity.status(401).body(Map.of("message","用户名或密码不正确"));}
 @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class) ResponseEntity<?> size(){return ResponseEntity.status(413).body(Map.of("message","文件大小超过限制"));}
 public static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
}
