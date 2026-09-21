package com.cofco.qiqihar.graintrade.messaging.interfaceadapter;

import com.cofco.qiqihar.graintrade.messaging.application.*;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/messages")
public class UserMessageController {
    private final UserMessageService service;
    public UserMessageController(UserMessageService service){this.service=service;}
    @PostMapping ApiResponse<UserMessageView> send(Authentication a,@RequestBody SendUserMessageCommand c){return new ApiResponse<>(service.send(a.getName(),c));}
    @GetMapping("/inbox") ApiResponse<List<UserMessageView>> inbox(Authentication a,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return new ApiResponse<>(service.inbox(a.getName(),page,size));}
    @GetMapping("/sent") ApiResponse<List<UserMessageView>> sent(Authentication a,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return new ApiResponse<>(service.sent(a.getName(),page,size));}
    @GetMapping("/unread-count") ApiResponse<Map<String,Long>> unread(Authentication a){return new ApiResponse<>(Map.of("unreadCount",service.unreadCount(a.getName())));}
    @PostMapping("/{id}/read") ApiResponse<Map<String,Boolean>> read(Authentication a,@PathVariable UUID id){return new ApiResponse<>(Map.of("read",service.markRead(a.getName(),id)));}
}
