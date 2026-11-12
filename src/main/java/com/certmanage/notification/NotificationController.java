package com.certmanage.notification;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.security.Principal;
import com.certmanage.common.Audit;
@RestController @RequestMapping("/api/v1")
public class NotificationController {
 private final NotificationService service;private final Audit audit;public NotificationController(NotificationService s,Audit a){service=s;audit=a;}
 @GetMapping("/system/notifications") public Object get(){return service.get();}
 @PutMapping("/system/notifications") public void put(@RequestBody Map<String,Object>b,Principal p){service.save(b,audit.actor(p.getName()));audit.record(p.getName(),"NOTIFICATION_CONFIG","SYSTEM",1);}
 @GetMapping("/notification-deliveries") public Object records(){return service.records();}
 @PostMapping("/notification-deliveries/{id}/retry") public void retry(@PathVariable long id,Principal p){service.retry(id);audit.record(p.getName(),"NOTIFICATION_RETRY","DELIVERY",id);}
 @PostMapping("/system/notifications/scan") public Object scan(Principal p){int count=service.scan();audit.record(p.getName(),"NOTIFICATION_SCAN","POLICY",1);return Map.of("created",count);}
}
