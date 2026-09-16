package com.cofco.qiqihar.graintrade.annotation.interfaceadapter;

import com.cofco.qiqihar.graintrade.annotation.application.*;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/overview/map-annotation")
public class UserMapAnnotationController {
    private final UserMapAnnotationService service;
    public UserMapAnnotationController(UserMapAnnotationService service) { this.service=service; }

    @GetMapping
    ApiResponse<UserMapAnnotationView> current(Authentication authentication) {
        return new ApiResponse<>(service.current(authentication.getName()).orElse(null));
    }
    @PutMapping
    ApiResponse<UserMapAnnotationView> save(Authentication authentication,
            @RequestBody UserMapAnnotationCommand command) {
        return new ApiResponse<>(service.save(authentication.getName(),command));
    }
    @DeleteMapping
    ApiResponse<Map<String,Boolean>> delete(Authentication authentication) {
        return new ApiResponse<>(Map.of("deleted",service.delete(authentication.getName())));
    }
}
